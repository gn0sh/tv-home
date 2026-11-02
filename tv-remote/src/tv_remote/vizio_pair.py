"""tv-remote-vizio-pair: pair the PC remote with the Vizio TV once (ADR 0007). Run it in PowerShell, at the TV.

    tv-remote-vizio-pair                  find the Vizio (or use [vizio] host in config.toml), show a PIN, type it
    tv-remote-vizio-pair --host 192.168.68.90
    tv-remote-vizio-pair --forget         remove the stored token and certificate pin

The auth token is sealed with DPAPI into %APPDATA%\\tv-remote\\vizio-auth.sealed and is never printed.
"""

from __future__ import annotations

import argparse
import asyncio
import ipaddress
import re
import socket
import sys
import time
import urllib.request
from pathlib import Path

SSDP_ADDR = ("239.255.255.250", 1900)
SSDP_ST = "urn:dial-multiscreen-org:device:dial:1"  # Vizio SmartCast TVs answer as DIAL devices
SSDP_WAIT = 3.0
DESCRIPTION_MAX = 64 * 1024
_PRIVATE = (ipaddress.ip_network("10.0.0.0/8"), ipaddress.ip_network("172.16.0.0/12"),
            ipaddress.ip_network("192.168.0.0/16"))


def _private(ip: str) -> bool:
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return addr.version == 4 and any(addr in n for n in _PRIVATE)


def _clean(text: str, limit: int = 40) -> str:
    return re.sub(r"[^\w .,()+-]", "", text)[:limit].strip()


def parse_ssdp(packet: bytes) -> str | None:
    """The LOCATION URL of one M-SEARCH answer, or None."""
    try:
        text = packet.decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return None
    for line in text.split("\r\n"):
        name, _, value = line.partition(":")
        if name.strip().lower() == "location":
            return value.strip()
    return None


def describe(ip: str, location: str, opener=None) -> str | None:
    """The friendly name if the device at `location` says it is made by VIZIO, else None. Only http to the same
    private address that answered, no proxy, 2 s, 64 KB."""
    m = re.match(r"\Ahttp://([0-9.]+)(:\d{1,5})?/", location or "")
    if not m or m.group(1) != ip or not _private(ip):
        return None
    opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(location, timeout=2) as resp:
            body = resp.read(DESCRIPTION_MAX).decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return None
    maker = re.search(r"<manufacturer>([^<]*)</manufacturer>", body, re.I)
    if not maker or "vizio" not in maker.group(1).lower():
        return None
    name = re.search(r"<friendlyName>([^<]*)</friendlyName>", body, re.I)
    return _clean(name.group(1)) if name else "Vizio"


def discover(wait: float = SSDP_WAIT, sock_factory=None, describer=describe) -> list[tuple[str, str]]:
    """[(ip, name)] of Vizio TVs that answer an SSDP search on the LAN."""
    msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
           f"ST: {SSDP_ST}\r\nMX: 2\r\n\r\n").encode()
    sock = (sock_factory or (lambda: socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)))()
    seen: dict[str, str] = {}
    try:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        sock.settimeout(0.5)
        sock.sendto(msg, SSDP_ADDR)
        end = time.monotonic() + wait
        while time.monotonic() < end and len(seen) < 32:
            try:
                packet, (ip, _port) = sock.recvfrom(2048)
            except (socket.timeout, TimeoutError):
                continue
            loc = parse_ssdp(packet)
            if ip not in seen and loc and _private(ip):
                seen[ip] = loc
    finally:
        sock.close()
    found = []
    for ip, loc in seen.items():
        name = describer(ip, loc)
        if name:
            found.append((ip, name))
    return found


def _add_host_to_config(path: Path, host: str) -> str:
    """Adds a [vizio] section when there is none. An existing one is left as it is (Brian's file)."""
    from .config import ConfigError, load_config

    try:
        current = load_config(path)
    except ConfigError:
        current = None
    if current is not None and current.vizio is not None:
        if current.vizio.host == host:
            return f"config.toml already has [vizio] host = \"{host}\"."
        return (f"config.toml has [vizio] host = \"{current.vizio.host}\"; this run used {host}. "
                "Edit config.toml if the TV's address changed.")
    path.parent.mkdir(parents=True, exist_ok=True)
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    sep = "" if not existing or existing.endswith("\n") else "\n"
    path.write_text(existing + sep + f"\n[vizio]\nhost = \"{host}\"\n", encoding="utf-8")
    return f"Added [vizio] host = \"{host}\" to {path}."


def _pick_host(args, config_host: str | None, ask=input, finder=discover) -> str | None:
    if args.host:
        return args.host
    if config_host:
        print(f"Using the Vizio at {config_host} from config.toml (use --host to choose another).")
        return config_host
    print("Looking for a Vizio on the home network...")
    found = finder()
    if len(found) == 1:
        ip, name = found[0]
        answer = ask(f"Found {name} at {ip}. Use it? [Y/n] ").strip().lower()
        if answer in ("", "y", "yes"):
            return ip
    elif found:
        for i, (ip, name) in enumerate(found, 1):
            print(f"  {i}. {name} at {ip}")
        answer = ask("Which one? Type its number: ").strip()
        if answer.isdigit() and 1 <= int(answer) <= len(found):
            return found[int(answer) - 1][0]
    else:
        print("No Vizio answered. (Find its address on the TV: Settings > Network > Network Information.)")
    typed = ask("Type the Vizio's IP address (or press Enter to stop): ").strip()
    return typed or None


def main(argv: list[str] | None = None, *, ask=input, finder=discover, remote_factory=None,
         vault_factory=None, config_path: Path | None = None) -> int:
    from .config import CONFIG_FILE, ConfigError, check_private_host, config_dir, load_config

    parser = argparse.ArgumentParser(prog="tv-remote-vizio-pair", description="Pair the PC remote with the Vizio TV.")
    parser.add_argument("--host", help="the Vizio's IP address, e.g. 192.168.68.90")
    parser.add_argument("--forget", action="store_true", help="remove the stored Vizio token and certificate pin")
    args = parser.parse_args(argv)

    try:
        folder = config_dir()
    except ConfigError as exc:
        print(f"Error: {exc}")
        return 2
    cfg_path = config_path or folder / CONFIG_FILE
    if vault_factory is None:
        from .secretstore import SecretStoreError, default_protector
        from .vizio import VizioVault

        try:
            vault = VizioVault(folder, default_protector())
        except SecretStoreError as exc:
            print(f"Error: {exc}")
            return 2
    else:
        vault = vault_factory(folder)

    if args.forget:
        removed = vault.forget()
        print("Removed " + ", ".join(removed) + "." if removed else "Nothing to remove: the Vizio was not paired.")
        return 0

    try:
        config_host = load_config(cfg_path).vizio
        config_host = config_host.host if config_host else None
    except ConfigError:
        config_host = None
    host = _pick_host(args, config_host, ask=ask, finder=finder)
    if not host:
        print("Stopped; nothing was changed.")
        return 1
    try:
        host = check_private_host(host, "Vizio address")
    except ConfigError as exc:
        print(f"Error: {exc}")
        return 2

    if remote_factory is None:
        from .vizio import VizioRemote

        remote = VizioRemote(host, vault)
    else:
        remote = remote_factory(host, vault)
    return _pair(remote, host, cfg_path, ask)


def _pair(remote, host: str, cfg_path: Path, ask) -> int:
    """Each network step runs in its own short event loop; the PIN is typed in between, so Ctrl+C there is plain."""
    from .devices import CertificateChanged, DeviceRefused, DeviceUnreachable, NotPaired

    try:
        print(asyncio.run(remote.start_pairing()))
    except DeviceUnreachable:
        print(f"The Vizio at {host} did not answer on port 7345 or 9000. Is it on, and is the address right?")
        return 1
    except (DeviceRefused, NotPaired) as exc:
        print(f"Error: {exc}")
        return 1
    try:
        pin = ask("Type the PIN shown on the Vizio: ")
    except (KeyboardInterrupt, EOFError):
        asyncio.run(remote.cancel_pairing())
        print("\nStopped; nothing was stored.")
        return 1
    try:
        message = asyncio.run(remote.finish_pairing(pin))
    except CertificateChanged:
        asyncio.run(remote.cancel_pairing())
        print("Error: a different device answered for the PIN than the one that showed it. Nothing was stored.")
        return 1
    except (DeviceUnreachable, DeviceRefused, NotImplementedError) as exc:
        asyncio.run(remote.cancel_pairing())
        print(f"Error: {exc}")
        return 1
    print(message)
    if message != "Vizio paired.":
        asyncio.run(remote.cancel_pairing())
        return 1
    print(_add_host_to_config(cfg_path, host))
    print("Done. Open TV Remote, tap Vizio at the top, and try the volume buttons.")
    return 0


def entry() -> None:
    sys.exit(main())
