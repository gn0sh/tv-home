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
import weakref

from .devices import CertificateChanged, DeviceRefused, DeviceUnreachable, LgBackend, NotPaired

VOLUME_CAP = 60  # the same cap as lg_volume (ADR 0003)
COMMANDS = ("up", "down", "mute")


_LOCKS: weakref.WeakKeyDictionary = weakref.WeakKeyDictionary()


def lock_for(lg: LgBackend) -> asyncio.Lock:
    """One lock per TV per event loop: commands to the same TV run one at a time (volume-cap-race)."""
    loop = asyncio.get_running_loop()
    held = _LOCKS.get(lg)
    if held is None or held[0] is not loop:
        held = (loop, asyncio.Lock())
        _LOCKS[lg] = held
    return held[1]


async def handle(lg: LgBackend, line: str, *, name: str = "LG", unknown_up: str = "refuse") -> str:
    """One volume command with the cap. `name` is the TV's name in error text ("LG" or "Vizio"). `unknown_up`:
    "refuse" (the LG and the MCP pipe) refuses to step up from an unknown level; "step" (the Vizio, ADR 0007) takes one
    step, which the PC remote allows only once per tap, and still clamps if the TV then reports a level above 60."""
    cmd = line.strip().lower()
    if cmd not in COMMANDS:
        return "error unknown command"
    # Read, step and clamp are one unit (volume-cap-race): two presses at 59 must not both step, and a caller that
    # gives up (timeout, window closing) must not cancel it between a step up and the clamp, so it is shielded.
    return await asyncio.shield(_locked(lg, cmd, name, unknown_up == "step"))


async def _locked(lg: LgBackend, cmd: str, name: str = "LG", step_unknown: bool = False) -> str:
    async with lock_for(lg):
        return await _run(lg, cmd, name, step_unknown)


async def _run(lg: LgBackend, cmd: str, name: str = "LG", step_unknown: bool = False) -> str:
    the = "the " + name
    try:
        if cmd == "mute":
            st = await lg.status()
            await lg.set_mute(not bool(st.muted))
            return "ok " + ("muted" if not st.muted else "unmuted")
        if cmd == "up":
            # The cap is checked before stepping, as lg_volume does (server.py, finding volume-step-cap): never step
            # up from an unknown level, and never step past 60.
            current = (await lg.status()).volume
            if current is None and not step_unknown:
                return f"error {the} did not report its volume"
            if current is not None and current >= VOLUME_CAP:
                if current > VOLUME_CAP:
                    current = await lg.set_volume(VOLUME_CAP)
                return "ok " + str(current)
        level = await lg.volume_step(cmd)
        if cmd == "up" and level is None:
            # The TV did not say where it landed; look, so a step of more than one cannot stay above the cap.
            level = (await lg.status()).volume
        if level is not None and level > VOLUME_CAP:
            level = await lg.set_volume(VOLUME_CAP)
        return "ok " + ("-" if level is None else str(level))
    except CertificateChanged:
        return f"error {the}'s certificate changed; pair it again"
    except NotPaired:
        return f"error {the} is not paired"
    except DeviceRefused:
        return f"error {the} refused"
    except DeviceUnreachable:
        return f"error {the} did not answer"
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
