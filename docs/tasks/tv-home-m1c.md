# Tasks: tv-home M1c (control the TVs from Claude on the phone or laptop)

Architecture: `docs/architecture/tv-home.md` section 8. ADR 0003. Where: sandbox / Brian (his PC and LAN).
Build order M1 → **M1c** → M1b. Independent of M1 except the shared `services.json` (M1b task B1, sandbox work).
ACs are the spec's M1c AC1-AC9 (`docs/specs/tv-home.md`). Security findings
(`docs/reviews/security-tv-home-design.md`) are tagged `#n` and are acceptance checks for the owning task.

| # | Task | Owner | Where | Depends | Files | ACs |
|---|---|---|---|---|---|---|
| C0 | security-engineer reviews `mcp`, `androidtvremote2`, `aiowebostv` and `uv` before anything is installed | security-engineer | sandbox | none | report text to manager | AC9 |
| C1 | Skeleton: `tv-remote/` uv project (Python ≥ 3.11), `mcp` FastMCP stdio server, config loader for `%APPDATA%\tv-remote\config.toml` (RFC 1918 check, LG MAC), tool registration with enums/ranges and clear refusals for anything outside the list, "which device is unreachable" errors, pytest, `.github/workflows/tv-remote.yml` (pytest only). `#8`: `uv.lock` with hashes, `uv run --locked`. `#7`: README states the allowlist binds MCP-only clients, and the Claude Code permission config never auto-allows `tv_pair` / `lg_power` | backend-engineer | sandbox | C0 | `tv-remote/pyproject.toml`, `tv-remote/uv.lock`, `tv-remote/src/tv_remote/` | AC5, AC6, AC7, AC8 |
| C2 | Shared deep links: Python reader of `services.json`, parity test on `deeplink-vectors.json` (same fixtures as M1b) | backend-engineer | sandbox | C1, B1 | `tv-remote/src/tv_remote/links.py` | AC1 |
| C3 | LG: wrapper over `aiowebostv` (power off, volume with cap 60, mute, input, status, foreground app), Wake-on-LAN builder and sender, pairing. `#5`: trimmed 8-permission manifest, a test that a call outside it fails on the real TV (in C6), wss 3001 only, client key DPAPI-encrypted, TLS fingerprint pinned. Tests: WoL bytes, SSAP messages against a fake websocket server, pairing state machine | backend-engineer | sandbox | C1 | `tv-remote/src/tv_remote/lg.py` | AC3, AC4 |
| C4 | Google TV: wrapper over `androidtvremote2` (two-step pairing tool, keys, app-link launch, current app). `#6`: TV cert fingerprint pinned at pairing, client private key DPAPI-encrypted. Tests against a fake remote | backend-engineer | sandbox | C1, C2 | `tv-remote/src/tv_remote/googletv.py` | AC1, AC2 |
| C5 | Setup on Brian's PC (each install and pairing is a separate OK, spec AC9): devops-engineer installs `uv` from a pinned winget version (`#8`) and the repo via Remote Control; `tv_discover` finds IPs and the LG MAC; Brian adds DHCP reservations in the Deco app and enables "Turn on via Wi-Fi" on the LG; pairing (Brian reads the code off the Google TV, accepts the LG prompt); register `claude mcp add tv-remote -- uv run --locked --directory <repo>\tv-remote tv-remote` and the desktop app config. No secrets pass through chat: pairing codes are one-time, and keys are created and stored by the server itself | devops-engineer, Brian | Brian | C3, C4 | `docs/user/tv-remote-setup.md` | AC4, AC9 |
| C6 | Acceptance run on his LAN: every tool, each forbidden request refused, each device unplugged in turn, PC off for the phone path, `netstat` (no listening port) and outbound-connection check, log grep for pairing material, LG out-of-manifest call fails (`#5`) | qa-engineer | Brian | C5 | `docs/qa/tv-home-m1c.md` | AC1-AC9 |
| C7 | Reviews: security-engineer re-checks C1, C3, C4 (`#5`-`#8`); code-reviewer on each task | security-engineer, code-reviewer | sandbox | each task | report text to manager | all |

Order: C0 → C1 → (C2 after B1, C3, C4 in parallel) → C5 → C6. C1-C4 are sandbox work and can start now.
Unit-testable in the sandbox: tool schemas and allowlist, config validation, deep-link parity, WoL bytes, SSAP message
building and parsing, pairing state machines (all against fakes). Needs his network: pairing, real key and app-link
behaviour, Wake-on-LAN, the trimmed-permission check, latency, the Remote Control phone path.
Brian's steps: about 20 min once (pairing at each TV, Deco reservations, one LG setting), then nothing.
