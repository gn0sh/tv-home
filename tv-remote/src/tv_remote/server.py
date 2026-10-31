"""The tv-remote MCP server: the fixed tool allowlist from architecture section 8, stdio transport only."""

from __future__ import annotations

import asyncio
import logging
import sys
from collections.abc import Awaitable, Callable
from typing import Annotated, Any, Literal

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp_types import ToolAnnotations
from pydantic import Field

from .config import Config, ConfigError, load_config
from .devices import (
    VOLUME_CAP,
    DeviceUnreachable,
    Discovery,
    DiscoveryStub,
    GoogleTvBackend,
    GoogleTvStub,
    Key,
    LgBackend,
    LgInput,
    LgStub,
    NotPaired,
)

log = logging.getLogger("tv_remote")

# Service keys from architecture section 7 (services.json starting values). C2 replaces this with the reader of
# core/src/main/resources/tvhome/services.json and a test that the keys match.
Service = Literal[
    "netflix", "youtube", "disneyplus", "prime", "max", "hulu", "appletv", "paramountplus", "peacock"
]
SERVICE_PACKAGES: dict[str, str] = {
    "netflix": "com.netflix.ninja",
    "youtube": "com.google.android.youtube.tv",
    "disneyplus": "com.disney.disneyplus",
    "prime": "com.amazon.amazonvideo.livingroom",
    "max": "com.wbd.stream",
    "hulu": "com.hulu.livingroomplus",
    "appletv": "com.apple.atve.androidtv.appletv",
    "paramountplus": "com.cbs.ott",
    "peacock": "com.peacocktv.peacockandroid",
}

ALLOWED_TOOLS = (
    "tv_status", "tv_open", "tv_launch_app", "tv_key", "lg_power",
    "lg_volume", "lg_mute", "lg_input", "tv_pair", "tv_discover",
)

REFUSAL = (
    "Refused: '{name}' is not something tv-remote can do. It only offers: " + ", ".join(ALLOWED_TOOLS) + ". "
    "Installing or uninstalling apps, changing settings, factory reset, account changes, raw adb or SSAP "
    "commands, the browser and notifications are never available."
)

INSTRUCTIONS = (
    "Controls Brian's LG TV and Google TV on the home network. Only the listed tools exist; anything else "
    "(uninstall, settings, factory reset, accounts, raw commands) must be refused. If a tool reports that a "
    "device is unreachable, tell Brian which one and do nothing else. LG volume is capped at 60."
)

LinkBuilder = Callable[[str, str | None, str | None], str]


def _link_builder_not_built(service: str, content_id: str | None, search_query: str | None) -> str:
    raise NotImplementedError("Opening a title or search is not built yet (task C2, links.py).")


ContentId = Annotated[str, Field(pattern=r"^[A-Za-z0-9._:-]{1,64}$", description="The service's own title id")]
SearchQuery = Annotated[str, Field(min_length=1, max_length=100, pattern=r"^[^\x00-\x1f\x7f]+$")]
PairCode = Annotated[str, Field(pattern=r"^[0-9A-Fa-f]{6}$", description="6-character code shown on the Google TV")]


class TvRemoteServer(MCPServer):
    async def call_tool(self, name: str, arguments: dict[str, Any], context=None):
        if name not in ALLOWED_TOOLS:
            raise ToolError(REFUSAL.format(name=name))
        return await super().call_tool(name, arguments, context)


def build_server(
    config: Config,
    lg: LgBackend | None,
    gtv: GoogleTvBackend | None,
    discovery: Discovery,
    link_builder: LinkBuilder = _link_builder_not_built,
) -> TvRemoteServer:
    server = TvRemoteServer(name="tv-remote", instructions=INSTRUCTIONS)

    def need_lg() -> LgBackend:
        if lg is None:
            raise ToolError("The LG TV is not set up: add an [lg] section to config.toml.")
        return lg

    def need_gtv() -> GoogleTvBackend:
        if gtv is None:
            raise ToolError("The Google TV is not set up: add a [googletv] section to config.toml.")
        return gtv

    async def run(action: Awaitable[Any]) -> Any:
        try:
            return await action
        except DeviceUnreachable as exc:
            raise ToolError(f"{exc} Nothing was sent; check that it is on and on the home network.") from None
        except NotPaired as exc:
            raise ToolError(str(exc)) from None
        except NotImplementedError as exc:
            raise ToolError(str(exc)) from None

    read_only = ToolAnnotations(read_only_hint=True, destructive_hint=False, open_world_hint=False)
    acts = ToolAnnotations(read_only_hint=False, destructive_hint=False, open_world_hint=False)

    @server.tool(annotations=read_only)
    async def tv_status() -> dict:
        """Reachability of both TVs, LG power/volume/input/foreground app, and the Google TV's current app."""
        out: dict[str, Any] = {}
        if lg is not None:
            try:
                s = await lg.status()
                out["lg"] = {"reachable": True, "power": s.power, "volume": s.volume, "muted": s.muted,
                             "input": s.input, "foreground_app": s.foreground_app}
            except DeviceUnreachable as exc:
                out["lg"] = {"reachable": False, "error": str(exc)}
            except (NotPaired, NotImplementedError) as exc:
                out["lg"] = {"reachable": None, "error": str(exc)}
        else:
            out["lg"] = {"configured": False}
        if gtv is not None:
            try:
                out["googletv"] = {"reachable": True, "current_app": await gtv.current_app()}
            except DeviceUnreachable as exc:
                out["googletv"] = {"reachable": False, "error": str(exc)}
            except (NotPaired, NotImplementedError) as exc:
                out["googletv"] = {"reachable": None, "error": str(exc)}
        else:
            out["googletv"] = {"configured": False}
        return out

    @server.tool(annotations=acts)
    async def tv_open(service: Service, content_id: ContentId | None = None,
                      search_query: SearchQuery | None = None) -> str:
        """Open a title (content_id) or a search (search_query) in a streaming service on the Google TV."""
        if content_id and search_query:
            raise ToolError("Give either content_id or search_query, not both.")
        g = need_gtv()
        try:
            uri = link_builder(service, content_id, search_query)
        except NotImplementedError as exc:
            raise ToolError(str(exc)) from None
        await run(g.open_link(uri))
        return f"Opened {service} on the Google TV."

    @server.tool(annotations=acts)
    async def tv_launch_app(service: Service) -> str:
        """Launch a streaming app on the Google TV."""
        await run(need_gtv().launch_package(SERVICE_PACKAGES[service]))
        return f"Launched {service} on the Google TV."

    @server.tool(annotations=acts)
    async def tv_key(key: Key, repeat: Annotated[int, Field(ge=1, le=10)] = 1) -> str:
        """Press a remote button on the Google TV, 1-10 times."""
        g = need_gtv()
        for _ in range(repeat):
            await run(g.send_key(key))
        return f"Pressed {key} x{repeat} on the Google TV."

    @server.tool(annotations=acts)
    async def lg_power(state: Literal["on", "off"]) -> str:
        """Turn the LG TV on (Wake-on-LAN) or off."""
        t = need_lg()
        await run(t.power_on() if state == "on" else t.power_off())
        return f"Sent power {state} to the LG TV."

    @server.tool(annotations=acts)
    async def lg_volume(level: Annotated[int, Field(ge=0, le=VOLUME_CAP)] | Literal["up", "down"]) -> str:
        """Set the LG TV volume to 0-60 (capped at 60), or step it up/down."""
        t = need_lg()
        if level in ("up", "down"):
            new = await run(t.volume_step(level))
        else:
            new = await run(t.set_volume(level))
        return f"LG TV volume is {new}."

    @server.tool(annotations=acts)
    async def lg_mute(muted: bool) -> str:
        """Mute (true) or unmute (false) the LG TV."""
        await run(need_lg().set_mute(muted))
        return "LG TV muted." if muted else "LG TV unmuted."

    @server.tool(annotations=acts)
    async def lg_input(source: LgInput) -> str:
        """Switch the LG TV input to HDMI_1..HDMI_4."""
        await run(need_lg().set_input(source))
        return f"LG TV input set to {source}."

    @server.tool(annotations=acts)
    async def tv_pair(device: Literal["lg", "googletv"], code: PairCode | None = None) -> str:
        """Pair once with a TV. LG: accept the prompt on the TV. Google TV: call without code, then again with
        the code shown on the TV. Pairing secrets are stored locally and never returned."""
        if device == "lg":
            if code is not None:
                raise ToolError("LG pairing has no code: call tv_pair with device='lg' and accept on the TV.")
            return await run(need_lg().pair())
        g = need_gtv()
        if code is None:
            return await run(g.start_pairing())
        return await run(g.finish_pairing(code))

    @server.tool(annotations=read_only)
    async def tv_discover() -> list[dict]:
        """Find TVs on the home network (setup only)."""
        found = await run(discovery.discover())
        return [{"kind": d.kind, "host": d.host, "name": d.name} for d in found]

    return server


def main() -> None:
    # stdout carries the MCP protocol; logs go to stderr only.
    logging.basicConfig(stream=sys.stderr, level=logging.INFO)
    try:
        config = load_config()
    except ConfigError as exc:
        print(f"tv-remote: {exc}", file=sys.stderr)
        sys.exit(2)
    lg = LgStub(config.lg.host, config.lg.mac) if config.lg else None
    gtv = GoogleTvStub(config.googletv.host) if config.googletv else None
    server = build_server(config, lg, gtv, DiscoveryStub())
    asyncio.run(server.run_stdio_async())  # stdio only: no listening port (AC6)


if __name__ == "__main__":
    main()
