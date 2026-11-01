# tv-home status

Feature: tv-home (custom Google TV home screen). Priority: Brian 2026-10-31 23:47Z: install the dashboard ASAP within usage (first claim on this thread).
Stage (2026-11-01 05:00Z): M1 built, reviewed, CI green on 8dcfa5a (core, app, emulator T9, live feed). PR #1 ready for Brian to merge. M1c tv-remote installed on Brian's PC; CEC power works, LG BX ignores CEC volume. Kodi (M5) parked. Next: T11 install. Mockup: https://claude.ai/artifact/L8f5rSyjZGA46d5BuJEier

## Blockers
- No Android SDK in the cloud sandbox (dl.google.com blocked). APKs build in GitHub Actions; private-repo minutes reset 2026-11-01 00:00Z.

## Waiting on the CEO
- Merge PR #1, then run new-signing-key.ps1 in his own window (reply done) | https://github.com/gn0sh/tv-home/pull/1, docs/release/tv-home-signing.md | tap Merge, paste 3 PowerShell lines | asked 2026-11-01 in thread "Build Google TV dashboard" | default: wait (hard stop: signing key is a new secret he creates)
- Next, not yet asked: typed OK to install on the TV (T11, hard stop), then he runs provision.ps1; then AC8-offline check on the real TV (QA doc).

## Problem-key ledger
- offline-not-really-off: 1 (T9 review, fixed 3054ab3, now NOT VERIFIED on emulator)
- qa-silent-degrade: 1 (fixed 3054ab3)

## Run ledger
| Date | Role | Model/effort | Task | Outcome |
|---|---|---|---|---|
| 2026-10-31 | product-manager | sonnet/medium | Spec docs/specs/tv-home.md | done (4104108) |
| 2026-10-31 | panel x3 (devil's advocate, Brian's seat, platform) | sonnet/medium | Spec review | all Go with changes; cut scope to Projectivy + agenda app |
| 2026-10-31 | product-manager | sonnet/medium | Apply panel decisions to spec | done (60a606d) |
| 2026-10-31 | ux-designer | sonnet/medium | M1 agenda design | done (4397577); cheaper setting would do |
| 2026-10-31 | product-manager | sonnet/medium | Spec: add M1b, M1c, M1b-2 | done (f4cadff, 26c5d18, 7d04d4e) |
| 2026-10-31 | software-architect | opus/high | Architecture, ADRs 0001-0003, task lists | done (6ca3060) |
| 2026-10-31 | manager | - | Clickable mockup artifact | published |
| 2026-11-01 | qa-engineer | opus | T9 emulator job + fixes | done (e241e86..3054ab3), CI green |
| 2026-11-01 | security-engineer + code-reviewer | opus | T9 emulator diff review | request changes (1 medium) -> delta approve |
