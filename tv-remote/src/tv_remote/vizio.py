"""Vizio SmartCast backend for the PC remote's volume (ADR 0007), modelled on the LG backend (lg.py, ADR 0003).

The Vizio acknowledges HDMI-CEC volume from the Google TV but ignores it, so volume goes to the TV's local SmartCast
API instead: HTTPS on port 7345 (current firmware) or 9000 (older), JSON bodies, an ``AUTH`` header after pairing.
Protocol facts (endpoint paths, key codesets, pairing fields) follow pyvizio 0.1.64 (MIT, Vlad Korniev), read as
documentation; no code is copied and it is not a dependency (it would bring requests, click, zeroconf and more).

  - The TV's self-signed certificate is pinned (SHA-256) when pairing starts and checked inside every TLS handshake
    (tlspin), before the auth token is sent. Pairing trusts the first certificate it sees (TOFU), like the LG.
    A different certificate later is refused (CertificateChanged) and nothing is sent.
  - Pairing: PUT /pairing/start (the TV shows a 4-digit PIN), PUT /pairing/pair with the PIN, which returns the auth
    token. The token is sealed with DPAPI (secretstore) into vizio-auth.sealed and never written, printed or logged
    in the clear. The port and certificate pin go in vizio-tv-pin.json (not secret).
  - Only the paths in ALLOWED_PATHS are ever requested, and only the volume codeset (5) is ever sent as a key.
  - Volume: key presses VOL_DOWN (5,0), VOL_UP (5,1), MUTE_OFF (5,2), MUTE_ON (5,3); the level is read from
    /menu_native/dynamic/tv_settings/audio/volume and set (for the cap) with a MODIFY on the same path.
The cap, lock and read-before-step come from lgpipe.handle, which drives this class through the LgBackend shape.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from pathlib import Path
from typing import Any

import aiohttp

from .devices import CertificateChanged, DeviceRefused, DeviceUnreachable, LgStatus, NotPaired
from .googletv import _write_atomic
from .secretstore import Protector, SecretStoreError
from .tlspin import pinned_client_context

log = logging.getLogger("tv_remote.vizio")

PORTS = (7345, 9000)
TOKEN_FILE = "vizio-auth.sealed"
PIN_FILE = "vizio-tv-pin.json"
DEVICE_ID = "gnosh-tv-remote-pc"
DEVICE_NAME = "TV Remote (PC)"

PAIR_START = "/pairing/start"
PAIR_FINISH = "/pairing/pair"
PAIR_CANCEL = "/pairing/cancel"
KEY_COMMAND = "/key_command/"
VOLUME_SETTING = "/menu_native/dynamic/tv_settings/audio/volume"
MUTE_SETTING = "/menu_native/dynamic/tv_settings/audio/mute"
ALLOWED_PATHS = frozenset({PAIR_START, PAIR_FINISH, PAIR_CANCEL, KEY_COMMAND, VOLUME_SETTING, MUTE_SETTING})

CODESET_VOLUME = 5
KEYS = {"down": 0, "up": 1, "mute_off": 2, "mute_on": 3, "mute_toggle": 4}

CONNECT_TIMEOUT = 4.0
REQUEST_TIMEOUT = 5.0
MAX_BODY = 256 * 1024
PIN_RE = re.compile(r"\A\d{4}\Z")
_NOT_PAIRED = {"requires_pairing", "blocked", "auth_failed", "unauthorized"}


def _get(obj: Any, key: str, default: Any = None) -> Any:
    """Case-insensitive dict lookup (the TV answers in upper case; older firmware varies)."""
    if not isinstance(obj, dict):
        return default
    for k, v in obj.items():
        if isinstance(k, str) and k.lower() == key.lower():
            return v
    return default


# --- Storage --------------------------------------------------------------------------------------------------------


class VizioVault:
    def __init__(self, state_dir: Path, protector: Protector):
        self.dir, self.protector = Path(state_dir), protector

    def token(self) -> str | None:
        try:
            blob = (self.dir / TOKEN_FILE).read_bytes()
        except FileNotFoundError:
            return None
        except OSError as exc:
            log.warning("Vizio auth token could not be read: %s", type(exc).__name__)
            return None
        try:
            return self.protector.unprotect(blob).decode("utf-8")
        except (SecretStoreError, UnicodeDecodeError) as exc:
            log.warning("Vizio auth token could not be unsealed: %s", type(exc).__name__)
            return None

    def pinned(self) -> tuple[int, str] | None:
        """(port, sha256) recorded at pairing, or None."""
        try:
            data = json.loads((self.dir / PIN_FILE).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        port, fp = _get(data, "port"), _get(data, "sha256")
        if not isinstance(port, int) or not 0 < port < 65536 or not isinstance(fp, str) or not re.fullmatch(r"[0-9a-f]{64}", fp):
            return None
        return port, fp

    def paired(self) -> bool:
        return self.pinned() is not None and (self.dir / TOKEN_FILE).exists()

    def save(self, token: str, port: int, fp: str) -> None:
        sealed = self.protector.protect(token.encode("utf-8"))  # seal first: fails before writing on error
        self.dir.mkdir(parents=True, exist_ok=True)
        _write_atomic(self.dir / TOKEN_FILE, sealed)
        _write_atomic(self.dir / PIN_FILE, json.dumps({"port": port, "sha256": fp}).encode())

    def forget(self) -> list[str]:
        removed = []
        for name in (TOKEN_FILE, PIN_FILE):
            try:
                (self.dir / name).unlink()
                removed.append(name)
            except FileNotFoundError:
                pass
        return removed


# --- Client ---------------------------------------------------------------------------------------------------------


class _Pairing:
    def __init__(self, port: int, fp: str, challenge: int, req_token: int):
        self.port, self.fp, self.challenge, self.req_token = port, fp, challenge, req_token


class VizioRemote:
    """Volume on a paired Vizio, in the LgBackend shape (status, volume_step, set_volume, set_mute) so lgpipe.handle
    applies the same cap and lock. One HTTPS session is kept while in use and dropped on any error."""

    def __init__(self, host: str, vault: VizioVault, *, ports: tuple[int, ...] = PORTS):
        self.host, self._vault, self._ports = host, vault, ports
        self._session: aiohttp.ClientSession | None = None
        self._session_key: tuple[int, str] | None = None
        self._pairing: _Pairing | None = None

    # -- transport ----------------------------------------------------------------------------------------------------

    async def close(self) -> None:
        session, self._session, self._session_key = self._session, None, None
        if session is not None:
            try:
                await session.close()
            except Exception:  # noqa: BLE001
                pass

    def _unreachable(self, reason: str = "no answer", sent: bool = False) -> DeviceUnreachable:
        return DeviceUnreachable("vizio", self.host, reason, sent)

    async def _request(self, method: str, path: str, body: dict[str, Any] | None, *, port: int, pin: str | None,
                       token: str | None, ctx_out: list | None = None) -> dict[str, Any]:
        if path not in ALLOWED_PATHS:
            raise DeviceRefused("Refused: tv-remote does not send that request to the Vizio.")
        key = (port, pin or "")
        if self._session is None or self._session_key != key or pin is None:
            await self.close()
            ctx = pinned_client_context(pin)
            self._session = aiohttp.ClientSession(
                connector=aiohttp.TCPConnector(ssl=ctx, limit=2),
                timeout=aiohttp.ClientTimeout(total=REQUEST_TIMEOUT, sock_connect=CONNECT_TIMEOUT),
                trust_env=False,  # never through a proxy: this is a LAN device
            )
            self._session_key = key
            self._ctx = ctx
        ctx = self._ctx
        if ctx_out is not None:
            ctx_out.append(ctx)
        headers = {"Content-Type": "application/json"}
        if token:
            headers["AUTH"] = token
        url = f"https://{self.host}:{port}{path}"
        sent = False
        try:
            async with self._session.request(method, url, headers=headers,
                                             data=None if body is None else json.dumps(body)) as resp:
                sent = True
                raw = await resp.content.read(MAX_BODY + 1)
                status = resp.status
        except BaseException as exc:
            await self.close()
            if ctx.tv_pin_mismatch:
                log.warning("Vizio TV certificate does not match the pinned fingerprint; refusing.")
                raise CertificateChanged("vizio") from None
            if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
                raise self._unreachable("timed out", sent) from None
            if isinstance(exc, (aiohttp.ClientError, OSError)):
                raise self._unreachable("no answer", sent) from None
            raise
        if len(raw) > MAX_BODY:
            raise DeviceRefused("The Vizio sent too much data.")
        if status in (401, 403):
            raise NotPaired("vizio")
        if status != 200:
            raise DeviceRefused("The Vizio refused the request.")
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise DeviceRefused("The Vizio sent an unreadable answer.") from None
        result = str(_get(_get(data, "status", {}), "result", "")).lower()
        if result in _NOT_PAIRED:
            raise NotPaired("vizio")
        if result != "success":
            raise DeviceRefused(f"The Vizio answered {result[:30] or 'without a result'}.")
        return data

    def _creds(self) -> tuple[int, str, str]:
        pinned = self._vault.pinned()
        token = self._vault.token()
        if pinned is None or not token:
            raise NotPaired("vizio")
        return pinned[0], pinned[1], token

    async def _authed(self, method: str, path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        port, fp, token = self._creds()
        return await self._request(method, path, body, port=port, pin=fp, token=token)

    # -- volume (LgBackend shape) -------------------------------------------------------------------------------------

    async def _key(self, name: str) -> None:
        await self._authed("PUT", KEY_COMMAND,
                           {"KEYLIST": [{"CODESET": CODESET_VOLUME, "CODE": KEYS[name], "ACTION": "KEYPRESS"}]})

    async def _volume_item(self) -> dict[str, Any] | None:
        data = await self._authed("GET", VOLUME_SETTING)
        items = _get(data, "items")
        return items[0] if isinstance(items, list) and items and isinstance(items[0], dict) else None

    @staticmethod
    def _level(item: dict[str, Any] | None) -> int | None:
        value = _get(item, "value")
        try:
            level = int(value)
        except (TypeError, ValueError):
            return None
        return level if 0 <= level <= 100 else None

    async def status(self) -> LgStatus:
        level = self._level(await self._volume_item())
        muted = None
        try:
            data = await self._authed("GET", MUTE_SETTING)
            items = _get(data, "items")
            value = _get(items[0], "value") if isinstance(items, list) and items else None
            if isinstance(value, str) and value.lower() in ("on", "off"):
                muted = value.lower() == "on"
        except DeviceRefused:
            pass  # some firmware has no mute item; the level still counts
        return LgStatus("on", level, muted, None, None)

    async def volume_step(self, direction: str) -> int | None:
        await self._key("up" if direction == "up" else "down")
        try:
            return self._level(await self._volume_item())
        except (DeviceRefused, DeviceUnreachable):
            return None  # the step was sent; the caller reads the level again before trusting it

    async def set_volume(self, level: int) -> int:
        level = max(0, min(100, int(level)))
        item = await self._volume_item()
        hashval = _get(item, "hashval")
        if not isinstance(hashval, int):
            raise DeviceRefused("The Vizio did not give its volume setting.")
        await self._authed("PUT", VOLUME_SETTING, {"REQUEST": "MODIFY", "HASHVAL": hashval, "VALUE": level})
        read = self._level(await self._volume_item())
        return level if read is None else read

    async def set_mute(self, muted: bool) -> None:
        await self._key("mute_on" if muted else "mute_off")

    # -- pairing ------------------------------------------------------------------------------------------------------

    async def start_pairing(self) -> str:
        """Asks the TV to show a PIN. Tries each port; the certificate seen is what will be pinned."""
        await self.close()
        body = {"DEVICE_ID": DEVICE_ID, "DEVICE_NAME": DEVICE_NAME}
        last: Exception | None = None
        for port in self._ports:
            seen: list = []
            try:
                data = await self._request("PUT", PAIR_START, body, port=port, pin=None, token=None, ctx_out=seen)
            except DeviceUnreachable as exc:
                last = exc
                continue
            fp = seen[0].tv_seen_fingerprint if seen else None
            item = _get(data, "item", {})
            try:
                challenge, req_token = int(_get(item, "challenge_type")), int(_get(item, "pairing_req_token"))
            except (TypeError, ValueError):
                raise DeviceRefused("The Vizio did not start pairing.") from None
            if not fp:
                raise DeviceRefused("The Vizio did not present a certificate.")
            self._pairing = _Pairing(port, fp, challenge, req_token)
            await self.close()  # the PIN is typed outside this event loop; finish opens a fresh, pinned session
            return f"The Vizio should now show a 4-digit PIN (port {port})."
        raise last or self._unreachable()

    async def finish_pairing(self, pin: str) -> str:
        p = self._pairing
        if p is None:
            raise DeviceRefused("Pairing was not started.")
        pin = pin.strip()
        if not PIN_RE.match(pin):
            return "The PIN is the 4 digits on the Vizio's screen. Nothing was sent."
        body = {"DEVICE_ID": DEVICE_ID, "CHALLENGE_TYPE": p.challenge, "PAIRING_REQ_TOKEN": p.req_token,
                "RESPONSE_VALUE": pin}
        try:  # pinned to the certificate seen at start: the PIN goes to the same TV
            data = await self._request("PUT", PAIR_FINISH, body, port=p.port, pin=p.fp, token=None)
        except DeviceRefused:
            return "The Vizio did not accept that PIN. Run tv-remote-vizio-pair again for a new PIN."
        token = _get(_get(data, "item", {}), "auth_token")
        if not isinstance(token, str) or not token:
            raise DeviceRefused("The Vizio did not complete pairing.")
        try:
            await asyncio.to_thread(self._vault.save, token, p.port, p.fp)
        except SecretStoreError:
            raise NotImplementedError("Secure token storage is not available here; pairing runs on Windows.") from None
        self._pairing = None
        await self.close()
        return "Vizio paired."

    async def cancel_pairing(self) -> None:
        p, self._pairing = self._pairing, None
        if p is None:
            return
        try:
            await self._request("PUT", PAIR_CANCEL, {"DEVICE_ID": DEVICE_ID, "DEVICE_NAME": DEVICE_NAME},
                                port=p.port, pin=p.fp, token=None)
        except Exception:  # noqa: BLE001  best effort; the TV times the PIN out anyway
            pass
        await self.close()
