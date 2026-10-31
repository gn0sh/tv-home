# tv-remote

A local MCP server that lets Claude control Brian's LG webOS TV and Google TV over the home network
(ADR `docs/adr/0003-tv-home-remote-mcp-server.md`, architecture section 8). It speaks MCP over **stdio only**: Claude
Code or the Claude desktop app starts it as a child process. It opens no listening port and uses no cloud relay. From
the phone it works through a Remote Control session on the PC, so the PC must be on.

## Tools (the complete allowlist)
`tv_status`, `tv_open`, `tv_launch_app`, `tv_key`, `lg_power`, `lg_volume` (0-60, capped), `lg_mute`, `lg_input`
(HDMI_1..HDMI_4), `tv_pair`, `tv_discover`. Any other request (install or uninstall, settings, factory reset,
accounts, raw adb or SSAP commands, browser, notifications) is refused with a message that says so. Arguments are
enums and ranges, checked before anything is sent. If a TV does not answer, the tool says which one and does nothing
else.

## The allowlist is not a security boundary inside Claude Code (security finding #7)
The allowlist binds **MCP-only clients**, such as the Claude desktop app, where these tools are the only way to reach
the TVs. Inside Claude Code the model also has Bash and could talk to the TVs directly, so the allowlist there is a
convenience, not a guard. In Claude Code permissions, **`tv_pair` and `lg_power` are never auto-allowed**: do not add
`mcp__tv-remote__tv_pair` or `mcp__tv-remote__lg_power` to any `permissions.allow` list. Each use asks first.

## Config
`%APPDATA%\tv-remote\config.toml` (folder readable by Brian's user only):

```toml
[lg]
host = "192.168.68.61"       # literal private (RFC 1918) IPv4 address; host names are refused
mac = "AA:BB:CC:DD:EE:FF"    # for Wake-on-LAN power on

[googletv]
host = "192.168.68.81"
```

Either section may be left out. Pairing material (LG client key, Google TV client cert and key, both TVs' pinned
fingerprints) is stored in the same folder by the device backends, encrypted with DPAPI, and is never returned by a
tool or written to logs. On non-Windows machines (tests, development) set `TV_REMOTE_CONFIG_DIR` to the folder.

## Run and test
Dependencies are locked with hashes in `uv.lock` (security finding #8). Always use `--locked`:

```
uv run --locked tv-remote      # the MCP server (stdio)
uv run --locked pytest         # unit tests, against fake devices
```

Claude Code registration (on Brian's PC, after his OK): `claude mcp add tv-remote -- uv run --locked --directory
<path-to>\tv-remote tv-remote`.

## Status
C1 skeleton: config, tool allowlist, refusals, unreachable errors, fakes. The real device wrappers are stubs that say
which task builds them: LG in C3 (`lg.py`), Google TV in C4 (`googletv.py`), deep links for `tv_open` in C2
(`links.py`).
