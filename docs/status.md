# tv-home status

Feature: tv-home (custom Google TV home screen). Priority: Brian 2026-10-31 23:47Z: install the dashboard ASAP within usage (first claim on this thread).
Stage (2026-11-02 09:30Z): M2 row edit mode, hero, feeds and Blender art (PRs #9-#13) installed on Brian's TV 01:39Z.
PR #14 (PC on-screen remote, Ask opens listening) and PR #15 (M3 Home watch, tile a11y follow-ups) are green and
reviewed, waiting for Brian to merge, type "install", and run `scripts/tv-home-takeover.ps1 -TurnOn`. Next: the on-TV
checks in spec M3.1 (launcherx). The mic key cannot be intercepted, but the app holding the ASSISTANT role receives it: verified on the Streamer 2026-11-04 (set with adb, PR #31), so the mic button now opens Ask Claude.
Stage (2026-11-04 21:20Z): PR #33 (mic answers in a corner overlay over whatever is playing) and PR #34 (sun placed by real azimuth: rises left, peaks centre, sets right) are green and reviewed, waiting for Brian to merge and run Install TV Home.cmd.
Earlier stage (2026-11-01 20:05Z): M2 tiles (PR #3), M2b calendar tile + full screen (PR #4), M2c dock row (PR #5) and M1b lean Ask Claude (PR #6) all INSTALLED on Brian's Google TV. Brian provisioned a dedicated spend-limited API key; first live question worked (it recommended The Office). LG BX found at 192.168.68.80 (MAC 58:FD:B1:C2:7B:EE), PAIRED over wss 3001, volume tested 7->8->7; ws 3000 not needed. Unverified on device: full-screen event-row navigation, Up-restore to a scrolled app tile, Open on 2 services (M1b AC5), AC8-offline. Next (when Brian asks): M1b-2 ask during playback, M6 voice reskin, web search for Ask Claude.
Earlier stage (2026-11-01 15:25Z): M1 INSTALLED on Brian's Google TV (T11, his typed OK 15:04Z) and provisioned by Brian; agenda shows his calendar. Signed APK from run 41318532029, cert SHA-256 18:E2:7C:A1:DF:5A:EA:B8:4C:E4:65:8E:0E:A3:94:02:67:77:1F:71:DE:9C:4B:49:07:96:F2:7C:2C:4B:64:2A. adb (platform-tools) at %LOCALAPPDATA%\tv-home-tools on his PC. PS 5.1 signing fix merged (PR #2). Next (when usage allows): M2 launcher (Home button), AC8-offline check on the real TV, LG volume per card.
Earlier: Build M1 (calendar agenda). Core and T5 app code approved (18631fe on claude/project-thread-i5nn8b). T8 provisioning and T7 signing scripts approved (2dd6d92). Remaining M1: first CI run + T9 emulator QA after the Nov 1 minutes reset (reminder set for 01:00Z), PR to main, then Brian runs the signing script and T11 install (hard stop). M1c: Brian (21:38Z) said push ahead cheaply on Opus. C1 server, C4 Google TV, C3 LG built and approved (4eb785d, 200 tests). C2 waits on M1b B1 (services.json). C5 (Brian typed OK 22:56Z): installed on his PC (uv 0.11.32, 200 tests, MCP registered user scope). Google TV 192.168.68.71 paired; status/open/keys/idle-20s verified on screen by Brian. LG 192.168.68.68 (MAC 58:DB:09:A4:36:B8, unconfirmed) refuses wss 3001, only ws 3000 open: LG is OLED65BXPUA (2020), webOS 5.6.2-21; 3001 refused with screen on, 3000 open; not paired, no code changed. Turn on via Wi-Fi now on. Decision card to Brian 23:18Z: update LG firmware (rec) / allow unencrypted LG-only exception (needs security review) / skip LG. Deco reservations pending (Google TV uses a private MAC). M4 PC game streaming added to spec (later). Spec at 7d04d4e covers M1, M1b (Ask Claude + ask-while-playing), M1c (control from Claude). Architecture + ADRs 0001-0003 merged fb24a2a. Order: M1 -> M1c -> M1b (architect rec: M1c needs no API key or spending). Mockup shown to Brian: https://claude.ai/artifact/L8f5rSyjZGA46d5BuJEier

Stage (2026-11-14 03:45Z): Game-day scoreboard (Fable redesign, Brian approved the mockup 03:01Z): rink/field board, ticket-stub pick, full ticket. Spec docs/design/tv-home-game-day-scoreboard.md. Built on claude/project-thread-xgwfdw; app code first compiled by CI. Install is a hard stop, through the TV remote thread.

## Blockers
- None for the team. Platform facts from PR #15 CI (API 34): a HOME alias + `set-home-activity` loses to the stock
  launcher's priority-2 HOME filter; an accessibility key filter never receives HOME, SEARCH or ASSIST.

## Waiting on the CEO
- None. Installed 2026-11-05 20:09Z (main e541bea: voice overlay, sun fix, Open OK-twice, Continue tile). Asked Brian for an on-TV check of the Continue tile and Netflix Open; default: assume both work.

## Problem-key ledger
| Key | Returns | Source |
|---|---|---|
| android-repo-filter, app-silent-skip, wrapper-no-sha (open until CI), root-plugins-incomplete | 1 | T1 code review (fixed 03d34f5, approved) |
| instance-cap-starves-today | 1 | M1 core code review |
| floating-recurring-dst | 1 | M1 core code review |
| agenda-initial-focus, fullscreen-model-clock, agenda-focus-lost-on-refresh, tile-old-cache, qa-vacuous-negative | 1 | M2b code review (fixed 9531fa9) |
| home-back-twice | 1 | M2b CI emulator (fixed 0a17393; guarded in M2c and M1b QA) |
| qa-back-once-app-row, qa-m2c-checks, dock-preview-steal, dock-up-restore | 1 | M2c code review (fixed 60993d8) |
| ask-http-status-copy, ask-keystore-main-thread, ask-deeplink-browser, ask-max-tokens-spend | 1 | M1b security review (fixed e1268c2) |
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
| offline-not-really-off, qa-silent-degrade | 1 | T9 review (fixed 3054ab3; AC8-offline NOT VERIFIED on emulator) |
| blocking-in-suspend, fetcher-customize-loosens-tls, redactor-over-redacts, pii-in-data-class-tostring | 1 | M1 core reviews |

| home-recovery-unreachable | 3 | PR #15 security (key filter, hold-Home, Home watch); escape defined as a tested policy (HomeRedirect in :core: double press + crash-loop pause), approved |
| home-redirect-crash-loop | 2 | PR #15 security, then code review (limit unreachable); fixed with init guard + tests |
| home-dead-on-start-failure, a11y-list-regex-too-strict, rollback-file-not-per-serial, home-set-stale, a11y-event-breadth, m3-staying-reset-overlay, m3-boot-claim, m3-launcher-unverified (open: on-TV) | 1 | PR #15 reviews |

- Nov 4: ok-on-card-noop, open-cause-unproven, answer-fade, open-failure-silent, trampoline-component-pkg: 1 return each (PR #36 reviews, fixed 35f220c)

- Nov 5: continue-late-insert, continue-stale-pick, continue-hidden-app, usage-op-regrant, usage-op-undo-not-sticky, continue-pick-self-usage: 1 return each (PR #39; pick ones superseded when Brian chose last-app-only)

- Nov 14: design-canvas/dialog-density, snow-redraw-scope/board-draw-allocs, safe-area/board-shadow, stale-doc/pick-short: 1 return each (PR #47 code review)

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
| 2026-11-01 | devops/backend | opus | Strict dep verification, AGP classloader, CEC keys | done, CI green |
| 2026-11-01 | qa-engineer | opus | T9 emulator job + fixes | done (e241e86..3054ab3), CI green |
| 2026-11-01 | security-engineer + code-reviewer | opus | T9 emulator diff review | request changes (1 medium) -> delta approve |
| 2026-11-01 | software-architect | opus | LG volume options writeup | done (4e756c9) |
| 2026-11-01 | security-engineer | opus | PS 5.1 signing fix delta | approve |
| 2026-11-01 | manager + RC on Brian's PC | - | T11 install (adb), cert verified | installed, provisioned by Brian |
| 2026-11-01 | frontend-engineer | opus | M2b calendar tile + full screen | done; review request changes (agenda-initial-focus) -> delta approve; CI caught home-back-twice, fixed |
| 2026-11-01 | code-reviewer | opus | M2b review + delta | request changes -> approve |
| 2026-11-01 | frontend-engineer | opus | M2c dock row | done; 4 nits fixed |
| 2026-11-01 | code-reviewer | opus | M2c review + delta | approve with nits -> approve |
| 2026-11-01 | frontend-engineer | opus | M1b lean Ask Claude | done; 4 fixes after review |
| 2026-11-01 | security-engineer + code-reviewer | opus | M1b review + delta | approve (1 medium fixed) -> approve |
| 2026-11-01 | manager + RC on Brian's PC | - | M2/M2b/M2c installs, LG pairing | installed; M1b adb install blocked by PC classifier, Brian ran it; LG paired |
| 2026-11-02 | security-engineer | opus | PR #15 M3 key filter | request changes (home-recovery-unreachable high) -> approve |
| 2026-11-02 | code-reviewer | opus | PR #15 M3 key filter | request changes; then superseded (key filter never receives HOME) `waste:superseded` |
| 2026-11-02 | security-engineer | opus | PR #15 Home watch fresh + 2 deltas | request changes -> request changes (CI timing) -> approve |
| 2026-11-02 | code-reviewer | opus | PR #15 Home watch fresh + delta | request changes (burst limit unreachable) -> approve with nits |
| 2026-11-04 | frontend-engineer | opus/medium | PR #33 voice overlay + CI fixes | done (e802a79, emulator overlay pass) |
| 2026-11-04 | code-reviewer | opus | PR #33 voice overlay | approve (4e978eb) |
| 2026-11-04 | security-engineer | opus | PR #33 fresh + delta 4e978eb..e802a79 | OK, OK |
| 2026-11-04 | frontend-engineer | opus/medium | PR #34 sun position root cause + fix | done (7750f92, 717 core tests) |
| 2026-11-04 | code-reviewer | sonnet/medium | PR #34 sun arc | approve with nits (skynow-default-trap, fixed) |
| 2026-11-04 | frontend-engineer | opus/medium | PR #36 Open button fix (trampoline, then OK-twice) | done (35f220c, emulator pass) |
| 2026-11-04 | code-reviewer | opus | PR #36 fresh + delta | request changes (ok-on-card-noop, open-cause-unproven) -> approve with nits |
| 2026-11-04 | security-engineer | opus | PR #36 fresh + delta | OK with 2 lows -> OK |
| 2026-11-05 | frontend-engineer | opus/medium | Watch Next / UsageStats feasibility probe (PR #38 closed) | done: Watch Next launcher-only; UsageStats via appop |
| 2026-11-05 | frontend-engineer | opus/medium | PR #39 Continue tile + 3 fix rounds | done (697a141); pick feature `waste:superseded` by Brian's pick |
| 2026-11-05 | code-reviewer | opus | PR #39 fresh + 3 deltas | request changes x2 -> approve |
| 2026-11-05 | security-engineer | opus | PR #39 fresh x2 + 2 deltas | OK with lows/1 medium (fixed) -> OK |
| 2026-11-14 | frontend-engineer | fable/default | Game-day scoreboard build | done (523e9a4): core 791 tests pass; app uncompiled locally |
| 2026-11-14 | code-reviewer | opus/high | PR #47 fresh review | request changes: 1 must-fix (design-canvas/dialog-density), 1 should-fix, 2 nits |
| 2026-11-14 | frontend-engineer | fable/default | PR #47 review fixes (4 findings) | done (ac1c8f7) |
| 2026-11-14 | frontend-engineer | fable/default | PR #47 polish from real emulator frames (cones, meta, stub overlap) | done (b08d11d), core 793 tests pass |
