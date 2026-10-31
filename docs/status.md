# tv-home status

Feature: tv-home (custom Google TV home screen). Priority: hip pocket (idle time only; Quick Invoice first).
Stage: Build M1 (calendar agenda), T2b re-plan in progress. Spec at 7d04d4e covers M1, M1b (Ask Claude + ask-while-playing), M1c (control from Claude). Architecture + ADRs 0001-0003 merged fb24a2a. Order: M1 -> M1c -> M1b (architect rec: M1c needs no API key or spending). Mockup shown to Brian: https://claude.ai/artifact/L8f5rSyjZGA46d5BuJEier

## Blockers
- No Android SDK in the cloud sandbox (dl.google.com blocked). APKs build in GitHub Actions; private-repo minutes reset 2026-11-01 00:00Z.

## Waiting on the CEO
- None open. (Projectivy ask answered 2026-10-31: "More than that", so M2 our own launcher is unconditional.)

## Problem-key ledger
| Key | Returns | Source |
|---|---|---|
| android-repo-filter, app-silent-skip, wrapper-no-sha (open until CI), root-plugins-incomplete | 1 | T1 code review (fixed 03d34f5, approved) |
| instance-cap-starves-today | 1 | M1 core code review |
| floating-recurring-dst | 1 | M1 core code review |
| one-bad-event-fails-feed | 1 | M1 core code review |
| rrule-advance-cpu / ics-parse-cpu-memory-dos | 3 | code + security review rounds 1-2; architect re-plan done (ADR 0004, strict reader); T2b.3 is a fresh review |
| partial-result-replaces-good, cancel-returns-network-error, vtimezone-bare-cr, vcal1-daylight | 1 | round 2 reviews (folded into T2b) |
| session-ok-never-ages, no-fsync-before-rename, cache-write-throws, emphasis-boundary-60 | 1 | T4 review (fixed) |
| lenient-verification-with-signing, scan-misses-nested, logs-before-scan | 1 | T6 review (fixed) |
| old-dtstart-recurrence-missing | 1 | security review |
| blocking-in-suspend, fetcher-customize-loosens-tls, redactor-over-redacts, pii-in-data-class-tostring | 1 | M1 core reviews |

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
| 2026-10-31 | security-engineer | opus/high | Design review | approve with changes, 1 high + 4 medium (applied dc6db29) |
| 2026-10-31 | devops-engineer | sonnet/medium | T1 skeleton + fixes | request changes -> approve with nits |
| 2026-10-31 | code-reviewer | sonnet/medium | T1 review + delta | request changes (1 must-fix) -> approve |
| 2026-10-31 | backend-engineer | sonnet/medium | T2 ICS parser | done, 33 tests |
| 2026-10-31 | backend-engineer | sonnet/high | T3 fetch/redaction + integration | done, 66 tests (36f4abf) |
| 2026-10-31 | code-reviewer | opus/high | M1 core T2+T3 | request changes, 2 must-fix |
| 2026-10-31 | security-engineer | opus/high | M1 core T2+T3 | approve with changes, 1 medium |
| 2026-10-31 | backend-engineer | sonnet/medium | T4 cache/state | done, 80 tests (65f1f28) |
| 2026-10-31 | devops-engineer | sonnet/high | T6 CI workflow | done (6020cbe), unrun until Nov 1 |
| 2026-10-31 | backend-engineer | opus/high | Fix M1 core findings (escalated after bounce) | done (b255e88, own expander, 0/6000 dateutil mismatches) |
| 2026-10-31 | code-reviewer + security-engineer | opus/high | Round 2 delta on T2+T3 | request changes (3rd return of ics-parse-cpu-memory-dos) |
| 2026-10-31 | software-architect | opus/high | Re-plan bounded parse (ADR 0004, T2b tasks) | done |
| 2026-10-31 | backend-engineer | opus/high | T2b.1 strict ICS reader | running |
| 2026-10-31 | backend-engineer | sonnet/high | T2b.2 fetch/source/state/AgendaRefresher | running |
