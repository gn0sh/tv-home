"""Google TV backend over the Android TV Remote protocol v2 (androidtvremote2 0.3.2), task C4, finding #6.

Files in the tv-remote config folder (user-only ACL):
  atv-client-cert.pem       our client certificate (public)
  atv-client-key.pem        our client private key as encrypted PKCS#8; never written unencrypted
  atv-client-key.pw.sealed  the random password for that key, sealed with DPAPI for Brian's Windows user
  atv-tv-cert.sha256        SHA-256 of the TV's certificate, pinned when pairing succeeds

Pairing: the TV shows a 6-hex-digit code. The first byte of it is a check value derived from a hash over both
certificates and the rest of the code, so a man in the middle that swaps in its own certificate passes our check
only by luck, about 1 in 256 per attempt (and Brian sees a failed code each time). It is not a cryptographic
guarantee. After pairing, every connection must present the pinned certificate. The check runs inside the TLS
handshake, before the protocol sends anything, and a mismatch refuses all commands (CertificateChanged).
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import secrets
import ssl
import tempfile
import time
import unicodedata
from collections.abc import Callable
from pathlib import Path
from typing import Any

from androidtvremote2 import AndroidTVRemote, CannotConnect, ConnectionClosed, InvalidAuth
from androidtvremote2.certificate_generator import generate_selfsigned_cert
from cryptography.hazmat.primitives import serialization

from .devices import CertificateChanged, DeviceUnreachable, Key, NotPaired
from .secretstore import Protector, SecretStoreError
from .tlspin import PinMismatch, TvSSLContext, fingerprint, peer_fingerprint, pinned_client_context  # noqa: F401

log = logging.getLogger("tv_remote.googletv")

CLIENT_NAME = "tv-remote"
CERT_FILE = "atv-client-cert.pem"
KEY_FILE = "atv-client-key.pem"
KEY_PW_FILE = "atv-client-key.pw.sealed"
PIN_FILE = "atv-tv-cert.sha256"
CONNECT_TIMEOUT = 8.0
APP_WAIT = 1.5
# The TV pings every 5 s on an idle link; this much silence means the link is half-open, so reconnect first.
SILENCE_LIMIT = 7.0

KEYCODES: dict[str, str] = {
    "UP": "KEYCODE_DPAD_UP",
    "DOWN": "KEYCODE_DPAD_DOWN",
    "LEFT": "KEYCODE_DPAD_LEFT",
    "RIGHT": "KEYCODE_DPAD_RIGHT",
    "OK": "KEYCODE_DPAD_CENTER",
    "BACK": "KEYCODE_BACK",
    "HOME": "KEYCODE_HOME",
    "PLAY_PAUSE": "KEYCODE_MEDIA_PLAY_PAUSE",
    "REWIND": "KEYCODE_MEDIA_REWIND",
    "FAST_FORWARD": "KEYCODE_MEDIA_FAST_FORWARD",
}
_PACKAGE_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$")

# Fixed reason strings only: library and OS error text never reaches the tool output.
REASON_NO_ANSWER = "no answer"
REASON_CLOSED = "connection closed"
REASON_TIMEOUT = "timed out"


def transport_open(transport: Any) -> bool:
    return transport is not None and not transport.is_closing()


class KeyVault:
    """Client cert, encrypted key with its sealed password, and the pinned TV fingerprint."""

    def __init__(self, state_dir: Path, protector: Protector):
        self.dir, self.protector = Path(state_dir), protector

    @property
    def cert_path(self) -> Path:
        return self.dir / CERT_FILE

    def has_identity(self) -> bool:
        return all((self.dir / f).is_file() for f in (CERT_FILE, KEY_FILE, KEY_PW_FILE))

    def create_identity(self) -> None:
        """New client cert and key. Clears the pin: a new identity always needs a fresh pairing."""
        cert_pem, key_pem = generate_selfsigned_cert(CLIENT_NAME)
        password = secrets.token_bytes(32)
        sealed_pw = self.protector.protect(password)  # fails before anything is written if DPAPI is unavailable
        key = serialization.load_pem_private_key(key_pem, password=None)
        enc = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.BestAvailableEncryption(password),
        )
        del key_pem, key
        self.dir.mkdir(parents=True, exist_ok=True)
        self.clear_pin()
        _write_atomic(self.dir / KEY_PW_FILE, sealed_pw)
        _write_atomic(self.dir / KEY_FILE, enc)
        _write_atomic(self.cert_path, cert_pem)

    def _password(self) -> bytes:
        try:
            return self.protector.unprotect((self.dir / KEY_PW_FILE).read_bytes())
        except FileNotFoundError:
            raise NotPaired("googletv") from None
        except SecretStoreError as exc:
            log.warning("Google TV client key could not be unsealed: %s", type(exc).__name__)
            raise NotPaired("googletv") from None

    def identity_usable(self) -> bool:
        if not self.has_identity():
            return False
        try:
            self.ssl_context()
        except (NotPaired, ssl.SSLError, OSError, ValueError):
            return False
        return True

    def ssl_context(self, pin: str | None = None) -> TvSSLContext:
        """Client TLS context. With `pin`, the handshake fails unless the TV presents that certificate."""
        password = self._password()
        ctx = pinned_client_context(pin)
        try:
            ctx.load_cert_chain(str(self.cert_path), str(self.dir / KEY_FILE), password=password)
        except (ssl.SSLError, OSError) as exc:
            # OSError: a file vanished or is unreadable. Either way the identity is unusable until re-paired.
            log.warning("Google TV client key could not be loaded: %s", type(exc).__name__)
            raise NotPaired("googletv") from None
        return ctx

    def pinned(self) -> str | None:
        try:
            value = (self.dir / PIN_FILE).read_text().strip().lower()
        except FileNotFoundError:
            return None
        return value if re.fullmatch(r"[0-9a-f]{64}", value) else None

    def pin(self, fp: str) -> None:
        _write_atomic(self.dir / PIN_FILE, fp.encode())

    def clear_pin(self) -> None:
        (self.dir / PIN_FILE).unlink(missing_ok=True)


def _write_atomic(path: Path, data: bytes) -> None:
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}-", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


class SealedKeyRemote(AndroidTVRemote):
    """AndroidTVRemote whose TLS context comes from the vault (with the pin), so the library never reads a key."""

    def __init__(self, vault: KeyVault, host: str, pin: str | None = None):
        super().__init__(CLIENT_NAME, str(vault.cert_path), str(vault.dir / KEY_FILE), host)
        self._vault, self._pin = vault, pin

    async def async_generate_cert_if_missing(self) -> bool:
        if self._vault.has_identity():
            return False
        await self._loop.run_in_executor(None, self._vault.create_identity)
        self._ssl_context = None
        return True

    async def _create_ssl_context(self) -> ssl.SSLContext:
        if self._ssl_context is None:
            self._ssl_context = await self._loop.run_in_executor(None, self._vault.ssl_context, self._pin)
        return self._ssl_context

    def pin_mismatch(self) -> bool:
        return bool(getattr(self._ssl_context, "tv_pin_mismatch", False))

    async def async_connect(self) -> None:
        await super().async_connect()
        # Record when the TV last sent anything. It pings every 5 s on an idle link, so a long silence means the
        # link is half-open (e.g. Wi-Fi blip) even though the transport still looks open.
        proto = self._remote_message_protocol
        self._last_rx = time.monotonic()
        if proto is not None:
            inner = proto.data_received

            def data_received(data: bytes) -> None:
                self._last_rx = time.monotonic()
                inner(data)

            proto.data_received = data_received

    def last_rx(self) -> float:
        return getattr(self, "_last_rx", 0.0)

    def api_transport(self) -> Any:
        proto = self._remote_message_protocol
        return proto.transport if proto is not None else None

    def pairing_transport(self) -> Any:
        proto = self._pairing_message_protocol
        return proto.transport if proto is not None else None


RemoteFactory = Callable[[KeyVault, str, "str | None"], Any]


class GoogleTvRemote:
    """GoogleTvBackend over androidtvremote2 (see devices.GoogleTvBackend)."""

    def __init__(self, host: str, vault: KeyVault, remote_factory: RemoteFactory = SealedKeyRemote,
                 clock: Callable[[], float] = time.monotonic):
        self.host, self._vault, self._factory, self._clock = host, vault, remote_factory, clock
        self._remote: Any = None
        self._connected = False
        self._pending_pin: str | None = None
        self._lock = asyncio.Lock()

    def _unreachable(self, reason: str) -> DeviceUnreachable:
        return DeviceUnreachable("googletv", self.host, reason)

    def _new_remote(self, pin: str | None = None) -> Any:
        self._drop()
        self._remote = self._factory(self._vault, self.host, pin)
        return self._remote

    def _drop(self) -> None:
        if self._remote is not None:
            self._remote.disconnect()
        self._remote, self._connected = None, False

    async def _ensure_connected(self) -> Any:
        async with self._lock:
            # androidtvremote2 silently drops sends on a closed link (base.py _send_message), and the TV closes idle
            # links and drops them on standby or a Wi-Fi blip. So a link only counts while its transport is open;
            # otherwise reconnect, which re-checks the pin.
            if self._connected and self._remote is not None and self._link_alive(self._remote):
                return self._remote
            pinned, has_identity = await asyncio.to_thread(lambda: (self._vault.pinned(), self._vault.has_identity()))
            if pinned is None or not has_identity:
                self._drop()
                raise NotPaired("googletv")
            remote = self._new_remote(pinned)
            try:
                await asyncio.wait_for(remote.async_connect(), CONNECT_TIMEOUT)
            except InvalidAuth:
                mismatch = remote.pin_mismatch()
                self._drop()
                if mismatch:
                    log.warning("Google TV certificate does not match the pinned fingerprint; refusing.")
                    raise CertificateChanged("googletv") from None
                raise NotPaired("googletv") from None
            except (asyncio.TimeoutError, TimeoutError):
                self._drop()
                raise self._unreachable(REASON_TIMEOUT) from None
            except ConnectionClosed:
                self._drop()
                raise self._unreachable(REASON_CLOSED) from None
            except (CannotConnect, OSError):
                self._drop()
                raise self._unreachable(REASON_NO_ANSWER) from None
            transport = remote.api_transport()
            if peer_fingerprint(transport) != pinned:  # second check; the handshake hook should already have refused
                self._drop()
                log.warning("Google TV certificate does not match the pinned fingerprint; refusing.")
                raise CertificateChanged("googletv")
            if not transport_open(transport):
                self._drop()
                raise self._unreachable(REASON_CLOSED)
            self._connected = True
            return remote

    def _link_alive(self, remote: Any) -> bool:
        return transport_open(remote.api_transport()) and self._clock() - remote.last_rx() <= SILENCE_LIMIT

    def _send(self, remote: Any, fn: Callable[[], None]) -> None:
        if not transport_open(remote.api_transport()):
            self._drop()
            raise self._unreachable(REASON_CLOSED)
        try:
            fn()
        except ConnectionClosed:
            self._drop()
            raise self._unreachable(REASON_CLOSED) from None

    async def send_key(self, key: Key) -> None:
        code = KEYCODES[key]  # KeyError here would mean the enum and the map drifted; tests guard it
        remote = await self._ensure_connected()
        self._send(remote, lambda: remote.send_key_command(code))

    async def open_link(self, uri: str) -> None:
        if not uri.startswith("https://") or any(c.isspace() or unicodedata.category(c)[0] == "C" for c in uri):
            raise ValueError("only https app links built by tv-remote are sent")
        remote = await self._ensure_connected()
        self._send(remote, lambda: remote.send_launch_app_command(uri))

    async def launch_package(self, package: str) -> None:
        if not _PACKAGE_RE.fullmatch(package):
            raise ValueError("not an Android package name")
        remote = await self._ensure_connected()
        self._send(remote, lambda: remote.send_launch_app_command(package))

    async def current_app(self) -> str | None:
        remote = await self._ensure_connected()
        if remote.current_app is None:
            got = asyncio.Event()
            cb = lambda _app: got.set()  # noqa: E731
            remote.add_current_app_updated_callback(cb)
            try:
                await asyncio.wait_for(got.wait(), APP_WAIT)
            except (asyncio.TimeoutError, TimeoutError):
                pass
            finally:
                remote.remove_current_app_updated_callback(cb)
        return remote.current_app

    async def start_pairing(self) -> str:
        async with self._lock:
            self._drop()
            self._pending_pin = None  # a pin from an earlier, abandoned pairing must never be used
            try:
                # A key that can no longer be unsealed (e.g. Windows profile restored) would make pairing impossible;
                # start over with a new identity, which also clears the old pin.
                if await asyncio.to_thread(lambda: self._vault.has_identity() and not self._vault.identity_usable()):
                    log.warning("Google TV client identity is unusable; creating a new one.")
                    await asyncio.to_thread(self._vault.create_identity)
                remote = self._new_remote(None)
                await remote.async_generate_cert_if_missing()
                await asyncio.wait_for(remote.async_start_pairing(), CONNECT_TIMEOUT)
            except SecretStoreError:
                self._drop()
                raise NotImplementedError("Secure key storage is not available here; pairing runs on Windows.") from None
            except (asyncio.TimeoutError, TimeoutError):
                self._drop()
                raise self._unreachable(REASON_TIMEOUT) from None
            except ConnectionClosed:
                self._drop()
                raise self._unreachable(REASON_CLOSED) from None
            except (CannotConnect, OSError):
                self._drop()
                raise self._unreachable(REASON_NO_ANSWER) from None
            self._pending_pin = peer_fingerprint(remote.pairing_transport())
            return ("A 6-character code is now shown on the Google TV. Call tv_pair again with device='googletv' "
                    "and that code.")

    async def finish_pairing(self, code: str) -> str:
        async with self._lock:
            remote, pending = self._remote, self._pending_pin
            if remote is None or pending is None:
                raise NotPaired("googletv")
            try:
                await remote.async_finish_pairing(code)
            except InvalidAuth:
                return "That code did not match. Check the code on the TV and call tv_pair again with it."
            except ConnectionClosed:
                self._drop()
                self._pending_pin = None
                raise self._unreachable(REASON_CLOSED) from None
            await asyncio.to_thread(self._vault.pin, pending)
            self._pending_pin = None
            self._drop()
            return "Google TV paired."
