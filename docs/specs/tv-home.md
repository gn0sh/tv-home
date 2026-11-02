# Spec: tv-home

Status: draft v2 (panel changes applied, docs/reviews/panel-tv-home-spec.md), 2026-10-31. Priority: hip pocket (low; built in idle time).

## Goal check
Brian gets his own Google TV home screen: one row of his streaming apps to open with the remote, plus a panel showing today's calendar, and an Ask Claude button that opens the right service on what he asks for.
Example: he turns on the TV and sees Netflix, YouTube, Disney+ etc. tiles and "Dentist 3pm" without opening anything.
(Confirmed by CEO as the request, 2026-10-31; the sentence itself is for a yes/no check.)

## Users
Primary user: Brian (confirmed by CEO, 2026-10-31). Internal, no public surface, no store listing, no accounts.
Users nobody has confirmed: household members, other viewers. Out of scope.

## Problem
The stock Google TV home is dominated by recommendations and ads, and has no calendar. Brian wants his apps up front and glanceable info beside them.

## Goals
- Open any installed streaming app in two or fewer remote presses from the home screen.
- See today's and tomorrow's events without opening anything.

## Non-goals / Later
- Replacing streaming apps' content rows, recommendations, search, accounts, ads, anything public.
- Later: more panels (news, photos, smart home), voice, multi-calendar colours.

## Assumptions
| # | Assumption | Status | Confidence |
|---|---|---|---|
| A1 | Device is Chromecast with Google TV or Google TV Streamer (Android TV based); exact model unknown | Unverified | Medium |
| A2 | Google TV has no setting to pick another home app. The real path to being home is adb (`cmd package set-home-activity`, else `pm disable-user` on the stock launcher); side effects, OTA updates may undo it, rollback needs a PC. An accessibility service cannot catch Home: it can only redirect after the stock home appears (flash, launcher-name dependent, no boot coverage), so it is fallback only | Partly contradicted by M3. **Result (2026-11-02, PR #15, emulator):** `set-home-activity` moves the HOME role but the stock launcher still wins (it declares HOME at priority 2), so it does not work; an accessibility *key filter* does catch Home before the launcher sees it (no flash), and is the chosen path. Boot still lands on the stock launcher; holding Home opens Settings. Was: Unverified on his device | Medium |
| A7 | Brian wants the mockup's full home screen (app row + calendar + Ask Claude on one screen) | Unconfirmed in words (inferred from "More than that" after seeing the mockup, 2026-10-31) | Medium |
| A6 | Free launchers (Projectivy and similar) already cover the app row, favorites order and home handling; Projectivy installs from the Play Store, no sideload | Unverified | Medium |
| A3 | Installing our APK means sideloading: Developer options + adb over the network from his PC, or a downloader app. Installing on his TV is a hard stop (needs his OK) | Unverified on his device | High |
| A4 | Builds run in GitHub Actions (no Android SDK in our cloud sandbox); pure logic (app ordering, ICS parsing, date windows, staleness) must be unit-testable on a plain JVM | Verified (sandbox) | High |
| A5 | Calendar source is undecided (see below). ICS secret URL is a secret, so obtaining it is a hard stop needing Brian's typed OK. Google ICS feeds can lag by hours | Unverified | High |

Calendar source: open decision for Brian, needed only when the agenda app is built (default: ICS). Built against a `CalendarSource` interface and fixtures meanwhile, so either plugs in.
- ICS secret URL: simple, no Google Cloud setup, read-only. Cost: can lag hours (weakens "Dentist 3pm"); URL is a secret kept only in private on-device storage, never in repo, logs or CI.
- OAuth device flow, calendar.readonly: real-time. Cost: Brian creates a Google Cloud OAuth client and consents (credential step, hard stop 5).
- URL/credentials are never typed on the TV: entry via adb push or a QR/phone step.

Technical constraints (for the architect): use a maintained HTTP client; read ICS with our own strict reader for the subset Google Calendar emits, with biweekly kept only as a test oracle (ADR 0004, after the bounded-parse re-plan); minSdk 31, target 34; `<queries>` with LEANBACK_LAUNCHER, not QUERY_ALL_PACKAGES; allowBackup=false, non-debuggable release builds, secrets scrubbed from exceptions and logs; one stable signing key kept in Actions secrets.

## Milestones and user stories
Order: M0 (resolved), M1 (agenda app), M1b, M1c, then M2 (full home screen). Only M3 is conditional.

### M0: Try an existing launcher (no build, no sideload) - RESOLVED 2026-10-31
As Brian, I want to try a ready-made launcher first, so I only get custom work for what is truly missing.
1. Brian installs Projectivy Launcher from the Play Store and sets up his app row (about 30 min).
2. He reports back: does it cover his app row and favorites order, does it become Home, and what is missing (calendar expected).
RESOLVED: Brian answered "More than that" (not "just the calendar", not "it covers everything") after seeing the mockup. Result: M2 is unconditional (below).
3. (Original) Done when the gap list is recorded in `docs/status.md`. Projectivy remains the app row; our app (agenda + Ask Claude) opens from it as a tile. If the only gaps are the calendar and Ask Claude, M2 is dropped.

### M1: Calendar agenda app (usable from any launcher)
As Brian, I want today's and tomorrow's events on the TV, as an app/tile in whatever launcher I use, so I see my day at a glance.
Acceptance criteria
1. Given fixture ICS files (via `CalendarSource`), the agenda shows today's and tomorrow's events with local start time and title; all-day events labelled "All day"; sorted by start. JVM unit tests.
2. Fixtures cover weekly RRULE, EXDATE/cancelled instance, a UTC event and an event in another timezone, all at the correct local time.
3. Fetch at launch, then at most every 15 min while visible; last good result cached on device and shown immediately.
4. Three states only: ok; stale (fetch failed, cache shown with "Updated N min ago"; stale when 60 minutes or older, emphasised); unavailable (no cache: "Can't reach calendar", or "Calendar not set up" with a one-line how-to). Empty day shows "Nothing scheduled".
5. Readable at 10 feet and fully D-pad/Back navigable (no focus trap), checked on a TV emulator.
6. The secret (URL or token) is stored only in private on-device storage; grep of repo, CI output and a captured log finds none of it. Never entered by typing on the TV.
7. HTTPS only; allowBackup=false; release APK non-debuggable, built by GitHub Actions and signed with the stable key.
8. The agenda launches with the network off in under 2 s showing the cache or "unavailable" (measured on emulator with `adb shell am start -W`).
Hard stops: installing on the TV (its own ask, once); Brian providing the ICS URL or OAuth client with his typed OK.

### M1b: Ask Claude (same app as the agenda)
As Brian, I want to say what I feel like watching and have it open on my services, so I skip browsing.
Honest note: the remote's Google Assistant already handles exact requests like "play The Bear". Claude's value is vague requests ("something funny under 30 minutes"), follow-ups and suggestions, and answering from only the services Brian has installed.
Acceptance criteria
1. An "Ask Claude" button is reachable by D-pad from the agenda screen's initial focus in at most 2 presses.
2. Voice: OK on the button starts speech recognition via the remote mic (Android `SpeechRecognizer`/RecognizerIntent); the transcript is shown before sending. If recognition is unavailable or fails, a keyboard text field appears (fallback). Both paths tested on the device.
3. The request goes to the Claude API with the list of Brian's installed TV apps (names + package ids, from `<queries>` LEANBACK_LAUNCHER) and returns structured output: title, service (must be one of the installed ones), and either a deep link or "search in app". A pure-JVM test with a fake `ClaudeClient` and fixtures covers: exact title ("new Stranger Things"), vague request, service not installed (answer says so and offers an installed alternative), malformed or empty response (shows "Couldn't work that out", nothing launches).
4. The result screen shows what Claude picked (title, service, one-line reason) with Open and Cancel; nothing launches until Brian presses Open. Back cancels with no launch (tested).
5. Open launches the chosen app on the title via deep link when available, else on the app's search page for the title, else the app's home; the fallback used is shown. Verified on the device for at least 2 services.
6. One follow-up turn is allowed ("something shorter") and replaces the result; a second follow-up is refused with a message and a "start over" action. Conversation state is discarded when leaving the screen.
7. Offline or API error shows "Can't reach Claude" with Retry; the agenda still works. A request times out after 20 s.
8. The API key is stored only in private on-device storage, never in repo, CI output or logs (grep check on repo and a captured log), and never typed on the TV (adb push or QR/phone step, as with the calendar secret). Hard stop: putting the key on the TV needs Brian's typed OK. Residual risk, stated: a key on the TV can be extracted by anyone with adb access to it; use a dedicated, spend-limited key.
9. Uses Brian's Anthropic API credits (included in his plan). Architect estimates cost per question (tokens per request, model choice) in the architecture doc before build; the app shows no cost UI. Only the spoken/typed text and installed app names are sent, never calendar data.
10. Titles and requests are not stored beyond the single session.

#### M1b-2: Ask while something is playing
Limit, stated plainly: streaming apps use DRM, so other apps cannot see or capture the picture. Claude cannot see the screen; it answers from what is playing (show, episode, position) as reported by the media session, plus its own knowledge. Questions about things only visible in the frame ("who is that actor in this shot") may be answered by guessing from the episode and cast; it must say so rather than claim to see the image.
Acceptance criteria
11. While another app plays, a designated remote button (one fixed in the spec at design time, not the Home or Back key) opens an Ask Claude overlay within 1 s; playback continues uninterrupted (position advances during the overlay, checked via media session on the device).
12. The overlay uses the same voice/keyboard input and answer display as M1b. Only the overlay takes focus; the video keeps audio.
13. The request includes now-playing title, episode and position from the media session when available; when unavailable, the answer says it does not know what is playing.
14. Answers never claim to see the picture; a test question about on-screen content gets a reply stating the limit.
15. Back closes the overlay and returns to the video with playback state unchanged; one follow-up is allowed, a second is refused (same rule as M1b).
16. Reading the media session needs a user-granted notification-listener permission; this is an explicit step Brian approves, and nothing is read until granted.

Design reference: Brian saw a clickable mockup on 2026-10-31: https://claude.ai/artifact/L8f5rSyjZGA46d5BuJEier

### M1c: Control from Claude (typing on laptop or phone)
As Brian, I want to type to Claude on my laptop or phone and have it control my TV, so I don't need the remote.
Scope is outcomes only; the mechanism is the architect's. Reaches the TV from his Windows PC over the home network (LAN only); the phone works through the PC, which must be on.
Acceptance criteria
1. From Claude on his laptop, "open Stranger Things" opens the right installed service on the title or its search page, reusing M1b's find-and-open logic (same fixtures pass for both entry points).
2. Claude can press remote buttons on the Google TV (Home, Back, D-pad, OK, play/pause) on request; each is observed working on the device.
3. Claude can control the LG TV: power on/off, volume up/down/set, mute, and switch input. Each is observed working on the TV.
4. LG pairing is a one-time prompt accepted on the TV; after that, commands work without prompts, including after a reboot of the PC.
5. Allowed actions are an explicit list. Uninstall, settings changes, factory reset, account changes and anything outside the list are refused with a clear message (tested by asking for each).
6. LAN only: the TV is not exposed to the internet and no cloud relay is added (checked by listening ports and outbound connections during use).
7. If the PC, a TV or the network is unreachable, Claude says which one and does nothing else (tested with each unplugged/off).
8. Phone use works via the PC; when the PC is off, the failure message says so.
9. Nothing is installed on his PC, and no network debugging or pairing is enabled on either device, without Brian's OK (hard stop, asked once per item).

### M2: Our home screen (app row + calendar + Ask Claude, like the mockup)
No longer conditional. One home screen combines the app row, the calendar panel (M1) and the Ask Claude button (M1b), as in the mockup. Projectivy is no longer the app row; it can stay installed as the fallback. Criteria:
1. Lists apps with a TV launcher entry (`<queries>` LEANBACK_LAUNCHER), favorites first in configured order, rest alphabetical; JVM test for ordering incl. missing and duplicate favorites.
2. OK launches the app; Back returns; D-pad focus has no dead ends; focused tile visibly distinct.
3. The home screen shows the app row, the calendar panel and the Ask Claude button together; D-pad moves between all three with no dead ends. Calendar or Claude failures never block the app row (tested with network off).
4. Measurements (cold start, screenshots) only where they protect a stated gap, with the method written in the PR.

### M3 (conditional): becoming the default home
Only after M2 and only if the launcher in use does not already do it. A spike, not a build.
1. Spike: on the real device, `adb shell cmd package set-home-activity <component>` is tried; result (works, survives reboot, survives Home press from Netflix and YouTube within 2 s) is recorded. **Result:** superseded on the emulator (set-home-activity does not win against the priority-2 launcher); M3 uses the key filter. On the real TV, check Home from Netflix and YouTube, and during the screensaver (home-over-dream).
2. If it fails: `pm disable-user` on the stock launcher is evaluated; fallback of last resort is an accessibility redirect, with its flash, naming dependence and no boot coverage written down for Brian.
3. Rollback documented and tested before handing over: restore stock home in at most 3 adb steps from a PC; the steps also cover an OTA update undoing the change.
4. Any accessibility service reads no screen content and declares only what it needs (manifest check).
5. Brian chooses after reading side effects; nothing changes on the TV without his OK (hard stop).
Optional weather: free keyless source, "Weather unavailable" on failure; cut if it needs an account or key.

### M4 (later, requested 2026-10-31 21:46Z): PC game streaming
Brian wants to stream games from his PC to the TV with a Bluetooth console controller. We do not build streaming:
Sunshine (free, open source) on the PC and Moonlight (free) on the Google TV do it, and the controller pairs to the
Google TV. Our part: (a) a Games tile in our launcher (M2) that opens Moonlight to his PC; (b) via M1c, "start <game> on
my PC" wakes the PC (Wake-on-LAN) and launches the game. Installing software on his PC is his own step or a hard-stop OK.

## What Brian does, and how long
| Milestone | Brian's steps | Minutes |
|---|---|---|
| M0 | Install Projectivy from the Play Store, arrange apps, report gaps | 30 |
| M1 | Decide ICS vs OAuth; approve sideload once (Developer options, adb from PC or downloader app); give ICS URL or OAuth client via adb push/QR | 30-45 first time |
| M1b | Provide a dedicated, spend-limited Anthropic API key with his typed OK (adb push or QR/phone step); try 5 requests and say which worked | 15 |
| M1c | OK installing the control tool on his PC; enable network debugging on the Google TV and accept the LG pairing prompt on the TV; try 5 commands | 20 |
| M2 | Approve install of updates; list favorites order | 10 |
| M3 | Approve adb change, run rollback test with us | 20 |

## Open questions (none block work; default applies if unanswered)
1. Exact device model and Google TV version? Default: assume Chromecast with Google TV; emulator until checked (Settings > System > About).
2. ICS or OAuth? Default: ICS (simplest), revisit if lag annoys him.
3. Comfortable with Developer options and sideloading for M1? Default: yes, asked once as its own hard stop.
4. One calendar or several? Default: one.
5. Which model/spend cap for Ask Claude? Default: architect proposes the cheapest model that passes the fixtures; a dedicated key with a low monthly cap (a hard stop if it raises a bill).
6. Favorites order (only matters if M2 happens)? Default: Netflix, YouTube, Disney+, Prime Video, Max, Hulu, Apple TV, Spotify, rest alphabetical.

## Success signal
After a week of use Brian says "keep it". No telemetry.

### M5 (PARKED by Brian 2026-10-31 23:43Z until more tokens are available; requested 23:22Z): clean Kodi, run by talking to Claude
Brian wants Kodi on the Google TV that stays clean and easy to navigate, and that he configures by talking to Claude.
Past installs got bogged down by bloat; that comes from third-party "builds" and add-on packs, so we use none.
- Brian installs official Kodi (XBMC Foundation) from the Play Store himself; our tools never install apps.
- Stock skin, home menu trimmed to what he uses, no builds, wizards or unofficial repositories.
- New allowlisted `kodi_*` tools in tv-remote over Kodi's JSON-RPC (home network only, RFC 1918 host, its own
  credentials stored like the other pairing secrets): play/pause/next, search and play from the library, add a
  media source, show or hide home-menu items, switch skin among installed ones. No add-on installs without Brian's OK.
- Brian chose both (2026-10-31 23:25Z): library tools first (sources, search, play, next episode), then our own free-legal-sources add-on (Pluto, Tubi, PBS, Internet Archive). No unlicensed-stream add-ons.
Acceptance: Brian says "play the next episode of <show>" and "hide Music from the menu" and both happen on screen.

### M6 (future, requested by Brian 2026-11-01 15:41Z): change the dashboard by talking to Claude
1. Reskin: "make it dark green", "use a retro theme". The app reads a theme (palette, fonts, tile size) from a stored config; Claude changes it through the TV remote tools, and no app update is needed. Natural first step once M2 ships.
2. "Add <feature> to my dashboard": Claude builds it. Realistic path: the Claude in the dashboard (API) writes the change to the repo, CI builds and checks it, and the signed update installs only after Brian's OK. Lighter variant first: a panel catalog (weather, sports scores, to-dos, etc.) that Claude can switch on and arrange by voice without new code.
3. Security note for later: an assistant that can change installed code is a high-risk path; it needs its own threat model before building.

### Public release idea (Brian 2026-11-01 15:43-15:45Z; not committed, his call later)
Free Play Store app; each user links their own AI provider (Claude, OpenAI, Gemini, local). Design M1b with a swappable provider interface. Public version needs phone/QR setup for keys and no personal data in repo or history. Advice given: finish for Brian first, use ~2 weeks, then a cheap test (public GitHub, bring-your-own key) before the Play Store.
