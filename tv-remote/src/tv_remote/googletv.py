"""Google TV backend over the Android TV Remote protocol v2 (androidtvremote2 0.3.2), task C4, finding #6.

Files in the tv-remote config folder (user-only ACL):
  atv-client-cert.pem     our client certificate (public)
  atv-client-key.sealed   our client private key, sealed with DPAPI; never written unencrypted
  atv-tv-cert.sha256      SHA-256 of the TV's certificate, pinned when pairing succeeds

The pairing code's hash covers both certificates, so the certificate seen during a successful pairing is the real
TV's. After that, every connection must present the same certificate or nothing is sent (CertificateChanged).
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import re
import secrets
import ssl
import tempfile
from collections.abc import Callable
from pathlib import Path
from typing import Any

from androidtvremote2 import AndroidTVRemote, CannotConnect, ConnectionClosed, InvalidAuth
from androidtvremote2.certificate_generator import generate_selfsigned_cert
from cryptography.hazmat.primitives import serialization

from .devices import CertificateChanged, DeviceUnreachable, Key, NotPaired
from .secretstore import Protector, SecretStoreError

log = logging.getLogger("tv_remote.googletv")

CLIENT_NAME = "tv-remote"
CERT_FILE = "atv-client-cert.pem"
KEY_FILE = "atv-client-key.sealed"
PIN_FILE = "atv-tv-cert.sha256"
CONNECT_TIMEOUT = 8.0
APP_WAIT = 1.5

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


def fingerprint(der: bytes) -> str:
    return hashlib.sha256(der).hexdigest()


def peer_fingerprint(transport: Any) -> str | None:
    ssl_obj = transport.get_extra_info("ssl_object") if transport is not None else None
    der = ssl_obj.getpeercert(True) if ssl_obj is not None else None
    return fingerprint(der) if der else None


class KeyVault:
    """Client cert and sealed key on disk, plus the pinned TV fingerprint."""

    def __init__(self, state_dir: Path, protector: Protector):
        self.dir, self.protector = Path(state_dir), protector

    @property
    def cert_path(self) -> Path:
        return self.dir / CERT_FILE

    def has_identity(self) -> bool:
        return self.cert_path.is_file() and (self.dir / KEY_FILE).is_file()

    def create_identity(self) -> None:
        cert_pem, key_pem = generate_selfsigned_cert(CLIENT_NAME)
        sealed = self.protector.protect(key_pem)  # seal before anything touches the disk
        self.dir.mkdir(parents=True, exist_ok=True)
        _write_atomic(self.dir / KEY_FILE, sealed)
        _write_atomic(self.cert_path, cert_pem)
        self.clear_pin()

    def ssl_context(self) -> ssl.SSLContext:
        """Build the client TLS context without a plaintext key on disk.

        Python's ssl only loads keys from files, so the unsealed key is re-encrypted with a one-time random password
        into a temporary file in the config folder, loaded, and deleted at once.
        """
        try:
            key_pem = self.protector.unprotect((self.dir / KEY_FILE).read_bytes())
        except FileNotFoundError:
            raise NotPaired("googletv") from None
        except SecretStoreError as exc:
            log.warning("Google TV client key could not be unsealed: %s", type(exc).__name__)
            raise NotPaired("googletv") from None
        password = secrets.token_bytes(32)
        key = serialization.load_pem_private_key(key_pem, password=None)
        enc = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.BestAvailableEncryption(password),
        )
        del key_pem, key
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        # The TV's certificate is self-signed; trust comes from the pin checked after every handshake.
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        fd, tmp = tempfile.mkstemp(dir=self.dir, prefix=".atv-key-", suffix=".tmp")
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(enc)
            ctx.load_cert_chain(str(self.cert_path), tmp, password=password)
        finally:
            os.unlink(tmp)
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
    """AndroidTVRemote whose TLS context comes from the vault, so the library never reads a key file."""

    def __init__(self, vault: KeyVault, host: str):
        super().__init__(CLIENT_NAME, str(vault.cert_path), str(vault.dir / KEY_FILE), host)
        self._vault = vault

    async def async_generate_cert_if_missing(self) -> bool:
        if self._vault.has_identity():
            return False
        await self._loop.run_in_executor(None, self._vault.create_identity)
        self._ssl_context = None
        return True

    async def _create_ssl_context(self) -> ssl.SSLContext:
        if self._ssl_context is None:
            self._ssl_context = await self._loop.run_in_executor(None, self._vault.ssl_context)
        return self._ssl_context

    def api_transport(self) -> Any:
        return self._transport

    def pairing_transport(self) -> Any:
        proto = self._pairing_message_protocol
        return proto.transport if proto is not None else None


RemoteFactory = Callable[[KeyVault, str], Any]


class GoogleTvRemote:
    """GoogleTvBackend over androidtvremote2 (see devices.GoogleTvBackend)."""

    def __init__(self, host: str, vault: KeyVault, remote_factory: RemoteFactory = SealedKeyRemote):
        self.host, self._vault, self._factory = host, vault, remote_factory
        self._remote: Any = None
        self._connected = False
        self._pending_pin: str | None = None
        self._lock = asyncio.Lock()

    def _unreachable(self, reason: str) -> DeviceUnreachable:
        return DeviceUnreachable("googletv", self.host, reason)

    def _new_remote(self) -> Any:
        self._drop()
        self._remote = self._factory(self._vault, self.host)
        return self._remote

    def _drop(self) -> None:
        if self._remote is not None:
            self._remote.disconnect()
        self._remote, self._connected = None, False

    async def _ensure_connected(self) -> Any:
        async with self._lock:
            if self._connected and self._remote is not None:
                return self._remote
            pinned = self._vault.pinned()
            if pinned is None or not self._vault.has_identity():
                raise NotPaired("googletv")
            remote = self._new_remote()
            try:
                await asyncio.wait_for(remote.async_connect(), CONNECT_TIMEOUT)
            except InvalidAuth:
                self._drop()
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
            if peer_fingerprint(remote.api_transport()) != pinned:
                self._drop()
                log.warning("Google TV certificate does not match the pinned fingerprint; refusing.")
                raise CertificateChanged("googletv")
            self._connected = True
            return remote

    def _send(self, fn: Callable[[], None]) -> None:
        try:
            fn()
        except ConnectionClosed:
            self._drop()
            raise self._unreachable(REASON_CLOSED) from None

    async def send_key(self, key: Key) -> None:
        code = KEYCODES[key]  # KeyError here would mean the enum and the map drifted; tests guard it
        remote = await self._ensure_connected()
        self._send(lambda: remote.send_key_command(code))

    async def open_link(self, uri: str) -> None:
        if not uri.startswith("https://") or any(c.isspace() for c in uri):
            raise ValueError("only https app links built by tv-remote are sent")
        remote = await self._ensure_connected()
        self._send(lambda: remote.send_launch_app_command(uri))

    async def launch_package(self, package: str) -> None:
        if not _PACKAGE_RE.match(package):
            raise ValueError("not an Android package name")
        remote = await self._ensure_connected()
        self._send(lambda: remote.send_launch_app_command(package))

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
            remote = self._new_remote()
            try:
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
            self._vault.pin(pending)
            self._pending_pin = None
            self._drop()
            return "Google TV paired."
