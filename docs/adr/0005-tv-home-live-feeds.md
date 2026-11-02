# ADR 0005: tv-home M2f live context feeds for the hero

Status: accepted (backend-engineer), 2026-11-01. (Numbered 0005 because 0003 is the remote MCP server ADR.)
Code: `core/.../feeds/`, wiring in `app/.../Hero.kt`, `AppGraph.kt`, `FeedSettings.kt`, `ProvisionProvider.kt`.

## Decision
The hero shows a weather chip, a game-day theme for followed teams, and a rain/snow motif overlay. All fetch, parse,
selection and timing logic is pure `:core` with fixture tests; the app only draws.

## Sources (all free, no key, no account)
| Feed | URL | Notes |
|---|---|---|
| Weather | `https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&current=temperature_2m,weather_code&hourly=weather_code&temperature_unit=fahrenheit&forecast_days=2&timezone=auto` | Documented public API. Free for non-commercial use under their terms; attribution is expected if the data is published (it is not: this is a private TV). |
| Predators (NHL) | `https://api-web.nhle.com/v1/club-schedule/NSH/week/now` | **Unofficial and undocumented.** The NHL publishes no terms for it and may change or block it without notice. |
| Ohio State (ESPN) | `https://site.api.espn.com/apis/site/v2/sports/football/college-football/teams/194/schedule` | **Unofficial and undocumented.** ESPN's terms do not license this endpoint for third-party use; this is a single private household display at about 1 request a minute during games. If either sports API objects or changes shape, that team goes dark. |

**Response shapes are unverified.** The build sandbox's outbound proxy blocked all three hosts (403 on CONNECT), so the
fixtures in `core/src/test/.../feeds/` follow the shapes as documented or remembered, not captured responses. First
on-device check: the hero should show the weather chip within about 2 minutes of setting a location, and a game
line on a day a followed team plays. If a parser rejects the real shape, that feed shows nothing (never a crash) and the
fixture and parser are fixed together. The parsers read the same ESPN fields from the scoreboard shape too, so the
scoreboard URL is a fallback if the team schedule endpoint misbehaves.

## Security (external input)
Exactly three hosts, https only, port 443, checked on every hop (no redirects followed); 10 s connect/read timeouts;
512 KB response cap (decompressed); only a plain `User-Agent: tvhome-hero/1` is sent (no cookies, keys or IDs). The
location is sent at two decimals (about 1 km). Parsing is defensive: a malformed document is "no data", a malformed game
is skipped. The only strings that reach the screen are fixed weather labels, short opponent names and a score/clock
matched by regex, each capped at 40 characters with control characters removed. Results are cached in memory only.

## Refresh and failure modes
Weather every 30 min; schedules every 60 min, or every 60 s while a game is live or within 10 minutes of starting.
After errors the interval doubles per failure (weather and schedules up to 2 h, live polling up to 15 min). A failed
refresh keeps the last good value; weather older than 2 h is dropped; an "upcoming" game 4 h past its start or a "live"
one 6 h past is ignored as stale. No location set: no weather and no weather request. Offline or blocked hosts: the
hero looks exactly as it did in M2e. The feed loops start after the first frame plus a short settle, on a background
dispatcher, so they cannot delay the first frame (AC8).

Theme priority: game day (live > soonest upcoming > final until local midnight) beats holiday beats time of day.
Weather never changes the theme. A game that starts before midnight and ends after it is shown as final only until
midnight of its start day (the rule as specified).

## Setup step (the weather location)
Not in source or git. On the TV, over adb, once (example: Clarksville, TN 37042 is about 36.53, -87.36):

    adb shell content insert --uri content://com.gnosh.tvhome.provision/location --bind lat:d:36.53 --bind lon:d:-87.36

Clear it with `adb shell content delete --uri content://com.gnosh.tvhome.provision/location`. It is read again by the
feed loop within a minute (when none was set) or at the next 30-minute refresh; `adb shell am force-stop com.gnosh.tvhome`
applies it at once. Same DUMP-permission guard as the secret provisioning. Following another team is one row in
`Teams.ALL`.
