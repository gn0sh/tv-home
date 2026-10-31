# Tasks: tv-home M1 (calendar agenda app)

Architecture: `docs/architecture/tv-home.md` sections 2-6. ADR 0001. ACs: spec `docs/specs/tv-home.md` M1 (AC1-AC8).
Where: **sandbox** = runs fully in our cloud sandbox (plain Gradle, JDK 21); **CI** = needs GitHub Actions (Android
SDK; minutes from 2026-11-01 00:00Z; repo must exist); **Brian** = needs his PC or TV.
Security findings (`docs/reviews/security-tv-home-design.md`) are tagged `#n` on the task that owns them; each is an
acceptance check for that task's review.
Sandbox Gradle tip: Maven Central sometimes returns 429; re-run the build (the cache keeps what it got).

| # | Task | Owner | Where | Depends | Files | ACs |
|---|---|---|---|---|---|---|
| T1 | Gradle skeleton: wrapper (8.14.x), `settings.gradle.kts` with conditional `:app`, `gradle/libs.versions.toml` (Kotlin 2.0.21, biweekly 0.6.8, OkHttp 4.12.0, kotlinx-serialization, JUnit 5, MockWebServer), `:core` with JVM target 17 (no toolchain block), `.gitignore`. `#8`: wrapper `distributionSha256Sum`, dependency locking, `gradle/verification-metadata.xml`. `./gradlew :core:test` green in sandbox | devops-engineer | sandbox | none | `settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `core/build.gradle.kts` | none (enables all) |
| T2 | *(Done; its parser and limits are replaced by T2b, ADR 0004.)* ICS parsing and windowing: `IcsParser` (biweekly text parser only, never `parseXml`; at most 500 expanded instances), RECURRENCE-ID overrides, STATUS:CANCELLED, iteration cap, `buildAgenda` with injected clock and zone. Fixtures: weekly RRULE, EXDATE, moved instance (RECURRENCE-ID), cancelled instance, UTC, TZID in another zone, floating time, all-day single and multi-day, DST change week, event across midnight, empty day | backend-engineer | sandbox | T1 | `core/src/main/kotlin/.../calendar/`, `.../agenda/`, `core/src/test/resources/ics/*.ics` | AC1, AC2 |
| T3 | Fetch and redaction: `IcsFetcher` (OkHttp: https-only check before I/O, timeouts, no https→http redirect, 10 MB cap), `IcsCalendarSource`, `SourceError` mapping with a `Throwable` catch at the source boundary (`#3`), `Redactor`, `SecretStore` interface + in-memory fake, provisioning validation with the `#3` regexes (trim first). All fixtures use one fixed fake secret (`#9`). Tests with MockWebServer (TLS), including an error whose OkHttp message contains the URL; property-style test (seeded random secrets): no `SourceError`, `AgendaState` or redacted string contains the secret | backend-engineer | sandbox | T1 | `core/.../calendar/`, `core/.../secrets/` | AC6, AC7 (HTTPS) |
| T4 | Cache and state: `CachedAgenda` JSON v1 (atomic write through an injected file API), `reduce()` for Ok / Stale (age, emphasis > 60 min) / NotSetUp / CantReach / Loading, `RefreshPolicy` (on start, every 15 min while visible, offline skips fetch). Table-driven tests with a fake clock, including a cache from yesterday and a cache older than the window | backend-engineer | sandbox | T2, T3 | `core/.../state/` | AC3, AC4, AC8 (logic) |
| T2b | **Bounded ICS parse** (re-plan of `ics-parse-cpu-memory-dos`; ADR 0004, architecture section 3a). Sub-tasks, acceptance tests and probe fixtures: section T2b below | backend-engineer (T2b.1, T2b.2), devops-engineer (T2b.6), ux-designer (T2b.4), product-manager (T2b.5) | sandbox (T2b.6: CI) | T2, T3, T4 merged | `core/.../calendar/`, `core/.../state/`, `core/build.gradle.kts`, `.gitattributes`, `.github/workflows/android.yml` | AC1, AC2, AC3, AC4 |
| T5 | Android app: `:app` module (minSdk 31, target 34), Compose for TV agenda screen for every `AgendaState` (Stale renders its `cause`, T2b.4 copy), 10-ft type sizes, lifecycle refresh through `AgendaRefresher` only (never writes the cache itself; ON_START and 15-min TICK call `refresh()`, ON_STOP cancels; cache read on IO), `KeystoreSecretStore`, `ProvisionActivity` and provision `ContentProvider` guarded by `DUMP` (no intent-filter, `excludeFromRecents`, `noHistory`, `setIntent(Intent())` after reading, `finishAndRemoveTask()`, regex re-check; `#10`), release uncaught-exception handler logging class names only (`#3`), manifest hardening (allowBackup=false, backup rules, no cleartext, INTERNET + ACCESS_NETWORK_STATE only), banner + LEANBACK_LAUNCHER, R8 rules stripping `Log`. Written in sandbox, first compiled in CI | frontend-engineer | written in sandbox, compiles in CI | T4 (contract only; can start from section 3 of the architecture in parallel with T2-T4) | `app/` | AC4 (render), AC5, AC6, AC7 |
| T6 | CI: `.github/workflows/android.yml` (core tests; lint + assembleRelease; signing only on push to main from secrets decoded to `$RUNNER_TEMP` and deleted; APK artifact 7 days; `aapt`/`apkanalyzer` checks for debuggable, allowBackup and QUERY_ALL_PACKAGES; grep of log and APK for `private-[0-9a-f]` and `sk-ant-`), manual emulator job (Android TV API 34). Actions pinned by SHA. `#9`: `permissions: contents: read`, `persist-credentials: false`, no `pull_request_target` / `workflow_run`, passwords via env not `-P`, no `--scan`, APK grep unzips and scans dex + resources, residual risk (any workflow on main can read signing secrets) recorded; `#8`: wrapper-validation action | devops-engineer | written in sandbox, runs in CI | T1; repo exists | `.github/workflows/android.yml` | AC6, AC7 |
| T7 | Signing key (`#1`): devops-engineer writes `scripts/new-signing-key.ps1` (passwords generated in-process, piped to `keytool -storepass:env` and `gh secret set` stdin, prints only "done" + cert fingerprint, stops if `gh` is not signed in); **Brian runs it himself** in his own PowerShell window from a copy-paste command. No agent runs it or reads `%USERPROFILE%\tv-home-signing\`; no sandbox fallback. Record where the backup is | devops-engineer (script), Brian (runs it) | sandbox (script), Brian | T6; repo exists | `docs/release/tv-home-signing.md` (no secrets) | AC7 |
| T8 | Provisioning scripts (`#1`, `#3`, `#10`): `scripts/provision.ps1` (hidden prompt, refuses non-interactive stdin, trim + `#3` regex, stdin to `content write` with `am start` fallback and device-shell quoting, `clear`, prints no value; after saving the calendar URL, downloads the feed once over HTTPS and prints counts only, never content: size in KB, VEVENT count, RRULE count, and "within app limits: yes/no" against 10 MiB / 50,000 / 5,000 (ADR 0004)), `scripts/provision.sh` for the emulator; README steps for Brian | devops-engineer | sandbox (script); emulator in CI | T5 | `scripts/`, `docs/user/tv-home-setup.md` | AC6 |
| T9 | QA on the emulator: D-pad/Back walk (no focus trap), `adb shell am start -W` cold start with network off (cache and no-cache), provision a dummy URL then grep captured logcat (incl. `System.err`, `AndroidRuntime`), `dumpsys dropbox --print data_app_crash` after a forced crash, CI log and unzipped APK (`#3`); confirm `DUMP` guard (shell can use it, a test app cannot); confirm `content write` over stdin works (else fallback); check ProvisionActivity leaves no recents entry and no retained extras (`#10`) | qa-engineer | CI (emulator job) | T5, T6, T8 | `docs/qa/tv-home-m1.md` | AC5, AC6, AC8 |
| T10 | Reviews: code-reviewer per task; security-engineer on T3, T5, T6, T8 (secrets, CI, signing) and on biweekly/OkHttp and each third-party Action | code-reviewer, security-engineer | sandbox | each task | report text to manager | all |
| T11 | **Hard stop:** install on Brian's TV (sideload once: Developer options, adb from PC), then **he runs `provision.ps1` himself** with his ICS URL (his typed OK). The ask gives the copy-paste command and never asks for the URL; if the script fails he reports the error text, never the value (`#1`) | manager asks Brian | Brian | T9 passed, signed APK | `docs/status.md` | AC6 |

Execution order: T1 → (T2, T3 in parallel; T5 can start against the contract) → T4 → T6 (as soon as the repo
exists) → **T2b** (T2b.1 and T2b.2 in parallel, then T2b.3 reviews; T2b.4-T2b.6 alongside) → T8 → T7 → T9 → T11.
T5 may be written against the section 3a contract meanwhile. T10 runs after each task. Everything up to T5's code is
doable before CI minutes reset.

## T2b: bounded ICS parse (re-plan of `ics-parse-cpu-memory-dos`)
Read: ADR 0004 (all), architecture section 3a (all), section 3 "Rules" (Recurrence, Parsing limits, Fetch, Cache).
Problem keys this closes: `ics-parse-cpu-memory-dos`, `ics-parse-deadline-excludes-biweekly`,
`deadline-drops-recurring-silently`, `cancel-returns-network-error`, `unsupported-rule-vanishes`,
`allday-until-utc-frame`, `one-bad-event-fails-feed` (EXDATE residual). Tag every new test with the key it proves.

| # | Sub-task | Owner (suggested model) | Depends | Files (owned) |
|---|---|---|---|---|
| T2b.1 | Parser: `IcsReader` (strict framing, whitelist, line caps, per-VEVENT caps, counts, deadline every 64 KiB), own RRULE parser into domain sets, `ParseOutcome`/`ParseStats`/`ParseLimit`/`RejectReason`, `IcsParse` takes `ByteArray`, `RecurrenceExpander` static per-period bound `B(rule)` charged before work (plus a test-only iteration counter), steps 1M / 20k per series, deadline 5 s from entry, `ZoneResolver` without exceptions (set membership, last 4 suffixes, memo of at most 256), `Collector` as a bounded heap. Delete `IcsPrepass`. Move biweekly to `testImplementation`; relock; regenerate verification metadata; `.gitattributes` with `*.ics -text`. A `// bound:` comment on every loop in `calendar/` | backend-engineer (opus/high: security-sensitive, third return) | none | `calendar/IcsReader.kt` (new), `IcsParser.kt`, `RecurrenceExpander.kt`, `ZoneResolver.kt`, `ParseOutcome.kt` (new), `core/build.gradle.kts`, lockfile, `gradle/verification-metadata.xml`, `.gitattributes`, parser tests |
| T2b.2 | Fetch, source, state: `IcsFetchResult.Body(bytes)`, final-host check, `ensureActive()` before every boundary mapping (fetcher and source), URL re-validation in `IcsCalendarSource`, outcome mapping, `SourceError.PARSE_LIMIT`, `StaleCause` in `reduce()`, `AgendaRefresher` (new), cache read cap 1 MiB | backend-engineer (sonnet/medium; spec is now exact) | section 3a contract only (parallel with T2b.1; stub `IcsParse` in tests) | `IcsFetcher.kt`, `IcsCalendarSource.kt`, `CalendarSource.kt`, `state/AgendaState.kt`, `state/AgendaRefresher.kt` (new), `state/CachedAgenda.kt`, their tests |
| T2b.3 | Reviews: **fresh, independent** security-engineer review (opus/high) and code-reviewer (opus/high) on the merged T2b.1+T2b.2 SHA, not deltas. Brief both with ADR 0004's hole table and ask them to attack the `// bound:` claims and the whitelist | security-engineer, code-reviewer | T2b.1, T2b.2 merged | report text to manager |
| T2b.4 | Copy for Stale with cause UNREADABLE (proposed footer "Calendar data unreadable. Showing the last copy."); confirm or replace in `docs/design/tv-home-m1-agenda.md` states table. Default if not done: the proposed text | ux-designer (sonnet/low) | none | `docs/design/tv-home-m1-agenda.md` |
| T2b.5 | Amend the spec's "Technical constraints" line: HTTP via a maintained client; ICS via our strict reader for Google's subset, biweekly as test oracle (ADR 0004). One line; the manager may apply it directly | product-manager (haiku/low) | none | `docs/specs/tv-home.md` |
| T2b.6 | CI: add `:core:hostileTest` to job 1 (`core tests`) after `:core:test`. In the manual `workflow_dispatch` job, add a live check: fetch the public Google US-holiday feed (`https://calendar.google.com/calendar/ical/en.usa%23holiday%40group.v.calendar.google.com/public/basic.ics`, no secret) and run `GoogleFramingLiveTest` (tag `live`, excluded from `test`), which asserts `Complete` and records CRLF, VERSION and the component set. Must pass before T11 | devops-engineer (sonnet/low), backend-engineer writes the test in T2b.1 | T2b.1 | `.github/workflows/android.yml` |

### Acceptance tests (definition of done for T2b.1 + T2b.2)
Harness: hostile probes live in `HostileFeedsTest`, tagged `hostile`, built by a deterministic generator
(`HostileFeeds.kt`, ByteArray output, nothing over 64 KB committed), and run by a separate Gradle `Test` task
`:core:hostileTest` with `maxHeapSize = "96m"` (raise only with a measured reason in the PR). `:core:test` excludes
the tag. Every probe runs with a **frozen clock** (`nanoTime = { 0L }`) inside `assertTimeoutPreemptively(1 s)`,
which proves the deterministic limits bind, not the deadline. Window: `now = 2026-03-10T14:00Z`, zone
`America/New_York` (as in `IcsParserFindingsTest`). "DTSTART only" means the series yields its DTSTART instance and
`unsupportedRules` counts it.

| Probe | Source | Construction | Expected outcome |
|---|---|---|---|
| P1 | security r1 #1 | 335-byte VTIMEZONE, STANDARD with `RRULE:FREQ=SECONDLY` from 1900; one VEVENT `TZID=Evil` 2026-03-10 09:00 | `Complete`, 1 event at 13:00Z (unknown TZID: device zone) |
| P2 | security r1 #1 | `SECONDLY;COUNT=2000000000` from 1970 | `Complete`, 0 events, `unsupportedRules=1` |
| P3 | security r1 #1 | 20,000 SECONDLY events, UNTIL just before the window | `Complete`, 0 events, `unsupportedRules=20000` |
| P4 | code r1 `rrule-advance-cpu` | 1,000 SECONDLY and 1,000 MINUTELY rules with BYSETPOS from 1970 | `Complete`, 0 events, `unsupportedRules=2000` |
| P5 | existing test | MINUTELY and `HOURLY;BYSETPOS=1`, DTSTART in the window | `Complete`, DTSTART only (2 instances) |
| P6 | security r2 #1 | YEARLY, 20 KB RRULE: BYMONTHDAY x3000, BYDAY=1MO x3000 | `Complete`, DTSTART only (line over 1 KiB) |
| P7 | security r2 #1, within caps | YEARLY from 1900, `COUNT=1000000`, BYMONTH 1-12, all 62 BYMONTHDAY values, as many distinct BYDAY ordinals as fit 1 KiB, `BYSETPOS=1,-1`. (a) one series; (b) 100 copies, distinct UIDs | (a) `Complete`, `skippedSeries=1`; (b) `Exhausted(STEPS)` |
| P8 | security r2 #1 | BYDAY with 1,000,000 entries (about 4 MB line) | `Complete`, DTSTART only; heap holds |
| P9 | security r2 #2 | the 305-byte bare-CR feed hiding a SECONDLY VTIMEZONE | `Rejected(LINE_ENDING)` |
| P10 | security r2 #2 | 60,000 VEVENTs separated by bare CR | `Rejected(LINE_ENDING)` |
| P11 | new | a valid feed with LF-only line ends | `Rejected(LINE_ENDING)` |
| P12 | security r2 #3 | `VERSION:1.0` with DAYLIGHT/TZ properties | `Rejected(VERSION)` |
| P13 | security r2 #3 | `VERSION:2.0` plus the same DAYLIGHT/TZ lines | `Complete`; those lines ignored; times as without them |
| P14 | security r2 #4 | 9 MB Google-shaped feed: 15,000 VEVENTs, 300 recurring series, long DESCRIPTIONs, VALARMs, VTIMEZONEs | `Complete`, events equal the expected list; also a perf test: under 500 ms on the first call in a fresh test JVM |
| P15 | security r2 holding | one series with 2,000 EXDATEs; same with 2,001 | `Complete` and correct; with 2,001, `skippedEvents=1`, other events intact |
| P16 | security r2 holding | 600,000 RDATE values: (a) one line, (b) separate lines | `Complete`; that VEVENT skipped (line over 8 KiB, or over 2,000 values) |
| P17 | security r2 holding | 9 MB line as (a) DESCRIPTION, (b) SUMMARY, (c) DTSTART | (a) event kept; (b) title is the first 200 chars; (c) VEVENT skipped. No line buffer over 8 KiB |
| P18 | security r2 holding | 200,000 nested `BEGIN:X-A` | `Rejected(NESTING)` at depth 5 |
| P19 | security r2 holding | 1,000,000 BYSETPOS values | `Complete`, DTSTART only |
| P20 | security r2 holding | VTIMEZONE inside an X- component | `Complete`; nothing interpreted |
| P21 | security r2 holding | SECONDLY over CRLF | `Complete`, DTSTART only |
| P22 | existing test | VTIMEZONE hidden 4 ways: lower case; folded `BEGIN:VTIME` / ` ZONE`; `x.BEGIN:VTIMEZONE`; `BEGIN;X=1:VTimeZone` | first two `Complete` (skipped); last two `Rejected(STRUCTURE)`. Never interpreted |
| P23 | existing test (expectation changes) | 3,000 `DAILY;BYDAY=MO;COUNT=1000000000` from year 1, plus one real one-off | `Exhausted(STEPS)`; refresher keeps the earlier cache (R2) |
| P24 | existing test (expectation changes) | DAILY series whose instances last 100 years | `Complete`, `skippedSeries=1`, 0 instances from it |
| P25 | existing test | BYHOUR x BYMINUTE x BYSECOND = 86,400 | `Complete`, DTSTART only |
| P26 | VEVENT cap | 50,001 minimal VEVENTs; 50,000 | `Exhausted(VEVENTS)`; `Complete` |
| P27 | series cap | 5,001 kept weekly masters; 5,000 | `Exhausted(SERIES)`; `Complete` |
| P28 | H8 | 50,000 VEVENTs, each `DTSTART;TZID=` a distinct 128-char, 64-segment id | `Complete`, all in the device zone |
| P29 | H9 | `YEARLY;BYMONTH=13` | DTSTART only, never every month |
| P30 | framing | two VCALENDARs; text after END:VCALENDAR; missing END; `END:VEVENT` closing a VALARM | `Rejected(STRUCTURE)` each |
| P31 | framing | no VERSION; VERSION after the first VEVENT; `CALSCALE:HEBREW` | `Rejected(VERSION)`, `Rejected(VERSION)`, `Rejected(CALSCALE)` |
| P32 | fuzz | 2,000 seeded mutations of the P14 shape (byte flips, line duplication, truncation, CR/LF injection, huge numbers, random RRULE parts) | each returns an outcome (never throws), under 200 ms each |

Deadline, cancellation and refresh (in `:core:test`, fake clock or fake source):
- D1 `deadline-drops-recurring-silently`: fake `nanoTime` crosses the deadline (a) while tokenizing a 10 MiB feed,
  (b) during expansion of a feed with one-offs and recurring series. Both give `Exhausted(DEADLINE)`, never a
  `Complete`. In (a) the clock is read at least once per 64 KiB before the crossing.
- D2 interrupt during tokenizing and during expansion: the parsing thread ends within 100 ms.
- R1: after a saved `Ok`, `refresh()` returning `Failed(PARSE_LIMIT)`, `Failed(PARSE)` or `Failed(TOO_LARGE)` leaves
  the cache file byte-identical; `reduce()` gives `Stale` with cause UNREADABLE, emphasised from 60:00; with no cache
  it gives `Unreadable`.
- R2: the P23 body through the real `IcsCalendarSource` and `AgendaRefresher` after an earlier good feed: the cache is
  unchanged, and the old events are still shown (Stale).
- R3: two concurrent `refresh()` calls make one fetch; the second returns null.
- C1 `cancel-returns-network-error`: `IcsFetcher` against MockWebServer with a throttled body; cancelling the caller
  mid-body makes `fetch` itself end with `CancellationException` (asserted on `fetch`'s own result, not a wrapper),
  never `Error(NETWORK)`. The same for a cancel during connect.
- C2: `IcsCalendarSource` cancel during download and during parse: `CancellationException`. C3: `AgendaRefresher`
  cancel mid-refresh: cache untouched, the next `refresh()` works.
- H11: a stored URL that fails `validateCalendarUrl` gives `NOT_CONFIGURED` with no request made; a final response
  from a non-Google host gives `HTTP_STATUS`.

Invariants and correctness (in `:core:test`):
- I1: anything `Complete` returns round-trips through `CachedAgenda` encode/decode unchanged (seeded instances at the
  field caps, with surrogate pairs, quotes, backslashes, C0 controls in the source).
- I2: for every rule in the 6,000-rule oracle corpus plus the P7 rules, real helper iterations per period never
  exceed the charged `B(rule)` (test-only counter).
- I3 differential oracle: for every non-hostile fixture and 500 seeded Google-shaped feeds, `IcsReader`'s records
  (uid, summary, dtstart, dtend or duration, RRULE parts, EXDATE, RDATE, RECURRENCE-ID, STATUS) equal biweekly's
  parse of the same text. All existing `IcsParserTest`, `IcsParserFindingsTest` (except P22-P24, which change as
  listed), `RecurrenceExpanderTest` and `AgendaTest` cases pass against the new reader.
- Policy tests: `unsupported-rule-vanishes` (every unsupported shape in architecture section 3 gives DTSTART only);
  `allday-until-utc-frame`; one bad EXDATE value ignored (`ignoredValues=1`), the series kept.
- Build: `core/gradle.lockfile` lists `net.sf.biweekly`, `vinnie` and `jackson-core` under `test*` configurations
  only. `./gradlew :core:test :core:hostileTest` green in the sandbox.
- Review checklist (T2b.3): every loop in `calendar/` has a `// bound:` comment naming (i) bytes, (ii) a fixed
  domain or (iii) a precharged budget, and it is true; no regex with nested quantifiers runs on input; every
  `catch (Throwable)` at a boundary calls `ensureActive()` first; nothing but `AgendaRefresher` calls `save`.

If this re-plan fails twice in review or security (loop limit), escalate per ADR 0004: put the Google Calendar API
source to Brian as a decision; process isolation only if he declines.
