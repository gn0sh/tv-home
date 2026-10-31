# Spec: tv-home

Status: draft v1, 2026-10-31. Priority: hip pocket (low; built in idle time).

## Goal check
Brian gets his own Google TV home screen: one row of his streaming apps to open with the remote, plus a panel showing today's calendar.
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
| A2 | Google TV has no setting to pick another home app. A custom launcher becomes "home" either via an accessibility service that catches the Home button, or by disabling the stock launcher over adb | Unverified on his device | Medium |
| A3 | Installing our APK means sideloading: Developer options + adb over the network from his PC, or a downloader app. Installing on his TV is a hard stop (needs his OK) | Unverified on his device | High |
| A4 | Builds run in GitHub Actions (no Android SDK in our cloud sandbox); pure logic (app ordering, ICS parsing, date windows, staleness) must be unit-testable on a plain JVM | Verified (sandbox) | High |
| A5 | Calendar via Google Calendar "Secret address in iCal format" (ICS URL). The URL is a secret: obtaining it is a hard stop needing Brian's typed OK | Unverified | High |

Calendar decision: recommend (a) ICS secret URL over (b) Google OAuth device flow. Why: no Google Cloud project, OAuth client, consent screen or token refresh to build and maintain; read-only by construction; one pasted string. Cost: anyone with the URL can read the calendar, so it must live only in on-device private storage, never in the repo, logs or CI. (b) is the fallback if Brian won't use a secret link; it needs an OAuth client and credential step from him.

## Milestones and user stories

### M1: Thin slice (real data: his installed apps)
As Brian, I want a screen of my installed streaming apps, favorites first, so I can launch what I watch in a click or two.

Acceptance criteria
1. The home screen lists every app installed on the TV that has a TV launcher entry (leanback launcher intent), and no app without one.
2. Apps named in a favorites list appear first, in the configured order; all others follow alphabetically (case-insensitive). Unit test on plain JVM covers ordering, including favorites not installed (skipped) and duplicates.
3. Pressing OK/Select on a tile launches that app; Back from the app returns to tv-home.
4. D-pad Left/Right/Up/Down moves focus between tiles with no dead ends and no focus trap; focus never lands on nothing after launch/return.
5. The focused tile is visibly distinct (scale and outline) at 10 feet; verified by screenshot on a 1080p emulator and on the device.
6. A clock shows local time, updating at least every 60 s, correct after a timezone change.
7. An app installed or removed while tv-home is open appears or disappears within 5 s of returning to the screen.
8. Favorites order is changed by editing one config file/setting in the repo or on-device, without code changes, and is applied on next launch.
9. Cold start to first rendered tile row in under 2 s on the real device (measured, stated in the PR).
10. The app makes no network calls in M1 (verified by manifest: no INTERNET permission).
Done also needs: APK built by GitHub Actions; installed on the TV only after Brian's OK.

### M2: Calendar panel
As Brian, I want today's and tomorrow's events beside my apps, so I know my day at a glance.

Acceptance criteria
1. Given an ICS feed, the panel shows today's and tomorrow's events with start time (local zone) and title; all-day events are labelled "All day"; sorted by start time. Unit tests use fixture ICS files.
2. Recurring events (RRULE incl. exceptions/EXDATE) and events in other timezones render at the correct local time; fixtures cover weekly recurrence, a cancelled instance and a UTC event.
3. Events are fetched at launch and then at most every 15 min while visible; the last good result is cached on device and shown immediately on start.
4. If the fetch fails and a cache exists, the panel shows the cached events plus "Updated N min ago" (or hours/days); stale means older than 60 min, then the label is visually emphasised.
5. If the fetch fails and there is no cache, the panel shows a plain-language error ("Can't reach calendar") and the app grid still works. A malformed feed shows a distinct "Calendar data unreadable" message.
6. If no ICS URL is configured, the panel shows "Calendar not set up" and how to set it (one line).
7. With no events, the panel shows "Nothing scheduled today".
8. The ICS URL is stored only in private on-device storage; it appears in no log line, crash report, repo file or CI output (grep check on repo and a log capture).
9. Network call uses HTTPS only; a calendar failure never blocks or delays app tiles (grid renders under 2 s with the network off).

Hard stop: Brian provides the ICS URL himself, with his typed OK, via on-device entry or a local file. The team never asks for it in chat or commits it.

### M3: Becoming the default home; optional weather
As Brian, I want pressing Home to bring me to tv-home, so it behaves like my real home screen.

Acceptance criteria
1. An accessibility service catches the Home button and opens tv-home from other apps; enabling it is a documented, step-by-step manual setup for Brian. If the service is disabled, tv-home still works as a normal app.
2. Pressing Home inside a streaming app returns to tv-home within 2 s on the real device (measured on Netflix and YouTube).
3. A documented rollback restores the stock launcher in at most 3 steps (disable service, or re-enable stock launcher via adb); tested before it is handed to Brian.
4. The service reads no screen content and requests only the Home-key capability (manifest check).
5. If A2 turns out false on his device (Home cannot be caught), the fallback path (adb disable of the stock launcher) is documented with its risks, and Brian chooses.
6. Optional weather: current temperature and condition for a configured location from a free, keyless source; failure shows "Weather unavailable" and never affects other panels. Cut if the source needs an account or key.

## Open questions (none block work; default applies if unanswered)
1. Which exact device model is it, and what Android/Google TV version? Default: assume Chromecast with Google TV; M1 targets API 29+ and is tested on an emulator until the device is checked (Settings > System > About).
2. Which apps are favorites, and in what order? Default: Netflix, YouTube, Disney+, Prime Video, Max, Hulu, Apple TV, Spotify, then the rest alphabetical.
3. Calendar: ICS secret URL (recommended) or OAuth? Default: ICS; Brian gives the URL himself later. M2 is built and tested on fixtures meanwhile.
4. Is he comfortable enabling Developer options and sideloading, and enabling an accessibility service for M3? Default: yes for M1/M2; M3 is asked again with exact steps once M2 ships.
5. Does he want only his primary Google calendar, or several? Default: the one ICS feed (one calendar).

## Success signal
After M3, Brian uses tv-home as his daily home screen for 2 weeks with no revert to stock launcher; a measure of that is his say-so, not telemetry (no analytics).
