# ADR 0008: tv-home M7 Now / Next home screen (panel data layer, glance budget, OLED, brief, Quiet)

Status: proposed (software-architect), 2026-11-02. Spec: `docs/specs/tv-home.md` M7. Panel report:
`docs/reviews/dashboard-info-panels.md`. Builds on ADR 0002 (Ask Claude) and ADR 0005 (live feeds). Architecture
summary, zones and verified facts: `docs/architecture/tv-home.md` section 11. Tasks: `docs/tasks/tv-home-m7.md`.

## Context
The hero today shows a focused-tile title, a top-bar game line and a weather chip, all from `LiveFeeds` (in memory
only). M7 turns the hero into an information surface: Now/Next calendar, weather with NWS severe alerts, a game card,
a one-line Claude brief and a Quiet mode, under hard rules R1-R5 (apps row keeps first focus, at most 6 glance items,
readable sizes at 7:1, no tickers, every panel shows its age or hides). One user (Brian), one TV app, no server.

## Decisions

### 1. Panel data layer: extend `LiveFeeds`, add a stamped disk cache; calendar stays on `AgendaSession`
- **One fetch path.** `LiveFeeds` stays the only network client for panels (spec M7-1.6). It gains an NWS alerts
  loop, per-panel stamps, a disk cache and off switches. No second repository class, no WorkManager.
- **Calendar is not re-cached.** Now/Next reads the existing `AgendaSession.state` (its own disk cache, its own 15 min
  refresh, its own staleness). No new calendar access (M7-3.1).
- **Stamped value.** `data class Stamped<T>(val value: T, val at: Instant)`: the time the value was fetched, never the
  time it was drawn. `FeedsSnapshot` becomes `weather: Stamped<WeatherNow>?`, `alerts: Stamped<AlertSet>?`,
  `games: Stamped<List<TeamGame>>?` (`at` is the oldest team fetch that fed it).
- **Last-good disk cache.** One file per panel under `noBackupFilesDir/panels/<id>.json`, written with the existing
  `CacheFile.writeAtomic` (temp file, fsync, rename). Envelope `{"v":1,"at":"<ISO instant>","data":{...}}`, read cap
  64 KiB, any decode failure is "no cache". Written only after a successful fetch and parse, so a failed refresh never
  replaces a good value (M7-1.1). Loaded once, on the IO dispatcher, as the first step of `LiveFeeds.run()`.
- **Panel policy (pure, `:core`).** `PanelPolicy(staleAfter, ageShownAfter, onStale = HIDE | SHOW_STALE)` and
  `fun <T> view(stamped: Stamped<T>?, policy, enabled, now): PanelView<T>` with `Off`, `NoData`,
  `Shown(value, ageText?, stale)`. Stale means `age >= staleAfter` (tests at -1 s, 0 s, +1 s). The age line reuses
  `ageText()` ("Updated 12 min ago", minute resolution) and is shown once `age >= ageShownAfter` (15 min default), so
  fresh panels carry no extra text; hidden panels say why only where the spec asks ("alerts unknown", calendar).

  | Panel | staleAfter | onStale | Refresh |
  |---|---|---|---|
  | Weather (Open-Meteo) | 2 h (existing) | HIDE | 30 min, back-off to 2 h |
  | Alerts (NWS) | 30 min | HIDE, chip says "alerts unknown" | 5 min while any alert is active, else 15 min |
  | Games | existing `GameDay` rules (upcoming +4 h, live +6 h) | HIDE | 60 min; 60 s live or within 10 min |
  | Calendar (Now/Next) | 3 h (`AgendaSession` age) | HIDE with "Calendar not updated since 2:10 PM" | existing 15 min |
  | Brief | next brief slot | HIDE, template line instead | 07:00 and 16:00 slots |

- **Off switch.** `PanelSwitches` (weather, alerts, games, brief) in the existing `feeds` SharedPreferences, set over
  adb through `ProvisionProvider` (`content insert --uri .../panel --bind id:s:alerts --bind on:b:false`). Each loop
  reads its switch every slice; off means no request (fake fetcher counts calls), and the panel's view is `Off`.
- **Scheduling.** Loops run only while the hero is composed (home visible), start after the first frame plus the
  existing 1.5 s settle on `Dispatchers.Default`, and each wakes at most once a minute (existing `SLICE_MS`). Leaving
  home cancels them; returning shows the disk/memory cache with its age and fetches only what is due. Nothing polls
  while Brian watches something (lean, and nothing would be on screen to show it).
- **Network off.** Every panel falls back to cache or hides (policy table); the apps row never waits on a panel.

### 2. Glance budget in code
- One pure composer, `homeGlance(inputs): HomeGlance` in `:core/ui`, is the only source of text drawn by the hero,
  the top strip and the brief line. Inputs: clock, `FeedsSnapshot` views, `AgendaState`, brief, Quiet flag, profile.
- `GlanceBudget.MAX = 6`. Candidates carry a fixed priority: clock (always kept) > severe-alert card > Now line 1 >
  weather chip > context card (non-alert) > Now line 2 > brief. `fit()` keeps the top 6 by priority; a 7th candidate
  displaces the lowest instead of stacking. Data-age lines, the Quiet marker and a chip's alert suffix belong to their
  item and do not count. The top-bar game line from M2f is removed: the game moves into the context card.
- Tests: every subset of the 6 panels on/off (64 cases) yields at most 6 items and never drops the clock; a synthetic
  7th item displaces the brief; a severe alert displaces nothing above Now line 1.

### 3. Hero layout zones (1920x1080 px = 1280x720 dp; 1 dp = 1.5 px)
Constants in `:core/ui/HeroZones.kt` (dp), with a JVM test that zones do not overlap and stay inside the 64/36 dp
safe margins. ux-designer may move numbers; the structure is fixed.

| Zone | dp (x, y, w, h) | Content | Focusable |
|---|---|---|---|
| Top strip | 64, 36, 1152, 64 | clock + date (left, 40 sp); weather chip and alert suffix (right, 24 sp); drifts | no |
| Hero left 60% | 64, 112, 680, 270 | Now line 1 (44 sp), line 2 (28 sp), age line (22 sp) | no |
| Hero right 40% | 768, 112, 448, 270 | context card: severe alert (40 sp) / live or upcoming game / time-of-day card | no |
| Apps row | unchanged | tiles; **first focus** | yes |
| Dock band | unchanged + brief line | Calendar tile, Ask pill, Watch pill (game day only), Quiet pill; brief line 24 sp | pills only |

- **Focus (R1, home-back-twice).** The hero stays non-focusable (a focusable ancestor of the tiles brings back
  home-back-twice). So "OK on the hero opens the calendar" (M7-3.7) is served by the existing Calendar tile, and the
  game card's Watch button is a **Watch pill in the dock band**, after Calendar and Ask in focus order; the Quiet
  pill is last. Nothing new is focusable above or between the apps row and a tile.
- **Text and contrast (R3).** The spec's px are physical at 1080p, so 32 px = 22 sp minimum, hero 44 sp (66 px),
  clock 40 sp (60 px), alert 40 sp. The backdrop is dimmed with a black scrim to **25%** (spec "about 35%"): 25% is
  the value at which both hero text colours keep 7:1 even over a pure-white pixel (`#F4F1EA` 9.2:1, `#D5DCE8`
  7.5:1; at 35% they are 6.2:1 and 5.1:1), so the test needs no knowledge of the art.

### 4. OLED profile (LG vs Vizio, drift, idle dim)
- **Profile.** `TvProfile.LG_OLED` (drift on, dim at 10 min, near-black at 30 min) and `TvProfile.VIZIO_LED` (drift
  off, dim at 30 min, no near-black). Resolution order: adb setting `tv_profile` (`lg` | `vizio`) if set, else EDID via
  `Display.getDeviceProductInfo()?.manufacturerPnpId` (API 31, our minSdk): `GSM` (LG) or `VIZ` (Vizio), else LG
  (safer). The PC-side tv-remote knows nothing the TV app can read, so it is not a source. Unverified on device.
- **Drift.** `Drift.offset(elapsed): (dx, dy)` pure: a slow closed path within ±6 dp x and ±4 dp y, at most 1 dp per
  20 s, so there is no visible jump. Applied to the top strip and the hero text (the static elements), not to tiles.
- **Idle.** `IdleClock` in `:core`: `ACTIVE` → `DIM` → `NEAR_BLACK` from the last key time (fake-clock tests).
  "Dim" is not a uniform scrim (a 50% scrim over text drops contrast below 7:1): the backdrop and tile fills go to
  15%, text to `#BEBEBE` (8.1:1 on the dimmed field). Near-black is `#000000` with only the clock in `#9A9A9A` (7.5:1),
  drifting. The key handler stays on the existing `onPreviewKeyEvent` (not a focus target). In `DIM` a key restores
  and passes through. In `NEAR_BLACK` the first key only wakes the screen (consumed), because the focused tile is
  invisible and OK would otherwise launch an app blind; this deviates from M7-5.2 and is flagged to the PM/ux.
- No `FLAG_KEEP_SCREEN_ON`: the system screensaver and sleep still apply over us. Idle states are composition state
  only, so they cannot change the M3 Home redirect (`HomeWatchService` watches activities, not pixels).

### 5. Claude daily brief
- **Inputs (the only data sent).** A small JSON facts object built by `briefFacts()` from cached panel values: local
  date and weekday, the weather chip text, the next rain window, today's high/low, the active alert event name, the
  game line (team, opponent, local time, network), and up to 3 calendar items (local time + title; **no titles when
  Quiet is on**, no titles older than today). Never sent: location, ZIP, calendar URL, app list, keys, device ids.
- **Request.** Same key, host pin, timeouts, `fallbacks: "default"` and model as Ask (`AskConfig.MODEL`,
  `claude-opus-5-5`), effort `low`, `max_tokens` 1024 (thinking counts toward it), structured output
  `{"line": string}`, no tools, no history. `AnthropicClient` gets a `post(body)` used by both Ask and the brief.
- **Cap (fail closed).** At most 2 generations per local day (slots 07:00 and 16:00; the first home visit after a slot
  generates) and at most 62 per month, each in its own no-backup count file (`DailyCap` reused with limit 2; a new
  `MonthlyCap`). A count that cannot be written means no call. No refresh button in M7. Worst case per call is about
  600 input + 1,024 output tokens = about $0.023 at $4/$20 per MTok, so the hard ceiling is about $1.42/month
  (typical about $0.30). The dedicated key's own limit stays the backstop.
- **Cache and freshness.** The line is cached on disk with its facts fingerprint (SHA-256 of the facts JSON) and
  `hadTitles`. It is shown only while the current facts' fingerprint still matches; otherwise (a game ended, an event
  passed) the free local template line is shown ("Rain after 4 · Preds 7:00 PM · Dentist tomorrow 9 AM"). Same
  fallback for no key, cap reached, network or API error, refusal, or empty reply. No error text ever shows.
- **Output handling.** One line, `Feeds.clean`-style control-character removal, URLs removed, capped at 90 chars,
  else the template. Event titles are outside input (invites), so the line is display-only: no links, no actions.
- **Off by default until Brian says yes** (hard stop 3, new recurring spend on his key): the brief's switch ships off
  and the template line fills the slot at no cost.

### 6. Quiet mode
- `QuietMode` flag in SharedPreferences (survives restart), toggled by the Quiet pill (last in focus order) and over
  adb/the PC remote through the provider. A remote key chord is left to ux: HOME, SEARCH and ASSIST never reach the app
  (PR #15 facts), and the remaining keys all have jobs.
- Applied inside `homeGlance()`, `calendarTile()` and `screenModel(..., hideTitles)` (the full-screen calendar is covered too, decided in
  review `quiet-calendar-fullscreen`); every title and spoken description goes through `QuietMode.title`: titles become "Busy", times stay.
  While the stored Quiet value is still loading (null) callers treat it as on. Turning Quiet on
  discards a cached brief with `hadTitles`, and briefs made in Quiet carry no titles (template only, no call).
- Test: with Quiet on, no fixture title substring appears in any string of `HomeGlance` or `CalendarTile`.

## Alternatives considered
- **A separate `PanelRepository` beside `LiveFeeds`.** Cleaner names, but two fetch paths and two allowlists; the
  spec forbids it (M7-1.6). Rejected.
- **WorkManager background refresh while away from home.** Fresher data on return, but wakeups and requests while
  nothing is shown, plus a new dependency. Rejected for M7; revisit if returning to stale panels annoys Brian.
- **Dim by uniform 50% scrim.** Simplest, but fails R3's 7:1 in the dimmed state. Rejected.
- **Focusable hero card with Watch inside.** Matches the mockup, but reintroduces home-back-twice. Rejected.
- **Brief on Claude Haiku 5.5** ($0.10/$0.50 per MTok, about 40x cheaper). Changing models is Brian's call, so the
  default stays the Ask model; offered to him as a one-word choice.
- **Do nothing / ambient mode only** (panel's steelman): kept as the post-M7 "Later" item.

## Consequences
- `FeedsSnapshot` changes shape; `Hero.kt` and `LiveFeedsTest` change with it (task M7-B4).
- Event titles reach Anthropic for the first time (brief only, off by default): security-engineer reviews section 5
  before the brief task starts.
- Two adb-set settings files grow (switches, profile, travel rules, team-to-app map); all are non-secret.
