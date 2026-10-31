"""Config loader for %APPDATA%\\tv-remote\\config.toml (architecture section 8).

The config holds only hosts and the LG MAC address. Pairing material (client keys, pinned fingerprints) lives
next to it in the same folder and is handled by the device backends (C3, C4); it is never read here.
"""

from __future__ import annotations

import ipaddress
import os
import re
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path

CONFIG_FILE = "config.toml"
# Tests and non-Windows development only. On Windows the folder is always %APPDATA%\tv-remote.
DIR_OVERRIDE_ENV = "TV_REMOTE_CONFIG_DIR"

_PRIVATE_NETS = (
    ipaddress.IPv4Network("10.0.0.0/8"),
    ipaddress.IPv4Network("172.16.0.0/12"),
    ipaddress.IPv4Network("192.168.0.0/16"),
)
_MAC_RE = re.compile(r"^[0-9A-Fa-f]{2}([:-])(?:[0-9A-Fa-f]{2}\1){4}[0-9A-Fa-f]{2}$")

_ALLOWED_KEYS = {"lg": {"host", "mac"}, "googletv": {"host"}}


class ConfigError(Exception):
    """The config file is missing or invalid. The message says what to fix and never echoes secrets."""


@dataclass(frozen=True)
class LgConfig:
    host: str
    mac: str  # normalised to AA:BB:CC:DD:EE:FF


@dataclass(frozen=True)
class GoogleTvConfig:
    host: str


@dataclass(frozen=True)
class Config:
    lg: LgConfig | None
    googletv: GoogleTvConfig | None


def config_dir() -> Path:
    if sys.platform == "win32":
        appdata = os.environ.get("APPDATA")
        if not appdata:
            raise ConfigError("APPDATA is not set, so the tv-remote config folder cannot be found.")
        return Path(appdata) / "tv-remote"
    override = os.environ.get(DIR_OVERRIDE_ENV)
    if not override:
        raise ConfigError(f"On non-Windows systems set {DIR_OVERRIDE_ENV} to the tv-remote config folder.")
    return Path(override)


def check_private_host(host: object, field: str) -> str:
    """Accept only a literal IPv4 address inside RFC 1918. Host names are refused (DNS could point anywhere)."""
    if not isinstance(host, str):
        raise ConfigError(f"{field} must be a string IPv4 address.")
    try:
        addr = ipaddress.IPv4Address(host.strip())
    except ipaddress.AddressValueError:
        raise ConfigError(f"{field} must be a literal IPv4 address on the home network, got {host!r}.") from None
    if not any(addr in net for net in _PRIVATE_NETS):
        raise ConfigError(
            f"{field} {addr} is not a private (RFC 1918) address; only 10/8, 172.16/12 and 192.168/16 are allowed."
        )
    return str(addr)


def check_mac(mac: object, field: str = "lg.mac") -> str:
    """Validate a unicast MAC for Wake-on-LAN and normalise it to AA:BB:CC:DD:EE:FF."""
    if not isinstance(mac, str) or not _MAC_RE.match(mac.strip()):
        raise ConfigError(f"{field} must look like AA:BB:CC:DD:EE:FF, got {mac!r}.")
    octets = re.split(r"[:-]", mac.strip().upper())
    if octets == ["00"] * 6 or octets == ["FF"] * 6:
        raise ConfigError(f"{field} {mac} is not a device address.")
    if int(octets[0], 16) & 1:
        raise ConfigError(f"{field} {mac} is a multicast address, not the TV's address.")
    return ":".join(octets)


def parse_config(data: dict) -> Config:
    unknown = set(data) - set(_ALLOWED_KEYS)
    if unknown:
        raise ConfigError(f"Unknown section(s) in config: {', '.join(sorted(unknown))}.")
    for section, allowed in _ALLOWED_KEYS.items():
        if section in data:
            if not isinstance(data[section], dict):
                raise ConfigError(f"[{section}] must be a table.")
            extra = set(data[section]) - allowed
            if extra:
                raise ConfigError(f"Unknown key(s) in [{section}]: {', '.join(sorted(extra))}.")

    lg = None
    if "lg" in data:
        sec = data["lg"]
        if "host" not in sec or "mac" not in sec:
            raise ConfigError("[lg] needs both host and mac.")
        lg = LgConfig(host=check_private_host(sec["host"], "lg.host"), mac=check_mac(sec["mac"]))
    gtv = None
    if "googletv" in data:
        sec = data["googletv"]
        if "host" not in sec:
            raise ConfigError("[googletv] needs host.")
        gtv = GoogleTvConfig(host=check_private_host(sec["host"], "googletv.host"))
    if lg is None and gtv is None:
        raise ConfigError("Config has neither [lg] nor [googletv]; add at least one TV.")
    return Config(lg=lg, googletv=gtv)


def load_config(path: Path | None = None) -> Config:
    path = path or (config_dir() / CONFIG_FILE)
    try:
        raw = path.read_bytes()
    except FileNotFoundError:
        raise ConfigError(f"No config file at {path}. Create it with the TVs' addresses (see README).") from None
    try:
        data = tomllib.loads(raw.decode("utf-8"))
    except (tomllib.TOMLDecodeError, UnicodeDecodeError) as exc:
        raise ConfigError(f"{path} is not valid TOML: {exc}") from None
    return parse_config(data)
