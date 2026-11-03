# Tasks: tv-home M7 (Now / Next home screen)

Spec: M7 in `docs/specs/tv-home.md`. Architecture: `docs/architecture/tv-home.md` section 11. ADR 0008.
Rule: logic in `:core` (JVM tests, `./gradlew :core:test`); `:app` only draws and wires. Every task keeps R1 (apps row
first focus, Home to Netflix 2 presses) and AC8 (first frame never waits on a panel). Each task goes to
code-reviewer; tasks marked (sec) also to security-engineer. Install on Brian's TV is hard stop 1.

## Milestone 1: thin visible slice (next event with countdown, weather with NWS alert, OLED protection)
Done when, on the real TV: the hero shows the next event with "in N min" (or "Leave by" with a rule), the top strip
shows the weather chip, an active NWS Warning takes the context card, and the OLED drift and idle dim run (spec
M7-5.5: OLED ships with the first new panel).

| # | Task | Owner | Depends | Files | Tests |
|---|---|---|---|---|---|
| D1 | `capture-feeds` workflow (`workflow_dispatch`, ~1 min): curl NWS `alerts/active?point=36.53,-87.36` and `?area=<a state with active alerts>` with the NWS UA, NHL `club-schedule/NSH/week/now` and `club-schedule-season/NSH/now`, ESPN `teams/194/schedule` and college-football `scoreboard`; upload bodies as an artifact; log status codes and top-level keys only. Manager commits trimmed bodies as fixtures (public data). Runs first, in parallel | devops-engineer | none | `.github/workflows/capture-feeds.yml` | one green run; fixture files land in `core/src/test/resources/feeds/` |
| B1 | Panel layer types: `Stamped`, `PanelPolicy`, `PanelView`, `view()`, disk envelope codec (v1, 64 KiB read cap) | backend-engineer | none | `core/.../feeds/Panels.kt` | stale at -1 s / 0 s / +1 s; age text with fake clock (14, 15, 61 min); codec round-trip, corrupt, oversize, wrong version -> null |
| B2 (sec) | `HttpFeedFetcher`: add `api.weather.gov`; per-host headers (NWS UA + `Accept: application/geo+json`, others unchanged) | backend-engineer | none | `core/.../feeds/FeedFetcher.kt` | MockWebServer: NWS headers sent; old hosts still `tvhome-hero/1`; non-listed host, http, port 8443 and redirect still refused |
| B3 (sec) | `NwsAlerts.url/parse` + `AlertRules.classify` (rules in architecture 11.3); strings cleaned to 40 chars; at most 8 alerts | backend-engineer | B1; D1 fixtures before merge (hand fixture until then) | `core/.../feeds/NwsAlerts.kt` | table test over event types (Tornado/Severe Thunderstorm/Flash Flood Warning = OVERRIDE; Watches, Advisories, Statements = SUFFIX); expired, `Cancel`, `Test`, null `ends`; malformed and hostile docs -> null, no throw |
| B4 | `LiveFeeds`: `Stamped` snapshot; alerts loop (15 min, 5 min while active, back-off); load caches first in `run()` on IO; write-through after good fetch; per-panel switches read each slice; plus the mechanical `Hero.kt` field update so `:app` compiles | backend-engineer | B1-B3 | `core/.../feeds/LiveFeeds.kt`, `app/.../Hero.kt` (field access only) | fake fetcher counts: switch off = 0 calls; failed refresh keeps last good; alerts dropped at 30 min -> "alerts unknown"; cache published before first fetch; network off -> cache or hidden |
| B5 | `AgendaItem.endsAt` (default null; cache DTO unchanged) and `nowNext()` with `TravelRule` | backend-engineer | none | `core/.../agenda/Agenda.kt`, `core/.../ui/NowNext.kt` | fake clock at 61, 60, 1, 0 min and after start; in progress "now, until 5:30"; all-day "Today: X" never counts down; nothing left -> tomorrow's first or "Nothing on today"; calendar 3 h stale -> hidden line; no rule -> no "Leave by" |
| B6 | `GlanceBudget`, `HeroZones`, `homeGlance()` (slice: clock, chip + alert suffix, Now/Next, context card = OVERRIDE alert or today's game line) | backend-engineer | B4, B5 | `core/.../ui/HomeGlance.kt`, `core/.../ui/ContextCard.kt` | all 64 panel subsets <= 6 items, clock kept; synthetic 7th displaces lowest; zones disjoint and inside 64/36 dp margins |
| B7 | `TvProfile`, `Drift`, `IdleClock`, dim tokens | backend-engineer | none | `core/.../ui/Oled.kt` | drift within ±6/±4 dp and <= 1 dp per 20 s over 24 h; LG: DIM at 10 min, NEAR_BLACK at 30; Vizio: DIM at 30, no NEAR_BLACK; key resets; profile resolve: setting > EDID `GSM`/`VIZ` > LG |
| F1 | Type scale and contrast tokens (22 sp minimum, hero 44, clock 40, alert 40; hero scrim 25%; dim `#BEBEBE` on <= 15% field; near-black clock `#9A9A9A`) | frontend-engineer | none | `core/.../ui/Tokens.kt` | JVM contrast test >= 7:1 for every text token over worst-case white at 25%, dimmed and near-black states |
| F2 | Settings over adb: panel switches, `tv_profile`, travel rules (<= 10, keyword <= 40, 1-180 min) through `ProvisionProvider`; same shell/DUMP guard; `provision.ps1`/`.sh` help text | frontend-engineer | none | `app/.../FeedSettings.kt`, `app/.../ProvisionProvider.kt`, `scripts/provision.*`, `scripts/ci/test-provision.sh`, `docs/user/tv-home-setup.md` | validation unit tests in `:core` (`core/.../secrets/ProvisionValidator.kt` style); CI provision script test |
| F3 | Draw `HomeGlance` in zones: top strip (clock left, chip right), Now/Next left 60%, context card right 40% (alert or game line), backdrop scrim 25%, M2f top-bar game line removed. Hero stays non-focusable | frontend-engineer | B6, F1 | `app/.../Hero.kt`, `app/.../HomeScreen.kt` | emulator: Home -> Netflix in 2 presses; Back once from a tile (home-back-twice); screenshots at 1080p with fixtures (alert, game, nothing) |
| F4 | OLED layer: drift on top strip and hero text; idle overlay states from `IdleClock`; key in DIM passes through, in NEAR_BLACK wakes only; EDID PnP id picks the profile (A-M7-6, verify with `adb shell dumpsys display`; app code does not log) | frontend-engineer | B7, F1, F3 | `app/.../HomeScreen.kt`, `app/.../Hero.kt` | emulator with fake clock override: states reached; any key restores; M3 Home redirect still works from DIM and NEAR_BLACK |
| Q1 | QA milestone 1 on the emulator: the done line above, R1-R5, AC8 cold-start before/after (`am start -W`, 5 runs each), network off, every panel off | qa-engineer | F3, F4 | `docs/qa/tv-home-m7.md` | report with numbers |
| I1 | Install on Brian's TV (hard stop 1), set ZIP-37042 location if not set, read the EDID line, check one real NWS fetch | devops-engineer, Brian | Q1, Brian's OK | none | on-TV checklist in `docs/qa/tv-home-m7.md` |

Order: D1, B1, B2, B5, B7, F1, F2 in parallel -> B3 -> B4 -> B6 -> F3 -> F4 -> Q1 -> I1.

## Milestone 2: game card, time-of-day card, brief, Quiet

| # | Task | Owner | Depends | Files | Tests |
|---|---|---|---|---|---|
| B8 | `ScheduleSource` adapter per league; NHL season-now fetched daily as the fallback, ESPN team schedule is the season; disk cache; parsers reconciled with D1 fixtures (fix `Schedules` where real shapes differ) | backend-engineer | B4, D1 | `core/.../feeds/GameSources.kt`, `Schedules.kt` | shape change in the live doc -> games from fallback, no throw; real-fixture parse tests |
| B9 | `WatchMap` (network -> package, team default network; feed network names used when present) and `contextCard()` priority; time-of-day card (morning weather arc, evening tomorrow's first event and overnight low) with Open-Meteo hourly temperature + daily min/max | backend-engineer | B6, B8 | `core/.../feeds/WatchMap.kt`, `core/.../ui/ContextCard.kt`, `core/.../feeds/Weather.kt` | priority table: alert > live > upcoming today > time of day; no mapping -> no Watch; uninstalled -> no Watch; weather URL and parse with new fields |
| F5 | Watch pill in the dock band (after Calendar and Ask) opening the leanback launch intent; game card drawing; watch map over adb | frontend-engineer | B9, F2 | `app/.../HomeScreen.kt`, `app/.../ProvisionProvider.kt` | emulator: pill only on game day with an installed mapped app; focus order apps row > Calendar > Ask > Watch |
| S1 | Security review of ADR 0008 section 5 (titles to Anthropic) before B10 | security-engineer | none | report text to manager | findings |
| B10 (sec) | Brief: `briefFacts`, `briefRequestBody`, `AnthropicClient.post(body)` (Ask unchanged in behaviour), `BriefEngine` (slots 07:00/16:00, `DailyCap` limit 2, `MonthlyCap` 62, fingerprint, `hadTitles`), `templateLine`, output cleaning | backend-engineer | S1, B9 | `core/.../assistant/Brief.kt`, `AnthropicClient.kt`, `AskEngine.kt` | counting client: never > 2/day or 62/month across fake days; unwritable count file -> no call; no key / error / refusal / empty / over-long -> template; facts exclude location and (in Quiet) titles; request body golden test |
| B11 | `QuietMode` applied in `homeGlance()` and `calendarTile()`; discards a `hadTitles` brief | backend-engineer | B6, B10 | `core/.../ui/HomeGlance.kt`, `core/.../ui/CalendarTile.kt`, `core/.../assistant/Brief.kt` | no fixture title substring in any `HomeGlance` / `CalendarTile` string with Quiet on |
| F6 | Brief line in the dock band; brief switch (default **off**); Quiet pill last in focus order with a visible "Quiet" marker; state survives restart | frontend-engineer | B10, B11, F2 | `app/.../HomeScreen.kt`, `app/.../AppGraph.kt`, `app/.../FeedSettings.kt` | emulator: Quiet toggles on next redraw; R1 order unchanged; brief off makes no request |
| Q2 | QA milestone 2 against M7-4, M7-6, M7-7 | qa-engineer | F5, F6 | `docs/qa/tv-home-m7.md` | report |

Order: B8 -> B9 -> F5; S1 in parallel -> B10 -> B11 -> F6 -> Q2 -> install (hard stop 1).

Brian's steps: approve each install; confirm the location and the team-to-app map (about 5 min); answer the brief
question (on/off and model). Nothing else.
