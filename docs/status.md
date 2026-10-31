# tv-home status

Feature: tv-home (custom Google TV home screen). Priority: hip pocket (idle time only; Quick Invoice first).
Stage: Build M1 (calendar agenda). Core and T5 app code approved (18631fe on claude/project-thread-i5nn8b). T8 provisioning and T7 signing scripts approved (2dd6d92). Remaining M1: first CI run + T9 emulator QA after the Nov 1 minutes reset (reminder set for 01:00Z), PR to main, then Brian runs the signing script and T11 install (hard stop). M1c: Brian (21:38Z) said push ahead cheaply on Opus. C1 server, C4 Google TV, C3 LG built and approved (4eb785d, 200 tests). C2 waits on M1b B1 (services.json). C5 setup on Brian's PC needs his OK (install = hard stop). M4 PC game streaming added to spec (later). Spec at 7d04d4e covers M1, M1b (Ask Claude + ask-while-playing), M1c (control from Claude). Architecture + ADRs 0001-0003 merged fb24a2a. Order: M1 -> M1c -> M1b (architect rec: M1c needs no API key or spending). Mockup shown to Brian: https://claude.ai/artifact/L8f5rSyjZGA46d5BuJEier

## Blockers
- Usage pacing (coordinator, 2026-10-31 15:25Z): weekly limit at 79%. After the two running T5 reviews, run one subagent at a time and no optional panels until Brian answers the pacing card.
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
| rrule-advance-cpu / ics-parse-cpu-memory-dos | 3 | code + security review rounds 1-2; architect re-plan done (ADR 0004); re-plan failure 1 of 2 at T2b.3 (uncharged setup work); fixed and approved at f95bec0 (two-pass removals) |
| provision-fallback-argv, provision-stale-success | 1 | T8 security review (fixed) |
| p12-keypass-ignored, ps51-native-stderr, remote-overwrite | 1 | T7 security review (fixed) |
| stdin-bom | 2 | T7 reviews (fixed f824a04) |
| doc-steps | 3 | T7 reviews (minor doc wording; closed by manager 2dd6d92) |
| instance-cap-removal-order, count-walk-skips-active-series, exdate-uncharged, fetch-redirect-hops-unpinned, p14-perf-flaky, expander-no-differential-test | 1 | T2b.3 reviews (docs/reviews/m1-t2b-reviews.md) |
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
| 2026-10-31 | backend-engineer | opus/high | T2b.1 strict ICS reader + merge of T2b.2 | done (d8cdb32, 166 + 34 hostile tests) |
| 2026-10-31 | backend-engineer | sonnet/high | T2b.2 fetch/source/state/AgendaRefresher | done (310ba73, 144 tests) |
| 2026-10-31 | code-reviewer | opus/high | T2b.3 fresh review | request changes, 1 must-fix (ics-rule-setup-uncharged) |
| 2026-10-31 | security-engineer | opus/high | T2b.3 fresh review | request changes, 1 high (instance-cap-removal-order) |
| 2026-10-31 | backend-engineer | opus/high | Fix T2b findings (2 rounds) | done (f9d21dc, f95bec0; 167 + 44 hostile tests) |
| 2026-10-31 | code-reviewer | opus/high | T2b deltas x2 | approve with nits -> approve |
| 2026-10-31 | security-engineer | opus/high | T2b deltas x2 | approve with changes -> approve |
| 2026-10-31 | devops-engineer | sonnet/low | T2b.6 CI hostile + live job | done (ec37129; manager-reviewed 26-line diff) |
| 2026-10-31 | frontend-engineer | sonnet/high | T5 :app agenda screen | done (1c81bc8, 238 core tests; :app uncompiled until CI) |
| 2026-10-31 | ux-designer (manager applied default) | - | T2b.4 stale copy | default text applied (85f0785), mapping corrected in T5 |
| 2026-10-31 | security-engineer | opus/high | T5 review | approve with changes (1 medium: crash pre-handler) |
| 2026-10-31 | code-reviewer | opus/high | T5 review | request changes (ci-core-includes-app) |
| 2026-10-31 | frontend-engineer | sonnet/high | T5 fixes | done (5d0fc33, 240 tests) |
| 2026-10-31 | code-reviewer | opus/high | T5 fix review (fresh, CI) | approve with nits |
| 2026-10-31 | devops-engineer | sonnet/medium | T8 provisioning scripts + 2 fix rounds + pwsh CI check | done (7b7275d) |
| 2026-10-31 | security-engineer | opus/high | T8 review + delta | approve with changes (provision-fallback-argv) -> approve |
| 2026-10-31 | devops-engineer | sonnet/medium | T7 signing script + 2 fix rounds | done (f824a04) |
| 2026-10-31 | backend-engineer | opus/medium | M1c C1, C4, C3 + fix rounds | done (4eb785d, 200 tests) |
| 2026-10-31 | code+security reviewer | opus/high | M1c C1, C4, C3 reviews + fix reviews | C1 request changes -> approve w/ nits; C4 request changes (atv-stale-connection) -> approve w/ nits; C3 approve w/ nits -> approve |
| 2026-10-31 | security-engineer | opus/high | T7 review + 2 fresh re-reviews | request changes (p12-keypass-ignored) -> approve with changes (stdin-bom) -> approve |
