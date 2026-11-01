# tv-remote

A local MCP server that lets Claude control Brian's LG webOS TV and Google TV over the home network
(ADR `docs/adr/0003-tv-home-remote-mcp-server.md`, architecture section 8). It speaks MCP over **stdio only**: Claude
Code or the Claude desktop app starts it as a child process. It opens no listening port and uses no cloud relay. From
the phone it works through a Remote Control session on the PC, so the PC must be on.

## Tools (the complete allowlist)
`tv_status`, `tv_open`, `tv_launch_app`, `tv_key` (includes VOLUME_UP/DOWN, MUTE and POWER, which reach the LG over HDMI-CEC; volume keys change the LG only if the Google TV's HDMI-CEC volume control is on, and VOLUME_UP is limited to 3 per call and 10 per minute), `lg_power`, `lg_volume` (0-60, capped), `lg_mute`, `lg_input`
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

Either section may be left out. Hosts are checked as if the home subnet is a /24. If it is wider, write the prefix,
for example `host = "10.0.5.20/16"`. The subnet's network and broadcast addresses (such as `192.168.68.0` and
`192.168.68.255`) are refused.

Pairing material is stored in the same folder and is never returned by a tool or written to logs.
- Google TV (C4, built): `atv-client-cert.pem` (public), `atv-client-key.pem` (the private key as encrypted PKCS#8,
  never written unencrypted), `atv-client-key.pw.sealed` (its random password, sealed with DPAPI for Brian's Windows
  user), and `atv-tv-cert.sha256`, the TV's certificate fingerprint pinned when pairing succeeds. The pin is checked
  inside every TLS handshake, before anything is sent. If the TV presents a different certificate, every Google TV
  command is refused until it is paired again. If the key can no longer be unsealed, `tv_pair` creates a new one. Pairing needs Windows. Elsewhere the server refuses to store keys.
- LG (C3, built): `lg-client-key.sealed` (the client key, sealed with DPAPI, never written unencrypted) and
  `lg-tv-cert.sha256`. Connections use wss on port 3001 only, never ws on 3000. tv-remote registers with only 8
  permissions (launch, power, audio, TV input, and reading the input list, running apps, power state and app status),
  so the stored key cannot change settings, show toasts or send input events. The TV certificate is pinned the first
  time pairing succeeds. LG pairing has no code to check, so that first pairing trusts the certificate it sees; do
  it at the TV. Power on uses Wake-on-LAN (limited broadcast to port 9, which stays on the LAN): enable "Turn on via
  Wi-Fi" or Quick Start+ on the TV. On non-Windows machines (tests, development) set `TV_REMOTE_CONFIG_DIR` to the folder.

## Run and test
Dependencies are locked with hashes in `uv.lock` (security finding #8). Always use `--locked`. Use uv 0.11.x:
the build backend is `uv_build>=0.11,<0.12`, which uv builds directly only when its own version is in that range.
Another uv version would download the backend unhashed. Bump the range and uv together.

```
uv run --locked tv-remote      # the MCP server (stdio)
uv run --locked pytest         # unit tests, against fake devices
```

Claude Code registration (on Brian's PC, after his OK): `claude mcp add tv-remote -- uv run --locked --directory
<path-to>\tv-remote tv-remote`.

## Status
Built: C1 (config, tool allowlist, refusals, unreachable errors), C4 (Google TV, `googletv.py`) and C3 (LG, `lg.py`).
Shared pieces are `secretstore.py` (DPAPI) and `tlspin.py` (certificate pin checked in the TLS handshake). Not built
yet, and their tools say so: deep links for `tv_open` (C2, `links.py`) and discovery.
