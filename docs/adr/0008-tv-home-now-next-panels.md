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

- **Off switch.** `PanelId` enum (`weather`, `alerts`, `games`, `brief`); one boolean per id under the key
  `panel.<id>` in the existing `feeds` SharedPreferences, set over adb through `ProvisionProvider`
  (`content insert --uri .../panel --bind id:s:alerts --bind on:b:false`). The provider accepts only an id in the enum
  and a boolean value; anything else is rejected and nothing is stored (`panel-switch-id-allowlist`). Each loop reads
  its switch every slice; off means no request (fake fetcher counts calls), and the panel's view is `Off`. The brief
  switch and Quiet are adb/on-screen only: they are **not** added to the ADR 0003 tv-remote MCP allowlist.
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
  **Exception (2026-11-03, preds-arena):** the indoor arena (Predators game day, `HeroMotif.ARENA`) is scrimmed to
  **33%** (`GlanceTokens.ARENA_BRIGHTNESS`) so its navy and gold read through, as Brian asked. Its art has no
  near-white pixel; 7:1 is kept against the art instead of a white pixel: `GlanceTokensTest` checks both text colours
  over pure team gold at 33% (`#D5DCE8` 7.5:1), and `render_backdrops.py` fails the render if the art's
  99.9th-percentile pixel at 33% drops below 7:1 (it measures 8.6:1 and 7.1:1). Every other motif keeps 25%.

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
- **Inputs (the only data sent; no event titles, `brief-titles-egress`).** A small JSON facts object built by
  `briefFacts()` from cached panel values: local date and weekday, the weather chip text, the next rain window, today's
  high and low (two facts; the current temperature is not sent, `brief-fingerprint-churn`), the active alert event
  name and the game line (team, opponent, local time, network; both feed text, cleaned and cut to 60 chars before
  sending, `brief-feed-injection`), and up to 3 calendar
  items as **placeholders only**: `{"id":"E1","when":"tomorrow 9:00 AM","allDay":false}`. Never sent: event titles,
  location, ZIP, calendar URL, app list, keys, device ids. Golden test: no fixture title substring appears in the
  request body, Quiet on or off.
- **Placeholders.** Claude writes the line with `{E1}`..`{E3}` where an event goes ("Rain after 4, Preds 7 on ESPN+,
  {E1} tomorrow 9am"). The app fills them locally at draw time: the cleaned title cut to 24 chars at a word boundary,
  or "Busy" when Quiet is on. A line naming a placeholder that was not sent (`{E4}`, `{X}`) is rejected (template).
  The cached line never contains a title, so Quiet needs no cache discard.
- **Request (`brief-fallback-price`).** Same key, host pin and timeouts as Ask. The model is **pinned, with no
  `fallbacks` field and no fallback beta header**: a refusal is just "no line" (template). Model `AskConfig.MODEL`
  (`claude-opus-5-5`) unless Brian picks `claude-haiku-5-5`; effort `low`, `max_tokens` 1024 (thinking counts toward
  it), structured output `{"line": string, "uses": [enum weather, rain, high, low, alert, game, E1, E2, E3]}`, no tools,
  no history. `AnthropicClient` gets a `post(body)` used by both Ask (unchanged, keeps its fallback) and the brief.
- **Slots (`brief-slot-attempt`).** Two slots a day, 07:00 and 16:00 local; the first home visit after a slot may
  attempt it. The slot is recorded as attempted **before** the call; if that write fails there is no call. One attempt
  per slot whatever the outcome (success, error, timeout, refusal, cancel), no refunds, no retry, no refresh button.
- **Monthly cap (`brief-cap-fail-open`).** `BriefLedger`, its own no-backup file (`brief-ledger.txt`, separate from
  Ask's `ask-count.txt`): `<yyyy-mm> <count> <last slot>`. Limit 62 a month. Fail closed: an unreadable or malformed
  file counts as exhausted for the current month (it is rewritten as exhausted, so it heals at the next month); a
  stored month or slot later than now means no call and no rewrite (clock moved back). Ask's `DailyCap` is untouched.
- **Cost ceiling.** One pinned model and one request per attempt, so the per-call bound holds: about 600 input +
  1,024 output tokens. Opus 5.5 ($4/$20 per MTok): about $0.023 a call, at most about $1.42 a month (typical about
  $0.30). Haiku 5.5 ($0.10/$0.50): about $0.0006 a call, at most about $0.04 a month. With `fallbacks: "default"` the
  bound would not hold (a refused call can be re-served by a pricier model and billed twice), which is why it is off.
  The dedicated key's own limit stays the backstop.
- **Cache and freshness (`brief-fingerprint-churn`).** The line is cached on disk with a fingerprint over **only the
  facts it names**: the `uses` list plus any placeholder found in the line (canonical JSON of those facts, SHA-256).
  It shows while those facts are unchanged; when one changes (the game ended, `{E1}` passed or moved) the free local
  template line shows ("Rain after 4 · Preds 7:00 PM · Dentist tomorrow 9 AM", titles or "Busy" filled locally).
  Unrelated changes (the temperature, when the line does not name it) keep the line. Same template for no key, cap
  reached, network or API error, refusal, or empty reply. No error text ever shows.
- **Output handling.** One line, `Feeds.clean`-style control-character removal, URLs removed, at most 90 chars after
  filling, else the template. Display-only: no links, no actions.
- **On by default since Brian approved it (2026-11-03)** (hard stop 3 cleared: recurring spend on his key, within the
  cap above): the switch defaults on, and the engine still calls only when the Ask key is provisioned. It stays
  provisionable off (`--bind id:s:brief --bind on:b:false`). **Off means no brief line at all** (not even the
  template, nor a cached line); **no key** means the free template line (`brief-off-still-shows`).
- **Review fixes (PR #24).** No slot is spent on empty facts (cold start before the feeds fill in,
  `brief-empty-snapshot`). The fingerprint always includes the date, so a line never carries over to the next day
  (`brief-date-carryover`). A ledger more than a month ahead counts as malformed: exhausted now, rewritten, healed next
  month (`brief-ledger-future-lock`). The line has its own full-width row at the bottom of the dock band, inside the
  overscan margins plus the drift travel, and drifts with the hero text (`brief-oled-drift`); it is fitted by
  measuring at the row's real width and font, dropping template parts (or falling back from a cached line) so it is
  never ellipsized (`brief-line-truncation`). Placeholders are filled in the same composition as the Quiet value
  (`brief-quiet-flash`). The switch is re-read each minute and keys the line, so off hides it within a minute
  (`brief-off-lag`). The prompt asks for at most 50 characters, counting each placeholder as a full 24-character title,
  so a reply fits the row (about 60-65 characters at 24 sp) and a paid call is not lost to the fit check
  (`brief-prompt-width`). Accepted, no change: `brief-zone-hop` (a time-zone change on the TV can shift or repeat a
  slot within a day; the monthly cap still bounds the spend).

### 6. Quiet mode
- `QuietMode` flag in SharedPreferences (survives restart), toggled by the Quiet pill (last in focus order) and over
  adb through the provider. Not exposed to the tv-remote MCP tools (ADR 0003 allowlist unchanged). A remote key chord
  is left to ux: HOME, SEARCH and ASSIST never reach the app (PR #15 facts), and the remaining keys all have jobs.
- Applied in every title renderer: `homeGlance()`, `calendarTile()`, **`screenModel()` (the full-screen AgendaScreen
  the Calendar tile opens, `quiet-agenda-leak`)** and the brief's placeholder fill. Titles become "Busy", times stay.
  While the stored Quiet value is still loading (null), callers treat it as on. Nothing is discarded: no title is ever stored in the brief cache, and briefs keep running in Quiet (no title leaves).
- Test (B11): with Quiet on, no fixture title substring appears in any string of `HomeGlance`, `CalendarTile`, the
  AgendaScreen `ScreenModel`, or the filled brief line.

## Alternatives considered
- **A separate `PanelRepository` beside `LiveFeeds`.** Cleaner names, but two fetch paths and two allowlists; the
  spec forbids it (M7-1.6). Rejected.
- **WorkManager background refresh while away from home.** Fresher data on return, but wakeups and requests while
  nothing is shown, plus a new dependency. Rejected for M7; revisit if returning to stale panels annoys Brian.
- **Dim by uniform 50% scrim.** Simplest, but fails R3's 7:1 in the dimmed state. Rejected.
- **Focusable hero card with Watch inside.** Matches the mockup, but reintroduces home-back-twice. Rejected.
- **Brief on Claude Haiku 5.5** ($0.10/$0.50 per MTok, about 40x cheaper; recommended by the security review). Changing
  models is Brian's call, so the default stays the Ask model; offered to him as a one-word choice.
- **Send titles and discard on Quiet** (first draft). Better wording, but titles leave the TV and Quiet depends on a
  cache flag. Rejected by the security review.
- **Do nothing / ambient mode only** (panel's steelman): kept as the post-M7 "Later" item.

## Consequences
- `FeedsSnapshot` changes shape; `Hero.kt` and `LiveFeedsTest` change with it (task M7-B4).
- No event title reaches Anthropic: the brief sends placeholders (security design review 2026-11-02, REQUEST CHANGES
  on sections 5 and 6, applied in place).
- Two adb-set settings files grow (switches, profile, travel rules, team-to-app map); all are non-secret.
