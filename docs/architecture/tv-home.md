# Architecture: tv-home (M1 agenda, M1b Ask Claude, M1c phone/laptop remote)

Status: v1, 2026-10-31, software-architect. Spec: `docs/specs/tv-home.md` (M1). M1b and M1c are scope additions from
Brian (2026-10-31 11:23Z and 11:26Z) that are not in the spec yet; their acceptance criteria are proposed in
`docs/tasks/tv-home-m1b.md` and `docs/tasks/tv-home-m1c.md` for the product-manager to fold in.
ADRs: [0001](../adr/0001-tv-home-module-split-and-calendar-source.md) (module split, ICS library, secrets, signing),
[0002](../adr/0002-tv-home-ask-claude.md) (M1b), [0003](../adr/0003-tv-home-remote-mcp-server.md) (M1c).

## 1. Facts this design rests on
| Fact | Status | How |
|---|---|---|
| Sandbox: JDK 21 only, Gradle 8.14.3 (embedded Kotlin 2.0.21), Maven Central / maven.google.com / plugin portal reachable, dl.google.com not | Verified 2026-10-31 | `gradle --version`; spike build |
| No `jvmToolchain(17)` in the sandbox (no JDK 17, no auto-provisioning). Use `jvmTarget = 17` + `sourceCompatibility = 17` on JDK 21 | Verified | spike build failed with toolchain, passed with target |
| Maven Central returns intermittent HTTP 429 to the sandbox; retries succeed (Gradle caches what it got) | Verified | spike |
| biweekly 0.6.8 expands weekly RRULE across DST, honours EXDATE, handles UTC, TZID=Europe/Paris and VALUE=DATE | Verified | spike test (scratch, outside repo) |
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
No exception, exception message or URL crosses from `calendar` into `state` or `app`: errors are the `SourceError`
enum only. That is the main redaction control; `Redactor` is defence in depth for anything that is logged.

### Rules
- **Windowing.** Expand each VEVENT over [start of today − 1 day, start of today + 3 days) in the device zone
  (`ZoneId.systemDefault()`, injected). Timed instance appears on each local day it overlaps; label is its local start
  time if it starts that day, else `Continues`. All-day events (VALUE=DATE) appear on each date in [DTSTART, DTEND).
  Sort: all-day first, then by start, then title. Floating times are read in the device zone.
- **Recurrence.** RRULE/RDATE via biweekly's `getDateIterator(tz)` using the VEVENT's own TZID (from VTIMEZONE or
  Olson name). EXDATE removes instances. **RECURRENCE-ID overrides are not applied by biweekly**: core must group
  VEVENTs by UID, drop the original instance for each RECURRENCE-ID, and add the override. `STATUS:CANCELLED`
  instances are dropped. Iteration is capped (stop once past the window; hard cap 5,000 iterations per VEVENT).
- **Fetch.** HTTPS only (rejected in core before any I/O, plus Android `cleartextTrafficPermitted=false`).
  OkHttp: connect 5 s, read 10 s, call 15 s, `followSslRedirects(false)`, response body capped at 10 MB
  (`TOO_LARGE`). Parsing runs off the main thread. Before fetching, the app checks connectivity; offline gives
  `OFFLINE` immediately (AC8: launch with network off in under 2 s).
- **Cache.** After a good fetch, store the expanded instances for the window above as JSON
  (`{"v":1,"fetchedAt":...,"events":[...]}`, kotlinx.serialization) in `noBackupFilesDir/agenda-cache.json`
  (atomic write via temp file + rename). Titles are truncated to 200 chars before caching. The cache never contains
  the URL. A cache whose window no longer covers today counts as no cache.
- **State.** No secret: `NotSetUp`. No usable cache and first fetch in flight: `Loading` (transient, at most the
  15 s call timeout). No usable cache and fetch failed: `CantReach`. Cache and last fetch this session succeeded:
  `Ok`. Cache and (last fetch failed, or none yet and cache older than 15 min): `Stale` with "Updated N min ago",
  `emphasised` when older than 60 min. "Nothing scheduled" is how an empty `AgendaDay` renders, not a state.
  (Assumption: "Updated N min ago" is shown whenever the cache is shown after a failed fetch; emphasis at 60 min.)
- **Refresh.** Fetch on `ON_START`, then every 15 min while `STARTED` (lifecycle-scoped coroutine), never in the
  background. No WorkManager, no wake locks.

## 4. Android app (M1)
- **UI toolkit: Compose for TV** (`androidx.tv:tv-material`) over Leanback. Leanback is in maintenance mode and
  fragment-heavy; the agenda is one read-only screen; Compose for TV gives TV focus handling and 10-ft defaults.
  Cost: Compose cold start on a Chromecast-class CPU; mitigated by tiny UI and an R8 release build, measured by AC8.
- Package `com.gnosh.tvhome`. Activities: `AgendaActivity` (LEANBACK_LAUNCHER, 320x180 banner, so it is a tile in
  Projectivy or the stock launcher), `ProvisionActivity` (below), and in M1b `AskActivity` (its own tile).
- Screen: two columns (Today, Tomorrow), 32 sp titles, 28 sp times, high contrast, 5% overscan margins. Read-only,
  so the only focusable element is the screen itself; Back exits. No focus trap by construction.
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
Never typed on the TV. Brian runs a script on his PC while the TV is connected over adb (he already needs adb for
the sideload).
1. `scripts/provision.ps1 -Key calendar_url` reads the value with `Read-Host -AsSecureString` (not written to
   PSReadLine history), validates it locally (https; API key starts with `sk-ant-`), then runs
   `adb shell am start -n com.gnosh.tvhome/.ProvisionActivity --es calendar_url '<value>'` with single-quote
   escaping for the device shell.
2. `ProvisionActivity` is exported but protected by `android:permission="android.permission.DUMP"`, which the adb
   shell user holds and third-party apps cannot get, so no other app on the TV can plant or overwrite a value. It
   validates, stores via `KeystoreSecretStore`, shows "Calendar saved" (never the value), and finishes. Extra
   `--es clear calendar_url` wipes it.

Why not "adb push into app-private storage": that needs `run-as`, which works only on debuggable builds, and the
release build must not be debuggable. Why not a QR code to a phone page: that needs an HTTP server on the TV and a
LAN listener, which is more attack surface than a one-off adb command. Kept as a later option if adb proves painful.

Traces, and what tests check (AC6):
| Where | Trace | Control |
|---|---|---|
| PC PowerShell history | none (`Read-Host`) | script review |
| PC process list | the value is an adb argument for under a second, visible to Brian's own user only | accepted |
| TV `am` process args | brief; Android hides other apps' /proc | accepted |
| TV logcat | ActivityTaskManager logs `START u0 {cmp=... (has extras)}`, not extra values | **QA greps a captured logcat after provisioning a dummy URL on the emulator** |
| App logs | none in release (R8 strips Log) | QA greps logcat in a debug run too, through `Redactor` |
| Repo / CI | value never present | CI greps the build log and APK for `private-` and `sk-ant-` patterns |

## 6. Signing and CI
- One dedicated release keystore (RSA 4096, validity 30 years), generated by devops-engineer **on Brian's PC** in a
  Remote Control session (`keytool`; his Temurin JDK 21 is installed). The keystore file and passwords stay in
  `%USERPROFILE%\tv-home-signing\` (his backup; he can copy it to his password manager), and are uploaded once with
  `gh secret set` as `TVHOME_KEYSTORE_B64`, `TVHOME_KEYSTORE_PASSWORD`, `TVHOME_KEY_ALIAS`, `TVHOME_KEY_PASSWORD`.
  Fallback if gh is not signed in on his PC: generate in the sandbox, set the secrets from here, and deliver the
  keystore to his PC as the backup; the sandbox copy is deleted. Never in the repo.
- Losing the key means the next APK cannot update the installed one: uninstall and reinstall, then re-provision
  secrets (about 10 min for Brian). Low impact, so no second backup location is required.
- `.github/workflows/android.yml`: on PR and push. Job 1 `:core:test :assistant-claude:test`. Job 2 `:app:lintRelease
  :app:assembleRelease`; signed only on push to `main` when the secrets exist (keystore decoded to `$RUNNER_TEMP`,
  deleted in an `always()` step); uploads the APK as an artifact (7-day retention); checks the APK with `aapt dump
  badging`/`apkanalyzer` (not debuggable, allowBackup false, no QUERY_ALL_PACKAGES) and greps the log for secret
  patterns. Job 3 (manual `workflow_dispatch`): Android TV API 34 emulator for AC5/AC8 checks. Third-party actions
  pinned by commit SHA and reviewed by security-engineer. Budget: about 8 minutes per push; emulator runs only on
  demand, so the free private-repo minutes are enough.

## 7. M1b: Ask Claude (summary; decisions in ADR 0002)
Flow: `AskActivity` → user presses OK on the on-screen mic → `SpeechRecognizer` (remote mic audio) → text, or the
on-screen keyboard as a fallback (Gboard's own mic also works there) → `Assistant.resolve()` → one Claude Messages
call with two tools → `AskResult` → `DeepLinkBuilder` builds a `LaunchPlan` locally → app tries each intent in order
(deep link, search link, app launch) after a 3 s "Opening *title* on Netflix" countdown that Back cancels.

```kotlin
interface Assistant { suspend fun resolve(req: AskRequest): AskResult }
data class AskRequest(val utterance: String, val installed: List<ServiceKey>, val history: List<Turn>) // ≤ 3 turns
sealed interface AskResult {
  data class Open(val r: Resolution) : AskResult
  data class Clarify(val question: String) : AskResult      // Claude replied with text
  data class NotFound(val message: String) : AskResult
  data class Failed(val error: AssistantError) : AskResult
}
enum class AssistantError { NOT_SET_UP, OFFLINE, NETWORK, AUTH, RATE_LIMITED, OVERLOADED, REFUSED, DAILY_CAP, BAD_RESPONSE }
data class Resolution(val title: String, val service: ServiceKey, val kind: Kind,
                      val contentId: String?, val searchQuery: String, val confidence: Confidence)
data class LaunchPlan(val steps: List<LaunchStep>)  // LaunchStep(action, dataUri, packageName, label)
```
Tools sent to Claude:
- `web_search_20260209` (server tool, `max_uses: 3`), so "which service has it" needs no new account.
- `open_title` (client tool, `strict: true`, `additionalProperties: false`): `title`, `service` (enum of the
  **installed** service keys only), `kind`, `content_id` (string or null), `search_query`, `confidence`. Length and
  per-service id regex limits are enforced locally (title ≤ 200 chars, query ≤ 100, id ≤ 64 and must match the
  service's pattern, else dropped and the search fallback is used).
Claude never supplies a URI. The deep link is built from `services.json` by our code, so a prompt-injected web page
can at worst open the wrong title on an installed service.

`services.json` (in `core/src/main/resources/tvhome/`, also read by tv-remote) holds, per service: key, display
name, Android TV package(s), title-link template + id regex, search-link template, and a `verified` flag set only
after checking on Brian's device (task B5). Starting values (all unverified):
| Key | Package | Title link | Search link |
|---|---|---|---|
| netflix | com.netflix.ninja | `https://www.netflix.com/title/{id}` | `https://www.netflix.com/search?q={q}` |
| youtube | com.google.android.youtube.tv | `https://www.youtube.com/watch?v={id}` | `https://www.youtube.com/results?search_query={q}` |
| disneyplus | com.disney.disneyplus | `https://www.disneyplus.com/browse/entity-{id}` | app launch |
| prime | com.amazon.amazonvideo.livingroom | `https://app.primevideo.com/detail?gti={id}` | app launch |
| max | com.wbd.stream | `https://play.max.com/{id}` | app launch |
| hulu | com.hulu.livingroomplus | `https://www.hulu.com/watch/{id}` | app launch |
| appletv | com.apple.atve.androidtv.appletv | `https://tv.apple.com/{id}` | app launch |
| paramountplus | com.cbs.ott | `https://www.paramountplus.com/{id}` | app launch |
| peacock | com.peacocktv.peacockandroid | `https://www.peacocktv.com/watch/asset/{id}` | app launch |
| spotify | com.spotify.tv.android | `spotify:{id}` | `spotify:search:{q}` |
Final fallback for every service: the package's LEANBACK_LAUNCHER intent. The manifest `<queries>` lists exactly
these packages, which is what package visibility needs for `setPackage` intents.

Model call: `claude-opus-5-5` (configurable string), adaptive thinking (cannot be disabled on this model),
`output_config.effort = "low"`, `max_tokens` 4,000, `tool_choice` auto (forced tool choice is rejected on this
model), server-side refusal fallback (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`), handles
`pause_turn` by re-sending, checks `stop_reason` before reading content. Sends only the utterance and installed
service names; never calendar data. Typed SDK exceptions map to `AssistantError`; messages are never shown or logged.

**Cost per question** (Opus 5.5 list prices $4 / $20 per MTok, web search $10 per 1,000; estimate, not measured):
| Case | Input tokens | Output tokens | Searches | Cost |
|---|---|---|---|---|
| Service named or well known ("YouTube lofi") | ~2.5k | ~400 | 0 | ~$0.02 |
| Typical ("the new Dune movie") | ~15k (search results dominate) | ~800 | 1-2 | ~$0.08-0.10 |
| Hard / ambiguous | ~35k | ~1.5k | 3 | ~$0.20 |
At 5 questions a day, roughly $10-15 a month. Controls: an in-app daily cap (default 20 questions), and Brian sets
a monthly spend limit on a dedicated API workspace. A cheaper model is Brian's choice, not ours (ADR 0002).

### 7b. M1b overlay: ask about what is playing, without pausing it (Brian, 11:32Z)
| Part | Choice | Why / caveat |
|---|---|---|
| Trigger from the remote while another app plays | `TvHomeAccessibilityService` with `flagRequestFilterKeyEvents` only (`canRetrieveWindowContent=false`, no event types). It consumes **only** the configured pattern (default proposal: long-press, at least 600 ms, of a key with no long-press meaning; the key is picked in spike B5 from what the service actually receives on his remote) and passes every other key through | Our app gets no key events while another app is in the foreground; an accessibility key filter is the only non-root way. It reads no screen content (same rule as spec M3.4). Enabling it is a setting change on his TV (part of the install ask): `adb shell settings put secure enabled_accessibility_services com.gnosh.tvhome/.TvHomeAccessibilityService` |
| Overlay window | `TYPE_ACCESSIBILITY_OVERLAY` added by that service; fallback `TYPE_APPLICATION_OVERLAY` with `SYSTEM_ALERT_WINDOW` granted by `adb shell appops set com.gnosh.tvhome SYSTEM_ALERT_WINDOW allow` (Google TV usually has no "Display over other apps" screen; verify) | The accessibility overlay needs no extra permission once the service is on. Panel in the right third of the screen, text only (no speech, so it does not mix with the show's audio). It takes key focus while open; Back closes it and returns focus |
| Keeping playback going | The playing activity stays resumed; it only loses window focus | Most players pause on `onPause`/`onStop`, not on focus loss, but some may. **Speech recognition may take audio focus, and a player that pauses on transient focus loss would pause.** Spike B5 checks each of his apps for both. If the mic pauses playback, the overlay uses the on-screen keyboard or a question typed from the phone (M1c) instead |
| What is playing | `MediaSessionManager.getActiveSessions()` through a `NotificationListenerService` we enable (`adb shell cmd notification allow_listener com.gnosh.tvhome/.MediaContextListener`; verify on Google TV). Reads package, `METADATA_KEY_TITLE` / `DISPLAY_TITLE` / `DISPLAY_SUBTITLE` / `ARTIST` / `ALBUM`, duration, `PlaybackState` position. Notification content itself is ignored and never stored | Screen capture does not work for most of his apps: DRM players use secure surfaces / `FLAG_SECURE`, so capture returns black |
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
(each string ≤ 200 chars). The system prompt treats it as untrusted data. The `open_title` tool stays available
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
androidtvremote2 client cert/key, LG client key and pinned TLS fingerprint. Hosts must be private (RFC 1918)
addresses. Never printed in tool output or logs.

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
| Accessibility key filter swallows a key Brian needs | Low | Consume only the exact long-press pattern; configurable; disable in one adb command |
| Google TV lacks UI to enable the accessibility service / notification listener / overlay permission | High | Enable via adb at install time (part of the install ask); verify commands on emulator first |
| LG Wake-on-LAN needs "Turn on via Wi-Fi" enabled | Medium | Setup step C5 |
| DHCP changes TV IPs | Medium | DHCP reservations in the Deco app (C5); `tv_discover` |
| Maven Central 429s slow sandbox builds | High | Retry; Gradle cache |
