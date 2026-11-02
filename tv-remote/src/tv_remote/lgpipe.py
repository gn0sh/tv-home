"""Volume for the PC on-screen remote (scripts/tv-remote-pc.ps1): one long-lived process that keeps the paired LG
connection open and reads one command per line from stdin.

Commands: ``up``, ``down``, ``mute`` (toggles). Anything else is refused. One line goes back on stdout per command:
``ok <volume or ->`` or ``error <reason>``. The LG ignores HDMI-CEC volume from the Google TV, so the remote talks to
the LG directly, with the same pairing, pinned certificate and volume cap as the MCP server's ``lg_volume``. No
client key, certificate or address is ever printed.
"""

from __future__ import annotations

import asyncio
import sys

from .devices import CertificateChanged, DeviceRefused, DeviceUnreachable, LgBackend, NotPaired

VOLUME_CAP = 60  # the same cap as lg_volume (ADR 0003)
COMMANDS = ("up", "down", "mute")


async def handle(lg: LgBackend, line: str) -> str:
    cmd = line.strip().lower()
    if cmd not in COMMANDS:
        return "error unknown command"
    try:
        if cmd == "mute":
            st = await lg.status()
            await lg.set_mute(not bool(st.muted))
            return "ok " + ("muted" if not st.muted else "unmuted")
        level = await lg.volume_step("up" if cmd == "up" else "down")
        if level is not None and level > VOLUME_CAP:
            level = await lg.set_volume(VOLUME_CAP)
        return "ok " + ("-" if level is None else str(level))
    except CertificateChanged:
        return "error the LG's certificate changed; pair it again"
    except NotPaired:
        return "error the LG is not paired"
    except DeviceRefused:
        return "error the LG refused"
    except DeviceUnreachable:
        return "error the LG did not answer"
    except Exception as exc:  # never a traceback (it could carry a host or key) on the pipe
        return "error " + type(exc).__name__


async def serve(lg: LgBackend, read_line, write_line) -> None:
    while True:
        line = await read_line()
        if not line:
            return
        write_line(await handle(lg, line))


def main() -> None:
    from .config import ConfigError, config_dir, load_config
    from .lg import LgRemote, LgVault
    from .secretstore import SecretStoreError, default_protector

    try:
        config = load_config()
        if not config.lg:
            raise ConfigError("no [lg] section in config.toml")
        lg = LgRemote(config.lg.host, config.lg.mac, LgVault(config_dir(), default_protector()))
    except (ConfigError, SecretStoreError) as exc:
        print(f"error {exc}", flush=True)
        sys.exit(2)

    async def read_line() -> str:
        return await asyncio.get_running_loop().run_in_executor(None, sys.stdin.readline)

    def write_line(text: str) -> None:
        print(text, flush=True)

    asyncio.run(serve(lg, read_line, write_line))
