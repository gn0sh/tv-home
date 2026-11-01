"""LG webOS backend over SSAP (task C3, finding #5).

What is used from aiowebostv 0.10.0: the SSAP endpoint names, the wss port and the registration payload shape. Its
WebOsClient is not used, because its connect path cannot meet #5: it tries ws:// on 3000 first, turns TLS
verification off with no pin, and on connect subscribes to channels, apps, settings and the pointer-input socket,
which need far more than our 8 permissions. This module is a small SSAP client on aiohttp instead:
  - wss://<host>:3001 only. There is no ws:// code path.
  - The TV's self-signed certificate is pinned at pairing and checked inside the TLS handshake (tlspin), before the
    client key is sent. LG pairing has no code, so the first pairing trusts the certificate it sees (TOFU); tv_pair
    is never auto-allowed, so Brian starts it himself while at the TV.
  - Registers with the trimmed 8-permission manifest, and refuses locally any SSAP URI not on ALLOWED_URIS.
  - The client key is sealed with DPAPI (secretstore) and never written unencrypted.
  - Each request waits for its reply (matched by id, which the TV echoes on requests). The TV sends nothing on an
    idle link (no pings), so "quiet for SILENCE_LIMIT" just means no command in the last 7 s: any command after
    such a pause opens a fresh connection (TLS + register, well under a second on the LAN) instead of trusting a
    link that may have gone half-open (Wi-Fi blip, TV standby). Commands in quick succession share one link.
Files in the config folder: lg-client-key.sealed, lg-tv-cert.sha256.
"""

from __future__ import annotations

import asyncio
import copy
import itertools
import json
import logging
import re
import socket
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any, Literal

import aiohttp
from aiowebostv import endpoints as ep
from aiowebostv.handshake import REGISTRATION_PAYLOAD
from aiowebostv.webos_client import WSS_PORT

from .devices import CertificateChanged, DeviceRefused, DeviceUnreachable, LgInput, LgStatus, NotPaired
from .googletv import _write_atomic
from .secretstore import Protector, SecretStoreError
from .tlspin import pinned_client_context

log = logging.getLogger("tv_remote.lg")

assert WSS_PORT == 3001  # noqa: S101  (guards an upstream change)

# Finding #5: the complete permission manifest. Nothing else is requested.
PERMISSIONS = (
    "LAUNCH",
    "CONTROL_POWER",
    "CONTROL_AUDIO",
    "CONTROL_INPUT_TV",
    "READ_INPUT_DEVICE_LIST",
    "READ_RUNNING_APPS",
    "READ_POWER_STATE",
    "READ_APP_STATUS",
)
# Every SSAP URI this client will ever send after registration. Anything else is refused before it is sent.
ALLOWED_URIS = frozenset({
    ep.POWER_OFF,
    ep.GET_POWER_STATE,
    ep.GET_VOLUME,
    ep.SET_VOLUME,
    ep.VOLUME_UP,
    ep.VOLUME_DOWN,
    ep.SET_MUTE,
    ep.SET_INPUT,
    ep.GET_CURRENT_APP_INFO,
})

KEY_FILE = "lg-client-key.sealed"
PIN_FILE = "lg-tv-cert.sha256"
CONNECT_TIMEOUT = 8.0
REQUEST_TIMEOUT = 6.0
PAIR_TIMEOUT = 60.0  # time Brian has to accept the prompt on the TV
SILENCE_LIMIT = 7.0  # seconds without any reply from the TV; see the module docstring
MAX_MSG = 1 << 20
WS_CLOSE_TIMEOUT = 1.0

REASON_NO_ANSWER = "no answer"
REASON_CLOSED = "connection closed"
REASON_TIMEOUT = "timed out"
REASON_NO_REPLY = "no reply"

REFUSED_PERMISSION = ("The LG TV refused this: it is outside the 8 permissions tv-remote registered with "
                      "(launch, power, audio, input, and reading inputs, apps and power state).")
REFUSED_GENERIC = "The LG TV refused the request."
REFUSED_LOCAL = "Refused: tv-remote does not send that request to the LG TV."

_HELLO = frozenset({"hello"})
_REPLY = frozenset({"response", "error"})
_REGISTER = frozenset({"response", "registered", "error"})
_HDMI_APP = re.compile(r"^com\.webos\.app\.hdmi([1-4])$")
_ON_STATES = {"Active", "Screen Off", "Screen Saver"}


# --- Wake-on-LAN ----------------------------------------------------------------------------------------------------

WOL_PORT = 9
WOL_ADDRESS = "255.255.255.255"  # limited broadcast: never forwarded by a router, so it stays on the LAN


def magic_packet(mac: str) -> bytes:
    """6 x 0xFF followed by the MAC 16 times (102 bytes). `mac` is already validated as AA:BB:CC:DD:EE:FF."""
    raw = bytes.fromhex(mac.replace(":", "").replace("-", ""))
    if len(raw) != 6:
        raise ValueError("MAC must be 6 bytes")
    return b"\xff" * 6 + raw * 16


def send_wol(packet: bytes) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.sendto(packet, (WOL_ADDRESS, WOL_PORT))


# --- Storage --------------------------------------------------------------------------------------------------------


class LgVault:
    def __init__(self, state_dir: Path, protector: Protector):
        self.dir, self.protector = Path(state_dir), protector

    def client_key(self) -> str | None:
        try:
            blob = (self.dir / KEY_FILE).read_bytes()
        except FileNotFoundError:
            return None
        except OSError as exc:
            log.warning("LG client key could not be read: %s", type(exc).__name__)
            return None
        try:
            return self.protector.unprotect(blob).decode("utf-8")
        except (SecretStoreError, UnicodeDecodeError) as exc:
            log.warning("LG client key could not be unsealed: %s", type(exc).__name__)
            return None

    def save(self, client_key: str, fp: str) -> None:
        sealed = self.protector.protect(client_key.encode("utf-8"))  # seal first: fails before writing on error
        self.dir.mkdir(parents=True, exist_ok=True)
        _write_atomic(self.dir / KEY_FILE, sealed)
        _write_atomic(self.dir / PIN_FILE, fp.encode())

    def pinned(self) -> str | None:
        try:
            value = (self.dir / PIN_FILE).read_text().strip().lower()
        except OSError:
            return None
        return value if re.fullmatch(r"[0-9a-f]{64}", value) else None


def registration_message(client_key: str | None) -> dict[str, Any]:
    payload = copy.deepcopy(REGISTRATION_PAYLOAD)
    payload["manifest"]["permissions"] = list(PERMISSIONS)
    payload["forcePairing"] = False
    payload["pairingType"] = "PROMPT"
    if client_key:
        payload["client-key"] = client_key
    return {"type": "register", "id": "register_0", "payload": payload}


# --- Client ---------------------------------------------------------------------------------------------------------


class _Link:
    def __init__(self, session: aiohttp.ClientSession, ws: aiohttp.ClientWebSocketResponse, now: float):
        self.session, self.ws, self.last_rx = session, ws, now

    async def close(self) -> None:
        try:
            await self.ws.close()
        finally:
            await self.session.close()


class LgRemote:
    """LgBackend over SSAP (see devices.LgBackend)."""

    def __init__(self, host: str, mac: str, vault: LgVault, *, port: int = WSS_PORT,
                 wol_sender: Callable[[bytes], None] = send_wol, clock: Callable[[], float] = time.monotonic):
        self.host, self.mac, self._vault = host, mac, vault
        self._port, self._wol, self._clock = port, wol_sender, clock
        self._link: _Link | None = None
        self._ids = itertools.count(1)
        self._lock = asyncio.Lock()

    @property
    def url(self) -> str:
        return f"wss://{self.host}:{self._port}"

    def _unreachable(self, reason: str, sent: bool = False) -> DeviceUnreachable:
        return DeviceUnreachable("lg", self.host, reason, sent)

    async def _drop(self) -> None:
        link, self._link = self._link, None
        if link is not None:
            try:
                await link.close()
            except Exception:  # noqa: BLE001  closing a dead link must not mask the real error
                pass

    # -- connection --------------------------------------------------------------------------------------------------

    async def _open(self, pin: str | None):
        """TLS (pinned) + websocket. Returns (session, ws, ctx)."""
        ctx = pinned_client_context(pin)
        session = aiohttp.ClientSession()
        try:
            ws = await asyncio.wait_for(
                session.ws_connect(self.url, ssl=ctx, heartbeat=None, autoping=True, max_msg_size=MAX_MSG,
                                   # a dead TV must not hold a close (and the next command) for aiohttp's 10 s
                                   timeout=aiohttp.ClientWSTimeout(ws_close=WS_CLOSE_TIMEOUT)),
                CONNECT_TIMEOUT,
            )
        except BaseException as exc:
            await session.close()
            if ctx.tv_pin_mismatch:
                log.warning("LG TV certificate does not match the pinned fingerprint; refusing.")
                raise CertificateChanged("lg") from None
            if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
                raise self._unreachable(REASON_TIMEOUT) from None
            if isinstance(exc, (aiohttp.ClientError, OSError)):
                raise self._unreachable(REASON_NO_ANSWER) from None
            raise
        return session, ws, ctx

    async def _recv(self, ws, want_id: str | None, timeout: float, sent: bool,
                    untagged_types: frozenset[str] = frozenset()) -> dict[str, Any]:
        """Next message whose id is want_id. During the preamble and registration the TV may reply without an id
        (aiowebostv reads those replies by type), so a message with no id whose type is in untagged_types also
        matches."""
        deadline = self._clock() + timeout
        while True:
            remaining = deadline - self._clock()
            if remaining <= 0:
                raise self._unreachable(REASON_NO_REPLY if sent else REASON_TIMEOUT, sent)
            try:
                msg = await asyncio.wait_for(ws.receive(), remaining)
            except (asyncio.TimeoutError, TimeoutError):
                raise self._unreachable(REASON_NO_REPLY if sent else REASON_TIMEOUT, sent) from None
            if msg.type != aiohttp.WSMsgType.TEXT:
                raise self._unreachable(REASON_CLOSED, sent)
            if self._link is not None and self._link.ws is ws:
                self._link.last_rx = self._clock()
            try:
                data = json.loads(msg.data)
            except ValueError:
                continue
            if not isinstance(data, dict):
                continue
            if want_id is None or str(data.get("id")) == want_id:
                return data
            if data.get("id") is None and data.get("type") in untagged_types:
                return data

    async def _preamble(self, ws) -> None:
        await ws.send_json({"id": "hello", "type": "hello", "payload": {}})
        await self._recv(ws, "hello", REQUEST_TIMEOUT, sent=False, untagged_types=_HELLO)
        # Newer webOS wants system info before registration (as aiowebostv does). Allowed without permissions.
        await ws.send_json({"id": "get_sys_info", "type": "request", "uri": f"ssap://{ep.GET_SYSTEM_INFO}",
                            "payload": {}})
        await self._recv(ws, "get_sys_info", REQUEST_TIMEOUT, sent=False, untagged_types=_REPLY)

    async def _ensure(self) -> _Link:
        if self._link is not None and not self._link.ws.closed and self._clock() - self._link.last_rx <= SILENCE_LIMIT:
            return self._link
        await self._drop()
        pin, key = await asyncio.to_thread(lambda: (self._vault.pinned(), self._vault.client_key()))
        if pin is None or key is None:
            raise NotPaired("lg")
        session, ws, _ctx = await self._open(pin)
        try:
            await self._preamble(ws)
            await ws.send_json(registration_message(key))
            reply = await self._recv(ws, "register_0", REQUEST_TIMEOUT, sent=False, untagged_types=_REGISTER)
            if reply.get("type") != "registered":
                # A stale key makes the TV show a pairing prompt. Close at once; only tv_pair may pair.
                raise NotPaired("lg")
        except BaseException:
            await ws.close()
            await session.close()
            raise
        self._link = _Link(session, ws, self._clock())
        return self._link

    async def _request(self, uri: str, payload: dict[str, Any] | None = None, *,
                       reply_optional: bool = False) -> dict[str, Any]:
        if uri not in ALLOWED_URIS:
            raise DeviceRefused(REFUSED_LOCAL)
        async with self._lock:
            link = await self._ensure()
            msg_id = str(next(self._ids))
            try:
                await link.ws.send_json({"id": msg_id, "type": "request", "uri": f"ssap://{uri}",
                                         "payload": payload or {}})
            except (aiohttp.ClientError, ConnectionError, RuntimeError):
                await self._drop()
                raise self._unreachable(REASON_CLOSED) from None
            try:
                reply = await self._recv(link.ws, msg_id, REQUEST_TIMEOUT, sent=True)
            except DeviceUnreachable:
                await self._drop()
                if reply_optional:
                    return {}
                raise
        return _parse(reply)

    # -- LgBackend ---------------------------------------------------------------------------------------------------

    async def status(self) -> LgStatus:
        power = await self._request(ep.GET_POWER_STATE)
        state = power.get("state")
        on = state in _ON_STATES
        if not on:
            return LgStatus("off" if state else "unknown", None, None, None, None)
        vol = await self._request(ep.GET_VOLUME)
        app = await self._request(ep.GET_CURRENT_APP_INFO)
        volume, muted = _volume_of(vol)
        app_id = app.get("appId") if isinstance(app.get("appId"), str) else None
        m = _HDMI_APP.match(app_id or "")
        return LgStatus("on", volume, muted, f"HDMI_{m.group(1)}" if m else None, app_id or None)

    async def power_on(self) -> None:
        await asyncio.get_running_loop().run_in_executor(None, self._wol, magic_packet(self.mac))

    async def power_off(self) -> None:
        # The TV may switch off before it replies; a missing reply after sending still counts as sent.
        await self._request(ep.POWER_OFF, reply_optional=True)
        await self._drop()

    async def set_volume(self, level: int) -> int:
        if not isinstance(level, int) or isinstance(level, bool) or not 0 <= level <= 100:
            raise ValueError("volume level out of range")
        await self._request(ep.SET_VOLUME, {"volume": level})
        return level

    async def volume_step(self, direction: Literal["up", "down"]) -> int | None:
        """Step once; returns the new level, or None if the step worked but the TV did not report the level."""
        await self._request(ep.VOLUME_UP if direction == "up" else ep.VOLUME_DOWN)
        try:
            volume, _ = _volume_of(await self._request(ep.GET_VOLUME))
        except (DeviceRefused, DeviceUnreachable):
            return None
        return volume

    async def set_mute(self, muted: bool) -> None:
        await self._request(ep.SET_MUTE, {"mute": bool(muted)})

    async def set_input(self, source: LgInput) -> None:
        if source not in ("HDMI_1", "HDMI_2", "HDMI_3", "HDMI_4"):
            raise ValueError("unknown input")
        await self._request(ep.SET_INPUT, {"inputId": source})

    async def pair(self) -> str:
        """One-time registration: the TV shows an accept prompt; this waits up to PAIR_TIMEOUT for Brian."""
        async with self._lock:
            await self._drop()
            session, ws, ctx = await self._open(None)
            try:
                await self._preamble(ws)
                await ws.send_json(registration_message(None))
                reply = await self._recv(ws, "register_0", REQUEST_TIMEOUT, sent=False, untagged_types=_REGISTER)
                if reply.get("type") == "response" and (reply.get("payload") or {}).get("pairingType") == "PROMPT":
                    try:
                        reply = await self._recv(ws, "register_0", PAIR_TIMEOUT, sent=False, untagged_types=_REGISTER)
                    except DeviceUnreachable as exc:
                        if exc.reason == REASON_TIMEOUT:
                            return "Nobody accepted the prompt on the LG TV in time. Call tv_pair again at the TV."
                        raise
                if reply.get("type") == "error":
                    return "The LG TV pairing prompt was declined. Call tv_pair again to retry."
                key = (reply.get("payload") or {}).get("client-key") if reply.get("type") == "registered" else None
                fp = ctx.tv_seen_fingerprint
                if not isinstance(key, str) or not key or fp is None:
                    raise DeviceRefused("The LG TV did not complete pairing.")
                try:
                    await asyncio.to_thread(self._vault.save, key, fp)
                except SecretStoreError:
                    raise NotImplementedError("Secure key storage is not available here; pairing runs on Windows.") from None
                return "LG TV paired."
            finally:
                await ws.close()
                await session.close()


def _parse(reply: dict[str, Any]) -> dict[str, Any]:
    if reply.get("type") == "error":
        err = str(reply.get("error", ""))
        if err.startswith("401"):
            raise DeviceRefused(REFUSED_PERMISSION)
        raise DeviceRefused(REFUSED_GENERIC)
    payload = reply.get("payload")
    if not isinstance(payload, dict) or payload.get("returnValue") is False:
        raise DeviceRefused(REFUSED_GENERIC)
    return payload


def _volume_of(payload: dict[str, Any]) -> tuple[int | None, bool | None]:
    src = payload.get("volumeStatus") if isinstance(payload.get("volumeStatus"), dict) else payload
    vol, muted = src.get("volume"), src.get("muteStatus", src.get("muted"))
    vol = vol if isinstance(vol, int) and not isinstance(vol, bool) else None
    return vol, muted if isinstance(muted, bool) else None
