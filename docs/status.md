# tv-home status

Feature: tv-home (custom Google TV home screen). Priority: hip pocket (idle time only; Quick Invoice first).
Stage: Build M1 (calendar agenda). Spec at 7d04d4e covers M1, M1b (Ask Claude + ask-while-playing), M1c (control from Claude). Architecture + ADRs 0001-0003 merged fb24a2a. Order: M1 -> M1c -> M1b (architect rec: M1c needs no API key or spending). Mockup shown to Brian: https://claude.ai/artifact/L8f5rSyjZGA46d5BuJEier

## Blockers
- No Android SDK in the cloud sandbox (dl.google.com blocked). APKs build in GitHub Actions; private-repo minutes reset 2026-11-01 00:00Z.

## Waiting on the CEO
- Try Projectivy Launcher, report what's missing (Just the calendar (rec) / It covers everything / More than that / Can't try it now) | Play Store on the TV | install, use a day, tap option | asked 2026-10-31 in thread "Customize Brian's LG OLED TV software" (also on Asks inbox) | default: build the calendar agenda app (M1)

## Problem-key ledger
(none yet)

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
