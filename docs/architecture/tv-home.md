# Architecture: tv-home (M1 agenda, M1b Ask Claude, M1c phone/laptop remote)

Status: v2, 2026-10-31, software-architect. v2 applies the security review
(`docs/reviews/security-tv-home-design.md`, findings tagged `#n key`) and the spec's M1b/M1c ACs. Spec: `docs/specs/tv-home.md` (M1, M1b, M1b-2, M1c). Build order: M1 → M1c → M1b.
ADRs: [0001](../adr/0001-tv-home-module-split-and-calendar-source.md) (module split, ICS library, secrets, signing),
[0002](../adr/0002-tv-home-ask-claude.md) (M1b), [0003](../adr/0003-tv-home-remote-mcp-server.md) (M1c).

## 1. Facts this design rests on
| Fact | Status | How |
|---|---|---|
| Sandbox: JDK 21 only, Gradle 8.14.3 (embedded Kotlin 2.0.21), Maven Central / maven.google.com / plugin portal reachable, dl.google.com not | Verified 2026-10-31 | `gradle --version`; spike build |
| No `jvmToolchain(17)` in the sandbox (no JDK 17, no auto-provisioning). Use `jvmTarget = 17` + `sourceCompatibility = 17` on JDK 21 | Verified | spike build failed with toolchain, passed with target |
| Maven Central returns intermittent HTTP 429 to the sandbox; retries succeed (Gradle caches what it got) | Verified | spike |
| biweekly 0.6.8 expands weekly RRULE across DST, honours EXDATE, handles UTC, TZID=Europe/Paris and VALUE=DATE | Verified | spike test (scratch, outside repo) |
| ...but its iterator drops 1900 series, shifts floating series 1 h after DST, evaluates VTIMEZONE RRULEs while parsing (OOM on a 335-byte feed), and errs on DAILY;INTERVAL+BYDAY and WEEKLY+BYSETPOS. Used for syntax only since 2026-10-31 | Verified | M1 core reviews; `RecurrenceExpanderTest` oracle |
| biweekly deps: vinnie 2.0.2, jackson-core 2.16.1 only (Android-safe, uses java.util.TimeZone) | Verified | `gradle dependencies` |
| ical4j 4.0.8 deps: slf4j, commons-codec, commons-lang3, threeten-extra (lighter than feared), but it loads its own tz data via ZoneRulesProvider, which is risky on Android | Deps verified; Android behaviour unverified | `gradle dependencies` |
| anthropic-java 3.2.0 resolves; pulls OkHttp 4.12, Jackson 2.19 (databind, kotlin module, jsr310), kotlin-reflect, victools jsonschema-generator, OpenTelemetry API | Verified | `gradle dependencies` |
| anthropic-java works on Android under R8 | Unverified | CI task B6 |
| PyPI: mcp 2.3.0 (MIT), androidtvremote2 0.3.2 (2026-09-01), aiowebostv 0.10.0 (2026-08-28) | Verified | PyPI JSON API |
| Brian's TV setup: an LG OLED (webOS) is mentioned in the thread title; a Google TV device (Chromecast with Google TV or Streamer) is assumed by the spec (A1). IPs .61 / .81 on 192.168.68.x unconfirmed | Unverified | **M1 and M1b only run on a Google TV device. If there is no Google TV device, M1 and M1b do not apply and only the LG half of M1c does** |
| Google TV remote's mic/Assistant button is owned by the system and not delivered to our app | Unverified (high confidence) | task B5 |

## 2. Components
```
tv-home/
  core/              Kotlin/JVM library. All logic. Builds and tests with plain Gradle in the sandbox.
  assistant-claude/  Kotlin/JVM library (M1b). ClaudeAssistant on anthropic-java. Sandbox-testable with MockWebServer.
  app/               Android TV app (Compose for TV). Renders state, holds Android glue. Builds only in GitHub Actions.
  tv-remote/         Python MCP server (M1c) for Brian's Windows PC. Sandbox-testable with fakes.
  scripts/           provision.ps1 / provision.sh (put secrets on the TV over adb), keystore helper.
  .github/workflows/ android.yml (core tests, APK build, signing), tv-remote.yml (pytest).
```
`settings.gradle.kts` always includes `:core` and `:assistant-claude`, and includes `:app` only when an Android SDK is
present (`ANDROID_HOME` set or `local.properties` has `sdk.dir`), so `gradle :core:test` works in the sandbox.
Versions are pinned in `gradle/libs.versions.toml`. The Gradle wrapper is checked in (services.gradle.org is reachable).

### core packages
| Package | Responsibility |
|---|---|
| `calendar` | `CalendarSource` interface, `IcsCalendarSource`, `IcsParser` (biweekly), `IcsFetcher` (OkHttp) |
| `agenda` | Windowing into today/tomorrow in the device zone, labels, sorting |
| `state` | `AgendaCache` format, `AgendaStateReducer`, `RefreshPolicy` |
| `secrets` | `SecretStore` interface, `SecretKey` enum, `Redactor`, input validation for provisioned values |
| `services` (M1b) | `services.json` (shared with tv-remote), `DeepLinkBuilder`, `LaunchPlan` |
| `assistant` (M1b) | `Assistant` contract, tool schemas, system prompt, `AskResult` |

## 3. Contracts (M1)
```kotlin
// calendar
interface CalendarSource { suspend fun fetch(window: InstantRange): FetchResult }
sealed interface FetchResult {
  data class Ok(val events: List<EventInstance>, val fetchedAt: Instant) : FetchResult
  data class Failed(val error: SourceError) : FetchResult
}
enum class SourceError { NOT_CONFIGURED, OFFLINE, NETWORK, HTTP_STATUS, TOO_LARGE, PARSE, INSECURE_URL }
data class EventInstance(val uid: String, val title: String, val start: EventTime, val end: EventTime?)
sealed interface EventTime {
  data class At(val instant: Instant) : EventTime        // UTC, TZID and floating times resolved to an instant
  data class AllDay(val date: LocalDate) : EventTime     // never zone-converted
}

// agenda
data class AgendaDay(val date: LocalDate, val items: List<AgendaItem>)   // empty list renders "Nothing scheduled"
data class AgendaItem(val title: String, val label: TimeLabel)
sealed interface TimeLabel { object AllDay; data class Starts(val time: LocalTime); object Continues }
fun buildAgenda(events: List<EventInstance>, now: Instant, zone: ZoneId): Pair<AgendaDay, AgendaDay>

// state
sealed interface AgendaState {
  data class Ok(val today: AgendaDay, val tomorrow: AgendaDay, val updatedAt: Instant) : AgendaState
  data class Stale(val today: AgendaDay, val tomorrow: AgendaDay, val updatedAt: Instant,
                   val ageMinutes: Long, val emphasised: Boolean) : AgendaState
  sealed interface Unavailable : AgendaState { object NotSetUp; object CantReach; object Loading }
}
fun reduce(hasSecret: Boolean, cache: CachedAgenda?, lastFetch: FetchResult?, now: Instant, zone: ZoneId): AgendaState

// secrets
enum class SecretKey { CALENDAR_URL, ANTHROPIC_API_KEY }
interface SecretStore { fun get(k: SecretKey): String?; fun put(k: SecretKey, v: String); fun clear(k: SecretKey) }
class Redactor(secrets: () -> Collection<String>) { fun redact(s: String): String }
```
**Threading contract (T5):** `CalendarSource.fetch` and `BodyFetcher.fetch` are main-safe; call them from any
dispatcher, including Main. The secret read and the download run on `Dispatchers.IO`, the parse on
`Dispatchers.Default` inside `runInterruptible`. Cancelling the calling coroutine (e.g. the lifecycle scope on
`ON_STOP`) cancels the OkHttp call and interrupts the parse; `fetch` then throws `CancellationException`, which the
app must let propagate. Nothing else is thrown. `:core` exposes kotlinx-coroutines-core 1.9.0 as an `api` dependency.
`IcsFetcher`'s client-customising constructor is `internal` (tests only); the app uses `IcsFetcher()`.
`toString()` of `EventInstance`, `FetchResult.Ok`, `AgendaDay` and `AgendaItem` prints counts and times only, never
titles or uids.

No exception, exception message or URL crosses from `calendar` into `state` or `app`: errors are the `SourceError`
enum only. That is the main redaction control; `Redactor` is defence in depth for anything that is logged.

### Rules
- **Windowing.** Expand each VEVENT over [start of today − 1 day, start of today + 3 days) in the device zone
  (`ZoneId.systemDefault()`, injected). Timed instance appears on each local day it overlaps; label is its local start
  time if it starts that day, else `Continues`. All-day events (VALUE=DATE) appear on each date in [DTSTART, DTEND).
  Sort: all-day first, then by start, then title. Floating times are read in the device zone.
- **Recurrence (revised after the M1 core reviews, 2026-10-31).** biweekly is kept for the text syntax only. Its
  iterator is not used: walking from DTSTART made 1900 series vanish, floating series drift an hour after DST, and
  SECONDLY rules (also inside VTIMEZONE, evaluated at parse time) exhaust CPU or memory. Instead:
  - VTIMEZONE components are dropped in a text pre-pass (after unfolding, any case or group prefix) before biweekly
    sees the feed. TZID resolves via `ZoneId.of` (also `/mozilla.org/...`-style prefixes), then a CLDR map of the
    Windows names Outlook writes, then the device zone (never UTC).
  - `RecurrenceExpander` (ours) expands DAILY / WEEKLY / MONTHLY / YEARLY with INTERVAL, COUNT, UNTIL, BYMONTH,
    BYMONTHDAY, BYDAY (ordinals), BYSETPOS, BYHOUR/BYMINUTE/BYSECOND and WKST, in the event's local frame (TZID zone,
    or the device zone for floating times), so wall-clock times hold across DST. It jumps to the window's period by
    arithmetic. With COUNT it counts the earlier instances arithmetically when every period yields the same number
    (all shapes Google writes), else walks period by period under the series budget. DTSTART is always instance 1
    (RFC 5545). A property test checks it against biweekly's iterator on recent DTSTARTs; it found two biweekly bugs
    (DAILY;INTERVAL>1 with BYDAY, WEEKLY with BYSETPOS), and ours follows the RFC there.
  - Not expanded (the series shows its DTSTART only): SECONDLY, MINUTELY, HOURLY (no family calendar needs them,
    Google's UI cannot make them, and they were the DoS vector), BYYEARDAY, BYWEEKNO, more than 1,000 candidates in
    one period, COUNT <= 0. Only the first RRULE counts; EXRULE is ignored.
  - EXDATE removes instances (a DATE EXDATE removes that local day). RDATE adds them. **RECURRENCE-ID**: VEVENTs are
    grouped by UID, the original instance for each RECURRENCE-ID is dropped and the override added.
    **RANGE=THISANDFUTURE is not handled**: such an override replaces only its own instance (Google and Outlook split
    the series instead of writing it). `STATUS:CANCELLED` events and instances are dropped.
  - One VEVENT that throws (an impossible date, a bad value) is skipped; the rest of the feed is used.
- **Boundaries catch `Throwable`** (`#3 okhttp-key-in-exception`): `IcsCalendarSource.fetch` and
  `ClaudeAssistant.resolve` wrap everything, map to the error enum and drop the throwable; nothing is rethrown.
- **Parsing limits:** biweekly text parser only (never `parseXml`). More than 30,000 VEVENTs fails the parse
  (`PARSE`). A parse-wide budget of 2,000,000 candidate dates, 200,000 per series, and a 2 s wall-clock deadline;
  when one runs out the series not yet expanded are skipped and the rest is returned (one-off events are processed
  first). At most 500 instances are returned: those overlapping today and tomorrow (`agendaFocus`) first, then the
  earliest; the list is in start order. The parse polls the thread's interrupt flag.
- **Fetch.** HTTPS only (rejected in core before any I/O, plus Android `cleartextTrafficPermitted=false`).
  OkHttp: connect 5 s, read 10 s, call 15 s, `followSslRedirects(false)`, response body capped at 10 MB
  (`TOO_LARGE`). Parsing runs off the main thread. Before fetching, the app checks connectivity; offline gives
  `OFFLINE` immediately (AC8: launch with network off in under 2 s).
- **Cache.** After a good fetch, store the expanded instances for the window above as JSON
  (`{"v":1,"fetchedAt":...,"windowStart":...,"windowEnd":...,"events":[...]}`, kotlinx.serialization) in `noBackupFilesDir/agenda-cache.json`
  (atomic write via temp file + rename). Titles are truncated to 200 chars before caching. The cache never contains
  the URL. A cache whose window no longer covers all of today and tomorrow counts as no cache. Added T4 states: `NothingScheduled` (fresh and both days empty), `Unreadable` (no cache, PARSE/TOO_LARGE); `Ok`/`Stale` carry `ageText`.
- **State.** No secret: `NotSetUp`. No usable cache and first fetch in flight: `Loading` (transient, at most the
  15 s call timeout). No usable cache and fetch failed: `CantReach`. Cache and last fetch this session succeeded:
  `Ok`. Cache and (last fetch failed, or none yet and cache older than 15 min): `Stale` with "Updated N min ago",
  `emphasised` when older than 60 min. "Nothing scheduled" is how an empty `AgendaDay` renders, not a state.
  (Assumption: "Updated N min ago" is shown whenever the cache is shown after a failed fetch; emphasis at 60 min.)
- **Refresh.** Fetch on `ON_START`, then every 15 min while `STARTED` (lifecycle-scoped coroutine), never in the
  background. No WorkManager, no wake locks.

## 4. Android app (M1)
- **UI toolkit: Compose for TV** (`androidx.tv:tv-material`) over Leanback. Leanback is in maintenance mode and
  fragment-heavy; the agenda is one simple screen; Compose for TV gives TV focus handling and 10-ft defaults.
  Cost: Compose cold start on a Chromecast-class CPU; mitigated by tiny UI and an R8 release build, measured by AC8.
- Package `com.gnosh.tvhome`. Activities: `AgendaActivity` (LEANBACK_LAUNCHER, 320x180 banner, so it is a tile in
  Projectivy or the stock launcher), `ProvisionActivity` (below), and in M1b `AskActivity` (its own tile).
- Screen: two columns (Today, Tomorrow), 32 sp titles, 28 sp times, high contrast, 5% overscan margins. In M1 the
  only focusable element is the screen; Back exits. M1b adds one "Ask Claude" button that takes initial focus (spec
  M1b AC1); Back still exits. No focus trap by construction.
- Release builds install an uncaught-exception handler that logs the exception class name only, never the message
  or stack, then lets the process die (`#3`).
- `KeystoreSecretStore`: AES-256-GCM key in AndroidKeyStore (non-exportable), ciphertext in `noBackupFilesDir`.
  (EncryptedSharedPreferences is deprecated; using the Keystore directly avoids the dependency.)
- Manifest: `allowBackup=false`, `dataExtractionRules`/`fullBackupContent` excluding everything,
  `usesCleartextTraffic=false`, `networkSecurityConfig`. Permissions M1: `INTERNET`, `ACCESS_NETWORK_STATE`.
  M1b adds `RECORD_AUDIO`, a `<queries>` block, and (overlay) an accessibility service bound with
  `BIND_ACCESSIBILITY_SERVICE` and a notification listener bound with `BIND_NOTIFICATION_LISTENER_SERVICE`; both
  are off until enabled over adb. Never `QUERY_ALL_PACKAGES`.
- Release build: `isDebuggable=false`, R8 on, `-assumenosideeffects class android.util.Log { *; }` so no log call
  survives. Debug builds are for the emulator only and are never installed on Brian's TV.
- minSdk 31, targetSdk 34, compileSdk 34 (bump target to 35 only if a Compose for TV release needs it). AGP pinned in
  the catalog to the newest stable release that supports Gradle 8.14 (devops-engineer records the exact version).

## 5. Getting secrets onto the TV (calendar URL, M1b API key)
Never typed on the TV, and **never passed through Claude** (`#1 secret-via-agent-transcript`): no agent, Remote
Control session or chat ever sees the value. Brian runs the script himself, in his own PowerShell window, with the
TV connected over adb (he already needs adb for the sideload). Asks to Brian give a copy-paste command and never ask
for the value. `Read-Host` cannot prompt inside an agent shell, so the script also refuses to run when stdin is not
an interactive console.
1. `scripts/provision.ps1 -Key calendar_url` reads the value with `Read-Host -AsSecureString` (not written to
   PSReadLine history), trims it and checks it against a strict regex (`#3`), printing only "valid" or "not valid":
   - calendar URL: `^https://calendar\.google\.com/calendar/ical/[A-Za-z0-9%._-]+/private-[0-9a-f]{32}/basic\.ics$`
   - API key: `^sk-ant-[A-Za-z0-9_-]{20,256}$`
   It then hands the value to the TV. Preferred, if T9 confirms it works: pipe it over stdin with
   `adb shell content write --uri content://com.gnosh.tvhome.provision/calendar_url` into a `DUMP`-guarded
   `ContentProvider`, so it is never a command-line argument (`#10`). Fallback: `adb shell am start -n
   com.gnosh.tvhome/.ProvisionActivity --es calendar_url '<value>'` with single-quote escaping.
2. `ProvisionActivity` (and the provider) is protected by `android:permission="android.permission.DUMP"`, which the
   adb shell user holds and third-party apps cannot get, so no other app on the TV can plant or overwrite a value.
   The activity has no intent-filter, `excludeFromRecents`, `noHistory`; it reads the extra, calls
   `setIntent(Intent())`, re-checks the same regex, stores via `KeystoreSecretStore`, shows "Calendar saved" (never
   the value), and calls `finishAndRemoveTask()` (`#10`). `--es clear calendar_url` wipes it.

Why not "adb push into app-private storage": that needs `run-as`, which works only on debuggable builds, and the
release build must not be debuggable. Why not a QR code to a phone page: that needs an HTTP server on the TV and a
LAN listener, which is more attack surface than a one-off adb command. Kept as a later option if adb proves painful.

Traces, and what tests check (AC6):
| Where | Trace | Control |
|---|---|---|
| Claude / agent transcripts | none: Brian runs the script himself | ask wording; script refuses non-interactive stdin |
| PC PowerShell history | none (`Read-Host`) | script review |
| PC process list | none with `content write` over stdin; with the `am start` fallback, an adb argument for under a second, visible to Brian's own user only | accepted |
| TV `am` process args | none with stdin; brief with the fallback; Android hides other apps' /proc | accepted |
| TV logcat | ActivityTaskManager logs `START u0 {cmp=... (has extras)}`, not extra values | **QA greps a captured logcat after provisioning a dummy value on the emulator** |
| App logs and crashes | none in release (R8 strips Log; uncaught handler logs class names only) | QA greps logcat incl. `System.err` and `AndroidRuntime`, and `dumpsys dropbox --print data_app_crash` (`#3`) |
| Repo / CI | value never present; fixtures use one fixed fake secret | CI greps the build log and the unzipped APK (dex and resources) for `private-[0-9a-f]` and `sk-ant-` (`#9`) |

## 6. Signing and CI
- One dedicated release keystore (RSA 4096, validity 30 years). **Brian runs `scripts/new-signing-key.ps1` himself**
  in his own PowerShell window (copy-paste command in the ask; his Temurin JDK 21 has `keytool`). The script
  generates the store and key passwords in-process (cryptographic RNG), passes them to `keytool` via
  `-storepass:env` / `-keypass:env` and to `gh secret set` via stdin, and prints nothing except "done" and the
  certificate's SHA-256 fingerprint (public). Secrets set: `TVHOME_KEYSTORE_B64`, `TVHOME_KEYSTORE_PASSWORD`,
  `TVHOME_KEY_ALIAS`, `TVHOME_KEY_PASSWORD`. The keystore and a password file stay in `%USERPROFILE%\tv-home-signing\`
  as his backup (he may copy them to his password manager). **Agents never read that folder.** If `gh` is not signed
  in, the script stops and tells him to run `gh auth login` first. There is no sandbox fallback (`#1`). Never in
  the repo.
- Losing the key means the next APK cannot update the installed one: uninstall and reinstall, then re-provision
  secrets (about 10 min for Brian). Low impact, so no second backup location is required.
- Supply chain (`#8`): Gradle wrapper with `distributionSha256Sum`, the `gradle/actions/wrapper-validation` action,
  dependency locking (`gradle.lockfile`) and `gradle/verification-metadata.xml` with SHA-256 checksums.
- `.github/workflows/android.yml` (`#9`): `permissions: contents: read`, `actions/checkout` with
  `persist-credentials: false`, never `pull_request_target` or `workflow_run`, no `--scan`, signing passwords passed
  as env vars (never `-P` on the command line). Runs on PR and push. Job 1 `:core:test :assistant-claude:test`. Job 2 `:app:lintRelease
  :app:assembleRelease`; signed only on push to `main` when the secrets exist (keystore decoded to `$RUNNER_TEMP`,
  deleted in an `always()` step); uploads the APK as an artifact (7-day retention); checks the APK with `aapt dump
  badging`/`apkanalyzer` (not debuggable, allowBackup false, no QUERY_ALL_PACKAGES) and greps the log for secret
  patterns and the unzipped APK (dex and resources). Residual risk, accepted and recorded: any workflow on `main`
  can read the signing secrets (environment protection rules may need a paid plan; not bought). Job 3 (manual `workflow_dispatch`): Android TV API 34 emulator for AC5/AC8 checks. Third-party actions
  pinned by commit SHA and reviewed by security-engineer. Budget: about 8 minutes per push; emulator runs only on
  demand, so the free private-repo minutes are enough.

## 7. M1b: Ask Claude (summary; decisions in ADR 0002)
Where it runs: **on the TV** for the remote/voice and overlay paths; typed requests from the phone or laptop go
through M1c on the PC with no API key (ADR 0002 records why). Build order: M1 → M1c → M1b.

Flow: "Ask Claude" button (initial focus on the agenda screen) or `AskActivity` tile → OK starts `SpeechRecognizer`
(remote mic audio); the transcript is shown before sending; keyboard text field if recognition is unavailable or
fails → `Assistant.resolve()` → one Claude Messages call with two tools (streamed, so the screen can show
"Searching…") → `AskResult` → `DeepLinkBuilder` builds a `LaunchPlan` locally → **result screen shows title,
service, one-line reason, and Open / Cancel; nothing launches until Brian presses Open** (spec M1b AC4,
`#2 autolaunch-vs-spec-open`). Back cancels with no launch. Open tries each step in order (deep link, search link,
app home) and the screen states which one was used. One follow-up turn is allowed; a second is refused with
"start over" (spec AC6). The whole request, including `pause_turn` rounds, times out at 20 s (spec AC7).
Conversation state lives in memory only and is dropped when the screen closes (spec AC10).

```kotlin
interface Assistant { suspend fun resolve(req: AskRequest): AskResult }
data class AskRequest(val utterance: String, val installed: List<ServiceKey>, val history: List<Turn>, // ≤ 1 follow-up
                      val nowPlaying: NowPlaying? = null)
sealed interface AskResult {
  data class Open(val r: Resolution) : AskResult
  data class Clarify(val question: String) : AskResult      // Claude replied with text
  data class NotFound(val message: String) : AskResult
  data class Failed(val error: AssistantError) : AskResult
}
enum class AssistantError { NOT_SET_UP, OFFLINE, NETWORK, AUTH, RATE_LIMITED, OVERLOADED, REFUSED, DAILY_CAP, BAD_RESPONSE }
data class Resolution(val title: String, val service: ServiceKey, val kind: Kind,
                      val contentId: String?, val searchQuery: String, val confidence: Confidence)
data class LaunchPlan(val steps: List<LaunchStep>)  // LaunchStep(action, dataUri, packageName, label); packageName never null
```
Tools sent to Claude:
- `web_search_20260209` (server tool, `max_uses: 3`), so "which service has it" needs no new account.
- `open_title` (client tool, `strict: true`, `additionalProperties: false`): `title`, `service` (enum of the
  **installed** service keys only), `kind`, `content_id` (string or null), `search_query`, `confidence`. Length and
  per-service id regex limits are enforced locally (title ≤ 200 chars, query ≤ 100, id ≤ 64 and must match the
  service's pattern, else dropped and the search fallback is used).
Claude never supplies a URI. The deep link is built from `services.json` by our code, so a prompt-injected web page
or now-playing title can at worst *propose* the wrong title on an installed service, which Brian sees before pressing
Open. Rules (`#12 deeplink-id-regex`): every id regex excludes `/ ? # % : @` and `..`; queries are URL-encoded;
every `LaunchStep` calls `setPackage`; the client never declares `web_fetch`; Clarify and NotFound texts are shown
as plain text capped at 300 chars (no links, no markup). Injection fixtures (B2): web result text telling Claude to
open a URL, a now-playing title containing instructions, a tool input with a URL in `content_id`, an unknown
service, oversized fields.

`services.json` (in `core/src/main/resources/tvhome/`, also read by tv-remote) holds, per service: key, display
name, Android TV package(s), title-link template + id regex, search-link template, and a `verified` flag set only
after checking on Brian's device (task B5). Starting values (all unverified):
| Key | Package | Title link | Search link |
|---|---|---|---|
| netflix | com.netflix.ninja | `https://www.netflix.com/title/{id}` | `https://www.netflix.com/search?q={q}` |
| youtube | com.google.android.youtube.tv | `https://www.youtube.com/watch?v={id}` | `https://www.youtube.com/results?search_query={q}` |
| disneyplus | com.disney.disneyplus | `https://www.disneyplus.com/browse/entity-{id}` | app launch |
| prime | com.amazon.amazonvideo.livingroom | `https://app.primevideo.com/detail?gti={id}` | app launch |
| max | com.wbd.stream | `https://play.max.com/show/{id}` | app launch |
| hulu | com.hulu.livingroomplus | `https://www.hulu.com/watch/{id}` | app launch |
| appletv | com.apple.atve.androidtv.appletv | `https://tv.apple.com/show/{id}` | app launch |
| paramountplus | com.cbs.ott | `https://www.paramountplus.com/shows/{id}/` | app launch |
| peacock | com.peacocktv.peacockandroid | `https://www.peacocktv.com/watch/asset/{id}` | app launch |
| spotify | com.spotify.tv.android | `spotify:{type}:{id}` (type ∈ track, album, playlist, artist, show, episode; id `^[A-Za-z0-9]{22}$`) | `spotify:search:{q}` |
Final fallback for every service: the package's LEANBACK_LAUNCHER intent. The manifest `<queries>` lists exactly
these packages, which is what package visibility needs for `setPackage` intents.

Model call: `claude-opus-5-5` by default (configurable string, see the latency table below), adaptive thinking (cannot be disabled on this model),
`output_config.effort = "low"`, `max_tokens` 4,000, `tool_choice` auto (forced tool choice is rejected on this
model), server-side refusal fallback (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`), handles
`pause_turn` by re-sending, checks `stop_reason` before reading content. Sends only the utterance and installed
service names; never calendar data. Typed SDK exceptions map to `AssistantError`; messages are never shown or logged.
The key is validated with the `#3` regex before it is ever put in a header (OkHttp 4.12's `Headers.checkValue` puts
a bad header value into its exception text, and `x-api-key` is not exempt), and `resolve()` catches `Throwable`.
Daily cap: a per-date counter in `noBackupFilesDir`, checked before each call (`#13`).

**Cost per question** (Opus 5.5 list prices $4 / $20 per MTok, web search $10 per 1,000; estimate, not measured):
| Case | Input tokens | Output tokens | Searches | Cost |
|---|---|---|---|---|
| Service named or well known ("YouTube lofi") | ~2.5k | ~400 | 0 | ~$0.02 |
| Typical ("the new Dune movie") | ~15k (search results dominate) | ~800 | 1-2 | ~$0.08-0.10 |
| Hard / ambiguous | ~35k | ~1.5k | 3 | ~$0.20 |
At 5 questions a day, roughly $10-15 a month. Controls: an in-app daily cap (default 20 questions), and Brian sets
a monthly spend limit on a dedicated API workspace **before** the key is provisioned (`#13`).

**Latency vs model** (spec AC7: 20 s timeout; a TV voice answer should feel like under 10 s). Estimates are
judgment, not measured (low confidence); web search, not output speed, dominates, so fast mode would barely help.
| Model | No search | 1-2 searches | Typical cost | Note |
|---|---|---|---|---|
| `claude-opus-5-5`, effort low | ~3-5 s | ~8-14 s | ~$0.10 | best at vague requests, the spec's stated value; default |
| `claude-sonnet-5-5`, effort low | ~2-4 s | ~6-10 s | ~$0.05 | same `web_search_20260209` tool; one config change |
| `claude-haiku-5-5` | ~1-2 s | ~4-7 s | ~$0.02 (search fees dominate) | older basic web search tool only; weakest on vague requests |
Decision: ship `claude-opus-5-5` at effort `low` (Brian asked for the latest model, and vague requests are where
quality shows), stream progress, and tell Claude in the system prompt to skip search when the request names a
service or is answerable from knowledge. B10 measures p50/p95 on his TV; if p95 is over 10 s, offer Brian the switch
to `claude-sonnet-5-5` (his choice, one config value).

### 7b. M1b overlay: ask about what is playing, without pausing it (Brian, 11:32Z)
| Part | Choice | Why / caveat |
|---|---|---|
| Trigger from the remote while another app plays | `TvHomeAccessibilityService` with `flagRequestFilterKeyEvents` only: `canRetrieveWindowContent=false`, no event types, no gestures. **Fixed trigger (spec AC11): a single press of the remote's Input button (`KEYCODE_TV_INPUT`)**, consumed on key-up; `onKeyEvent` returns `false` at once for every other key, with no buffering and no logging (`#4 a11y-settings-overwrite`). No code path leads from `AskResult` to `performGlobalAction` or `dispatchGesture`. Brian keeps input switching on the LG remote and via M1c `lg_input`. If B5 shows the filter does not receive Input on his remote, the PM re-fixes the button in the spec from the keys B5 lists | Our app gets no key events while another app is in the foreground; an accessibility key filter is the only non-root way. A single dedicated button avoids holding back other keys, which a long-press pattern would need. It reads no screen content (same rule as spec M3.4). Enabling it is a setting change on his TV (part of the install ask) and must **append**, not replace: the script reads `settings get secure enabled_accessibility_services`, saves it to a rollback file on the PC, writes the old list plus `:com.gnosh.tvhome/.TvHomeAccessibilityService`, and sets `accessibility_enabled 1` (`#4`) |
| Overlay window | `TYPE_ACCESSIBILITY_OVERLAY` added by that service; fallback `TYPE_APPLICATION_OVERLAY` with `SYSTEM_ALERT_WINDOW` granted by `adb shell appops set com.gnosh.tvhome SYSTEM_ALERT_WINDOW allow` (Google TV usually has no "Display over other apps" screen; verify) | The accessibility overlay needs no extra permission once the service is on. Panel in the right third of the screen, text only (no speech, so it does not mix with the show's audio). It takes key focus while open; Back closes it and returns focus |
| Keeping playback going | The playing activity stays resumed; it only loses window focus | Most players pause on `onPause`/`onStop`, not on focus loss, but some may. **Speech recognition may take audio focus, and a player that pauses on transient focus loss would pause.** Spike B5 checks each of his apps for both. If the mic pauses playback, the overlay uses the on-screen keyboard or a question typed from the phone (M1c) instead |
| What is playing | `MediaSessionManager.getActiveSessions()` through a `NotificationListenerService` we enable (`adb shell cmd notification allow_listener com.gnosh.tvhome/.MediaContextListener`; verify on Google TV) **only after Brian's explicit OK** (spec AC16). Reads package, `METADATA_KEY_TITLE` / `DISPLAY_TITLE` / `DISPLAY_SUBTITLE` / `ARTIST` / `ALBUM`, duration, `PlaybackState` position. The listener sees every notification, including 2FA codes, so (`#11 notif-listener-scope`) it does not override `onNotificationPosted`, declares the narrowest `android.service.notification.default_filter_types` that still lets `getActiveSessions` work (B5 checks), and stores nothing | Screen capture does not work for most of his apps: DRM players use secure surfaces / `FLAG_SECURE`, so capture returns black |
| Screen capture | **Not built in M1b.** `MediaProjection` needs a consent dialog per session (Android 14: single-use token), shows black for DRM apps, and would send frames to Anthropic | Possible later for non-DRM apps (YouTube) only, if metadata proves too thin |

Expected metadata per app (all unverified until B5; the overlay works with whatever is present and says so):
| App | Expected MediaSession data | DRM capture |
|---|---|---|
| YouTube | title, channel, position | not secure (capture possible) |
| Netflix | title, often episode subtitle, position | black |
| Disney+, Prime Video, Max, Hulu, Apple TV, Paramount+, Peacock | varies by app version: often title only, sometimes nothing | black |
| Spotify | track, artist, album, position | n/a |
| Live TV / HDMI input | none | n/a |

Contract addition: `AskRequest.nowPlaying: NowPlaying?` with
`NowPlaying(service: ServiceKey?, packageName: String, title: String?, subtitle: String?, positionMs: Long?, durationMs: Long?)`
(each string ≤ 200 chars), already in the §7 contract. The system prompt treats it as untrusted data and tells
Claude it cannot see the picture and must say so when a question depends on it (spec AC13, AC14). An Open result
from the overlay still needs an explicit Open press (`#2`). The `open_title` tool stays available
(for "play the next one"), and Claude answers in text otherwise ("Who plays the bartender?" → web search with the
title, episode and position). Cost: as the typical row above, ~$0.08-0.15 per question.

## 8. M1c: phone/laptop remote (summary; decisions in ADR 0003)
A local **Python MCP server `tv-remote`** on Brian's Windows PC, run by `uv`, stdio transport only (no listening
port). Clients: Claude Code and the Claude desktop app on the PC/laptop; from the phone via a Remote Control session
on the PC. Google TV is controlled through the **Android TV Remote protocol v2** (`androidtvremote2`, the same
pairing-code protocol the Google TV phone app uses, so no Developer options are needed for M1c). LG webOS through
**SSAP** (`aiowebostv`, pairing prompt on the TV) plus Wake-on-LAN.

Tools (complete allowlist; anything else is not exposed):
| Tool | Args | Notes |
|---|---|---|
| `tv_status` | none | reachability, LG power/volume/input/foreground app, Google TV current app |
| `tv_open` | `service`, `content_id?`, `search_query?` | builds the link from the shared `services.json`, sends it as an app link |
| `tv_launch_app` | `service` (enum) | |
| `tv_key` | `key` enum (UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, PLAY_PAUSE, REWIND, FAST_FORWARD), `repeat` 1-10 | |
| `lg_power` | `on` / `off` | on = Wake-on-LAN to the stored MAC; off = `ssap://system/turnOff` |
| `lg_volume` | `level` 0-60, or `up` / `down` | capped at 60 |
| `lg_mute` | bool | |
| `lg_input` | HDMI_1..HDMI_4 | |
| `tv_pair` | `device`, `code?` | two-step pairing; Brian reads the code off the TV |
| `tv_discover` | none | mDNS `_androidtvremote2._tcp` and SSDP, setup only |
Not exposed: install/uninstall, settings, raw adb or SSAP URIs, browser, notifications, factory reset.
Config and pairing material in `%APPDATA%\tv-remote\` (user-only ACL): `config.toml` (hosts, LG MAC), the
androidtvremote2 client cert and key, the LG client key, and both TVs' pinned TLS fingerprints. The LG client key and
the ATV client private key are encrypted at rest with Windows DPAPI (current user) (`#5`, `#6`). Hosts must be private
(RFC 1918) addresses. Never printed in tool output or logs.
- LG (`#5 webos-broad-permissions`): register with a trimmed permission manifest, not aiowebostv's default of about
  37 (which includes WRITE_SETTINGS, input control and toasts): LAUNCH, CONTROL_POWER, CONTROL_AUDIO,
  CONTROL_INPUT_TV, READ_INPUT_DEVICE_LIST, READ_RUNNING_APPS, READ_POWER_STATE, READ_APP_STATUS. C3 verifies the
  TV honours the trimmed list (a call outside it must fail). wss on 3001 only, never ws on 3000.
- Google TV (`#6 atv-remote-cert-trust`): pin the TV's certificate fingerprint at pairing; refuse a changed one
  until re-paired.
- The allowlist is not a security boundary inside Claude Code (`#7 allowlist-not-a-boundary`): there the model also
  has Bash and could talk to the TVs directly. It binds MCP-only clients (desktop app). `tv_pair` and `lg_power` are
  never on auto-allow in Claude Code permissions.
- Supply chain (`#8`): `uv.lock` committed with hashes, server run with `uv run --locked`, uv installed from a
  pinned winget version.

## 9. Non-functional
- Performance: AC8 (cold start, network off, under 2 s, `am start -W` on the emulator). Parsing a 5 MB feed off
  the main thread is fine because the cache renders first.
- Observability: none beyond the screen (spec: no telemetry). Debug builds log through `Redactor`.
- Size budget (cache): at most 500 instances × (uid ≤ 256 B + title ≤ 800 B UTF-8 after truncation to 200 chars +
  ~60 B times) ≈ 560 KB worst case; typical under 20 KB.

## 10. Risks
| Risk | Likelihood | Mitigation |
|---|---|---|
| Brian has no Google TV device (LG webOS only) | Unknown | Ask (see handoff). M1c LG half still works |
| RECURRENCE-ID overrides handled wrong (moved meetings shown twice) | Medium | Core handles them; fixture task T2 |
| Google ICS lag of hours | High | Spec accepts; `CalendarSource` keeps OAuth possible later |
| `DUMP`-guarded activity not startable from adb shell on his Android version | Low | Emulator check in T9; fallback is a `ContentProvider` with the same guard via `adb shell content call` |
| anthropic-java fails on Android/R8 (reflection, size) | Medium | `:assistant-claude` isolated; R8 keep rules; emulator smoke test (B6). If it fails: ADR 0002 revisit |
| Deep links differ per app version on Google TV | High | `verified` flags, search and app-launch fallbacks, on-device check (B5) |
| Built-in Google TV assistant (Gemini) already does "play X on Netflix" | Medium | Task B0: Brian tries it first; M1b may be dropped |
| Overlay: speech recognition takes audio focus and the player pauses, which breaks "without pausing" | Medium | Spike B5 per app; keyboard / phone-typed fallback |
| Overlay: a player pauses on window-focus loss | Low-medium | Spike B5 per app; non-focusable "peek" panel as fallback |
| Overlay: apps publish no MediaSession metadata, so context is thin | Medium (varies by app) | Per-app table in B5; say "I can't see what's playing" rather than guess |
| Accessibility key filter swallows a key Brian needs | Low | Consumes only Input, which stays available on the LG remote and M1c; rollback file restores the old accessibility list |
| Google TV lacks UI to enable the accessibility service / notification listener / overlay permission | High | Enable via adb at install time (part of the install ask); verify commands on emulator first |
| LG Wake-on-LAN needs "Turn on via Wi-Fi" enabled | Medium | Setup step C5 |
| DHCP changes TV IPs | Medium | DHCP reservations in the Deco app (C5); `tv_discover` |
| A secret pasted into chat when a script fails | Medium | `#1`: Brian runs scripts himself; asks never request values; script refuses non-interactive stdin |
| Answer too slow for a TV (search-heavy requests) | Medium | Stream progress; skip search when not needed; B10 measures; Sonnet 5.5 switch offered |
| Maven Central 429s slow sandbox builds | High | Retry; Gradle cache |
