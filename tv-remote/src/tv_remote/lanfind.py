"""Find the paired LG again after the router gives it a new address (lg-address-moved).

The home router hands out addresses by DHCP, and the LG BX moved from 192.168.68.80 to 192.168.68.69 between
2026-11-02 and 2026-11-13, so every LG command went to an address where nothing listened. The config already holds
the LG's MAC address (for Wake-on-LAN), so when the configured address does not answer, this looks that MAC up in
Windows' neighbour (ARP) table and returns the address it now has.

This is safe to trust only because the caller (lg.LgRemote._ensure) uses it with a pinned certificate: the TLS
handshake to the new address is checked against the LG's pinned fingerprint before the client key is sent, so a
spoofed ARP entry gets a refused handshake, never the key. Pairing (no pin yet) never relocates.

Only an address in the configured host's own subnet (the same /24 unless the config says otherwise) that passes the
same private-address checks as the config is returned. Nothing is written back to config.toml.
"""

from __future__ import annotations

import ipaddress
import os
import re
import subprocess
import sys
from pathlib import Path

from .config import ConfigError, check_private_host

# "  192.168.68.69         58-fd-b1-c2-7b-ee     dynamic   "
_ARP_LINE = re.compile(r"^\s*(\d{1,3}(?:\.\d{1,3}){3})\s+([0-9a-fA-F]{2}(?:[-:][0-9a-fA-F]{2}){5})\s+(\w+)")
ARP_TIMEOUT = 3.0  # seconds; the table is local, so this only guards a hung process


def _norm_mac(mac: str) -> str:
    return re.sub(r"[-:]", ":", mac.strip()).upper()


def parse_arp(text: str) -> list[tuple[str, str]]:
    """(address, MAC as AA:BB:CC:DD:EE:FF) for each dynamic or static entry in `arp -a` output."""
    found = []
    for line in text.splitlines():
        m = _ARP_LINE.match(line)
        if m and m.group(3).lower() in ("dynamic", "static"):
            found.append((m.group(1), _norm_mac(m.group(2))))
    return found


def pick(entries: list[tuple[str, str]], mac: str, host: str, prefix: int = 24) -> str | None:
    """The one address in `entries` that has `mac`, is not `host`, and sits in host's subnet; else None.

    More than one address for the same MAC (a stale entry next to a fresh one) is ambiguous, so None."""
    want = _norm_mac(mac)
    net = ipaddress.IPv4Interface(f"{host}/{prefix}").network
    hits = set()
    for addr, entry_mac in entries:
        if entry_mac != want or addr == host:
            continue
        try:
            ip = ipaddress.IPv4Address(addr)
            if ip not in net:
                continue
            check_private_host(f"{addr}/{prefix}", "lg.host")
        except (ValueError, ConfigError):
            continue
        hits.add(addr)
    return hits.pop() if len(hits) == 1 else None


def _arp_exe() -> Path | None:
    root = os.environ.get("SystemRoot")
    if not root:
        return None
    exe = Path(root) / "System32" / "ARP.EXE"  # absolute: never whatever "arp" is first on PATH
    return exe if exe.is_file() else None


def read_arp_table() -> list[tuple[str, str]]:
    """Windows' neighbour table, or [] off Windows or on any failure."""
    if sys.platform != "win32":
        return []
    exe = _arp_exe()
    if exe is None:
        return []
    try:
        out = subprocess.run([str(exe), "-a"], capture_output=True, text=True, timeout=ARP_TIMEOUT,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), check=False)
    except (OSError, subprocess.SubprocessError):
        return []
    return parse_arp(out.stdout or "")


def find_by_mac(mac: str, host: str) -> str | None:
    """Where the device with `mac` is now, if the neighbour table knows and it is on host's subnet."""
    return pick(read_arp_table(), mac, host)
