"""Device backend interfaces, the unreachable error, fakes for tests, and stubs for the real wrappers.

C3 fills in the LG wrapper (aiowebostv + Wake-on-LAN) in lg.py, C4 the Google TV wrapper (androidtvremote2) in
googletv.py. Both implement the Protocols below so the tool layer does not change.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal, Protocol

Key = Literal["UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME", "PLAY_PAUSE", "REWIND", "FAST_FORWARD",
              "VOLUME_UP", "VOLUME_DOWN", "MUTE", "POWER"]
LgInput = Literal["HDMI_1", "HDMI_2", "HDMI_3", "HDMI_4"]
Device = Literal["lg", "googletv"]

DEVICE_NAMES = {"lg": "LG TV", "googletv": "Google TV"}
VOLUME_CAP = 60


class DeviceUnreachable(Exception):
    """A TV did not answer. `device` says which one so the tool can tell Brian (AC7)."""

    def __init__(self, device: Device, host: str, reason: str = "no answer", sent: bool = False):
        # sent: the request went out before the link failed, so the TV may have acted on it.
        self.device, self.host, self.reason, self.sent = device, host, reason, sent
        super().__init__(f"{DEVICE_NAMES[device]} at {host} is unreachable ({reason}).")


class DeviceRefused(Exception):
    """The TV answered but refused the request (for example a call outside the registered permissions).
    The message is a fixed string chosen by the backend; TV error text is not passed through."""


class NotPaired(Exception):
    def __init__(self, device: Device):
        self.device = device
        super().__init__(f"{DEVICE_NAMES[device]} is not paired yet. Run tv_pair with device='{device}'.")


class CertificateChanged(NotPaired):
    """The TV presented a TLS certificate other than the one pinned at pairing (finding #6). Nothing is sent."""

    def __init__(self, device: Device):
        self.device = device
        Exception.__init__(
            self,
            f"Refused: the {DEVICE_NAMES[device]}'s security certificate has changed since pairing, so nothing was "
            f"sent. If the TV was reset or replaced, pair again with tv_pair device='{device}'.",
        )


@dataclass
class LgStatus:
    power: str  # "on" | "off" | "unknown"
    volume: int | None
    muted: bool | None
    input: str | None
    foreground_app: str | None


class LgBackend(Protocol):
    host: str

    async def status(self) -> LgStatus: ...
    async def power_on(self) -> None: ...  # Wake-on-LAN to the configured MAC
    async def power_off(self) -> None: ...  # ssap://system/turnOff
    async def set_volume(self, level: int) -> int: ...  # returns the new level; caller guarantees 0..60
    async def volume_step(self, direction: Literal["up", "down"]) -> int | None: ...  # None: level not reported
    async def set_mute(self, muted: bool) -> None: ...
    async def set_input(self, source: LgInput) -> None: ...
    async def pair(self) -> str: ...  # shows the accept prompt on the TV; returns a status message


class GoogleTvBackend(Protocol):
    host: str

    async def current_app(self) -> str | None: ...
    async def send_key(self, key: Key) -> None: ...
    async def open_link(self, uri: str) -> None: ...
    async def launch_package(self, package: str) -> None: ...
    async def start_pairing(self) -> str: ...  # TV shows a code; returns a status message
    async def finish_pairing(self, code: str) -> str: ...


@dataclass
class Discovered:
    kind: Device
    host: str
    name: str


class Discovery(Protocol):
    async def discover(self) -> list[Discovered]: ...


# --- Stubs for the real wrappers (replaced in C3 / C4) -------------------------------------------------------------


class _Stub:
    _task = ""

    def __getattr__(self, name: str):
        async def _not_built(*_a, **_k):
            raise NotImplementedError(f"{type(self).__name__}.{name} is not built yet ({self._task}).")

        return _not_built


class LgStub:
    """Used when secure key storage (DPAPI) is unavailable, i.e. not on Windows. The real backend is lg.py."""

    def __init__(self, host: str, mac: str):
        self.host, self.mac = host, mac

    def __getattr__(self, name: str):
        async def _unavailable(*_a, **_k):
            raise NotImplementedError("LG TV control is unavailable here: its key storage (DPAPI) needs Windows.")

        return _unavailable


class GoogleTvStub:
    """Used when secure key storage (DPAPI) is unavailable, i.e. not on Windows. The real backend is googletv.py."""

    def __init__(self, host: str):
        self.host = host

    def __getattr__(self, name: str):
        async def _unavailable(*_a, **_k):
            raise NotImplementedError("Google TV control is unavailable here: its key storage (DPAPI) needs Windows.")

        return _unavailable


class DiscoveryStub(_Stub):
    _task = "task C3/C4"


# --- Fakes for tests ------------------------------------------------------------------------------------------------


@dataclass
class FakeLg:
    host: str = "192.168.68.61"
    reachable: bool = True
    power: str = "on"
    volume: int = 20
    muted: bool = False
    input: str = "HDMI_1"
    foreground_app: str = "com.webos.app.hdmi1"
    calls: list = field(default_factory=list)

    def _check(self, name: str, *args):
        if not self.reachable:
            raise DeviceUnreachable("lg", self.host)
        self.calls.append((name, *args))

    async def status(self) -> LgStatus:
        self._check("status")
        return LgStatus(self.power, self.volume, self.muted, self.input, self.foreground_app)

    async def power_on(self) -> None:
        self._check("power_on")
        self.power = "on"

    async def power_off(self) -> None:
        self._check("power_off")
        self.power = "off"

    async def set_volume(self, level: int) -> int:
        self._check("set_volume", level)
        self.volume = level
        return level

    async def volume_step(self, direction) -> int:
        self._check("volume_step", direction)
        # Deliberately no clamp: like the real TV, the fake goes past 60, so the tool layer must enforce the cap.
        self.volume = max(0, self.volume + (1 if direction == "up" else -1))
        return self.volume

    async def set_mute(self, muted: bool) -> None:
        self._check("set_mute", muted)
        self.muted = muted

    async def set_input(self, source) -> None:
        self._check("set_input", source)
        self.input = source

    async def pair(self) -> str:
        self._check("pair")
        return "Accept the prompt on the LG TV."


@dataclass
class FakeGoogleTv:
    host: str = "192.168.68.81"
    reachable: bool = True
    app: str | None = "com.google.android.apps.tv.launcherx"
    calls: list = field(default_factory=list)

    def _check(self, name: str, *args):
        if not self.reachable:
            raise DeviceUnreachable("googletv", self.host)
        self.calls.append((name, *args))

    async def current_app(self) -> str | None:
        self._check("current_app")
        return self.app

    async def send_key(self, key) -> None:
        self._check("send_key", key)

    async def open_link(self, uri: str) -> None:
        self._check("open_link", uri)

    async def launch_package(self, package: str) -> None:
        self._check("launch_package", package)

    async def start_pairing(self) -> str:
        self._check("start_pairing")
        return "A code is shown on the Google TV."

    async def finish_pairing(self, code: str) -> str:
        self._check("finish_pairing", code)
        return "Google TV paired."


@dataclass
class FakeDiscovery:
    found: list = field(default_factory=list)

    async def discover(self) -> list[Discovered]:
        return list(self.found)
