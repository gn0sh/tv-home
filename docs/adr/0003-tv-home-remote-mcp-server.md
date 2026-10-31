# ADR 0003: tv-home M1c, control the TVs from Claude on the phone or laptop

Status: proposed (architect), 2026-10-31. Architecture: `docs/architecture/tv-home.md` section 8.

## Context
Brian (11:26Z): control the TV by typing to Claude on his phone or laptop. He has an LG OLED (webOS) and, per the
spec, a Google TV device; LAN 192.168.68.x on a TP-Link Deco mesh; IPs (.61 / .81) unconfirmed. His Windows PC
already runs Claude Code with MCP servers (REA), Node and Temurin JDK 21. The proposed shape was a local MCP server
using adb for Google TV and SSAP for LG.

## Decision
1. **A local MCP server `tv-remote` on Brian's PC, stdio transport only.** Claude Code and the Claude desktop app
   launch it as a child process; the phone reaches it through a Remote Control session on the PC. No listening port,
   no inbound internet exposure, no cloud relay.
2. **Language: Python**, run with `uv` (free, single binary, manages its own Python), on the official `mcp` SDK
   (2.3.0, MIT). The deciding factor is the device libraries: `androidtvremote2` and `aiowebostv` are the
   maintained libraries Home Assistant uses for these exact devices (releases in Aug and Sep 2026). In Kotlin we
   would hand-roll both protocols.
3. **Google TV over the Android TV Remote protocol v2 (`androidtvremote2`), not adb.** It pairs once with a code
   shown on the TV (as the Google TV phone app does), sends keys and app links (so deep links work), and reports
   the current app. It needs no Developer options, and it does not leave a network shell (adb) open to the LAN for
   daily use. adb stays a setup-time tool for sideloading M1.
4. **LG webOS over SSAP (`aiowebostv`)**, wss port 3001 with the TV's self-signed certificate pinned on first
   pairing, and Wake-on-LAN for power on.
5. **Shared deep links:** `core/src/main/resources/tvhome/services.json` is the single source for packages and link
   templates. Python reads it; a shared `deeplink-vectors.json` is run by both the Kotlin and the Python test
   suites, so the builders cannot drift.
6. **Fixed tool allowlist** (architecture section 8). Nothing destructive: no install/uninstall, settings, raw
   commands, browser, notifications or factory reset. Volume capped at 60. Hosts must be private addresses.
   Pairing material lives in `%APPDATA%\tv-remote\` and is never returned by a tool.

## Alternatives considered
- **adb over Wi-Fi for Google TV** (the proposed shape): full control (`am start -d`, `input keyevent`,
  `dumpsys media_session` for what's playing). Rejected for daily use: needs Developer options and network debugging
  left on (wireless debugging on newer Google TV builds turns off after a reboot or network change), and an adb
  connection is a full shell. Kept as a setup tool and as a fallback backend behind the same tool names.
- **Kotlin/JVM MCP server** (official `io.modelcontextprotocol:kotlin-sdk` is on Maven Central): reuses `:core`
  directly. Rejected because both TV protocols would be ours to write and maintain; the deep-link reuse is solved
  by the shared JSON.
- **TypeScript MCP server** (Node is already on his PC): the npm libraries for both protocols are less maintained.
  Rejected.
- **Home Assistant** on the PC, with its MCP server: gives both integrations and much more, but is a whole home
  automation install to run and secure for a few tools. Rejected as heavier; reasonable if Brian ever wants home
  automation.
- **Don't build it: the Google TV phone app and LG ThinQ app** already give a phone remote. What they lack is
  "type a request in words". M1c is the thin layer that adds Claude to it.

## Consequences
- Works only while the PC is on and on the same LAN as the TVs.
- Pairing needs Brian at the TV once per device (code on the Google TV screen, accept prompt on the LG).
- LG power-on needs "Turn on via Wi-Fi" (or Quick Start+) enabled on the TV and the TV's MAC address.
- Protocol logic is unit-tested in the sandbox against fakes; everything that touches the devices is tested on his LAN.
- Third-party packages (`mcp`, `androidtvremote2`, `aiowebostv`, `uv`) need security-engineer review before install,
  per the playbook.
- M1c also covers much of M1b's "ask for a show" use case through Claude on the phone, with no API key on the TV
  (ADR 0002).
