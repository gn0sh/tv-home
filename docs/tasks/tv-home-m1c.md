# Tasks: tv-home M1c (control the TVs from Claude on the phone or laptop)

Architecture: `docs/architecture/tv-home.md` section 8. ADR 0003. Where: sandbox / Brian (his PC and LAN).
Independent of M1 and M1b except the shared `services.json` (M1b task B1); nothing here needs CI minutes beyond pytest.

## Proposed acceptance criteria (for the product-manager to fold into the spec)
- C-AC1 From Claude on his phone (Remote Control session on the PC) or laptop, "open Stranger Things on Netflix"
  opens it on the Google TV within 10 s.
- C-AC2 "Turn the TV off", "turn it on", "volume 20", "mute", "switch to HDMI 2" work on the LG.
- C-AC3 D-pad, OK, Back, Home, play/pause, rewind and fast-forward work on the Google TV.
- C-AC4 The server exposes only the allowlisted tools; arguments outside the enums or ranges are rejected; volume never above 60.
- C-AC5 No listening port on the PC (`netstat` check); only private-address hosts accepted; no pairing material in tool output, logs or the repo.
- C-AC6 Deep links come from the shared `services.json` and pass the same vectors as the Kotlin builder.

| # | Task | Owner | Where | Depends | Files | ACs |
|---|---|---|---|---|---|---|
| C1 | Project skeleton: `tv-remote/` uv project (Python ≥ 3.11), `mcp` FastMCP stdio server, config loader for `%APPDATA%\tv-remote\config.toml` (private-address check, LG MAC), tool registration with enums/ranges, pytest, `.github/workflows/tv-remote.yml` (pytest only) | backend-engineer | sandbox | security-engineer OK on `mcp`, `androidtvremote2`, `aiowebostv`, `uv` | `tv-remote/pyproject.toml`, `tv-remote/src/tv_remote/` | C-AC4, C-AC5 |
| C2 | Shared deep links: Python reader of `services.json`, parity test on `deeplink-vectors.json` | backend-engineer | sandbox | C1, M1b B1 | `tv-remote/src/tv_remote/links.py` | C-AC6 |
| C3 | LG: wrapper over `aiowebostv` (power off, volume with cap, mute, input, status, foreground app), Wake-on-LAN packet builder and sender, pairing with client-key storage and TLS fingerprint pin. Tests: WoL bytes, SSAP requests built and responses parsed against a fake websocket server, pairing state machine | backend-engineer | sandbox | C1 | `tv-remote/src/tv_remote/lg.py` | C-AC2 |
| C4 | Google TV: wrapper over `androidtvremote2` (two-step pairing tool, keys, app link launch, current app), cert storage. Tests against a fake remote | backend-engineer | sandbox | C1, C2 | `tv-remote/src/tv_remote/googletv.py` | C-AC1, C-AC3 |
| C5 | Setup on Brian's PC via Remote Control: install `uv`, clone/update the repo, `tv_discover` to find IPs and the LG MAC, Brian adds DHCP reservations in the Deco app, Brian enables "Turn on via Wi-Fi" on the LG, pairing (Brian reads the code off the Google TV; accepts the prompt on the LG), register the server: `claude mcp add tv-remote -- uv run --directory <repo>\tv-remote tv-remote` and the desktop app config | devops-engineer | Brian | C3, C4 | `docs/user/tv-remote-setup.md` | C-AC1, C-AC2 |
| C6 | Acceptance run on his LAN: every tool, the phone path through a Remote Control session, `netstat` and log checks | qa-engineer | Brian | C5 | `docs/qa/tv-home-m1c.md` | all |
| C7 | Reviews: security-engineer on the allowlist, config and pairing storage, LAN posture and the third-party packages (before C1 installs them); code-reviewer on each task | security-engineer, code-reviewer | sandbox | each task | report text to manager | all |

Order: C7 (package review) → C1 → (C2 after B1, C3, C4 in parallel) → C5 → C6. C1-C4 are all sandbox work and can
start now. Unit-testable in the sandbox: tool schemas and allowlist, config validation, deep-link parity, WoL bytes,
SSAP message building and parsing, pairing state machines (all against fakes). Needs his network: pairing, real key
and app-link behaviour, Wake-on-LAN, latency, the Remote Control phone path.
Brian's steps: about 20 min once (pairing at each TV, Deco reservations, one LG setting), then nothing.
