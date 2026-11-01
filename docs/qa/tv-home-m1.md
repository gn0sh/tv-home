# QA plan: tv-home M1 (T9, emulator)

Task: T9 in `docs/tasks/tv-home-m1.md`. Acceptance criteria: AC5, AC6, AC8 in `docs/specs/tv-home.md` (M1).
Runs as the `emulator` job in `.github/workflows/android.yml`, which calls `scripts/ci/emulator-qa.sh`.

## How it runs
- Triggers: `pull_request` (so it runs before merge) and `workflow_dispatch`. Not on push to `main` (minutes).
  `needs: app`; the `app` job now also runs on `workflow_dispatch` so a manual run has an APK.
- Device: `reactivecircus/android-emulator-runner`, API 34, `android-tv`, x86, `tv_1080p`, no snapshot, animations off.
- APK: the `tv-home-release-apk` artifact (unsigned release build), zipaligned and signed in the job with a throwaway
  PKCS12 key made by `keytool` (random password in an env var, 1-day validity, deleted after signing). The `TVHOME_*`
  signing secrets are never referenced. `permissions: contents: read`.
- Budget: job timeout 20 minutes. The script stops at the first failed check (`::error::`), then still scans what
  it captured.
- Output: only `tv-home-emulator-screenshot` (the agenda screen PNG, 7 days) is uploaded. Logs and dumps stay on the
  runner; they are scanned, never published.
- Secret material: a DUMMY calendar URL only
  (`https://calendar.google.com/calendar/ical/x%40example.com/private-<32 f>/basic.ics`). Its token equals the
  `FakeSecrets` constant, which `scan-secrets.sh` ignores on purpose, so the script also counts the dummy token and
  `x%40example.com` / `x@example.com` in every captured file. Either finding fails the job.

## Checks
| # | Check | How | T9 item | AC |
|---|---|---|---|---|
| 1 | Installs; launcher entry | `adb install`, `pm path`; `cmd package resolve-activity` for MAIN + LEANBACK_LAUNCHER must give `AgendaActivity` | install | (M1: usable from any launcher) |
| 2 | Not a HOME candidate (reported) | `cmd package query-activities` MAIN + HOME; count printed as a notice, not a failure | - | M1 scope (Home is A2/M2) |
| 3 | Cold start, network off, no cache, not set up | `pm clear`; Wi-Fi, data off and airplane mode on; 3 x (`am force-stop`, `am start -W`): `Status: ok`, process alive, app in front; median `TotalTime` under 2000 ms; uiautomator dump shows "Calendar not set up" | cold start, no-cache | AC8 |
| 4 | Screenshot | `adb exec-out screencap -p` of check 3's screen; PNG signature and over 10 KB | - | AC5 (10-foot look, by eye) |
| 5 | D-pad/Back walk | 12 D-pad keys (up/down/left/right/center): same pid, still in front; then Back (twice at most) must leave the app | D-pad/Back walk | AC5 |
| 6 | `content write` over stdin | `scripts/provision.sh` with the dummy in `PROVISION_VALUE`; must print `Saved.` (`configured=1 last=SAVED_CALENDAR`) | `content write` works | AC6 |
| 7 | DUMP guard, shell side | `adb shell content query` sees `configured=1` and the row has no value | DUMP guard (shell can) | AC6 |
| 8 | DUMP guard, other caller | Only if the image has `su`: `su nobody content query` must not return a row; denial passes, anything else warns | DUMP guard (others cannot), partial | AC6 |
| 9 | Cold start, network off, no cache, URL set | as check 3; screen shows "Can't reach calendar" | cold start, no-cache | AC8 |
| 10 | Failed fetch | network on, launch, 12 s: the dummy fetch fails (404 or network) without a crash | logcat grep input | AC6 |
| 11 | ProvisionActivity | `am start ... --es calendar_url <dummy>`, wait 4 s: not in `dumpsys activity recents`, no record in `dumpsys activity activities`; full `dumpsys activity` is scanned for the dummy (retained extras) | `#10` recents, extras | AC6 |
| 12 | No unexpected crash | after checks 3, 5, 9 and 10, `logcat -b crash` has no line for the package | - | AC5, AC8 |
| 13 | Forced crash | `am crash com.gnosh.tvhome`; prints crash-buffer lines and `data_app_crash` entries (0 expected: `ReleaseCrashHandler` does not delegate) | forced crash, dropbox | AC6 |
| 14 | Secret scan | `scan-secrets.sh` over: streamed logcat (main, system, crash, events; includes `System.err`, `AndroidRuntime`), `logcat -d -b all`, `logcat -b crash`, `dumpsys dropbox --print data_app_crash`, `dumpsys activity`, recents, UI dumps, provision output, the script's console log; plus the dummy count above | logcat, `-b crash`, dropbox, CI log | AC6 |

## Not covered, and why
- **Cold start with a cache** (AC8 "cache" case): making a cache needs one good fetch of a real feed, or writing the
  app's private cache file, which a non-debuggable release build does not allow. Covered by the JVM tests of the cache
  and state reducer; on the device it is checked at T11.
- **Third-party test app vs. the DUMP guard**: building and installing a second APK costs build and emulator minutes.
  Covered instead by the app job's merged-manifest check (provider and ProvisionActivity require
  `android.permission.DUMP`), `ProvisionProvider.enforceShell()` re-checking the uid, and check 8 where `su` exists.
- **Marker repro** (`RuntimeException("MARKER")` from `Handler(mainLooper).post{}`): needs a hook in the app. Not
  added, so app code is unchanged. The `ReleaseCrashHandler` comment already records the expected result (the marker
  reaches `logcat -b crash`), which is why the control is "no secret-bearing exception escapes".
- **Readable at 10 feet**: judged by eye from the screenshot, not asserted.
- **Unzipped APK and CI log scans**: done in the `app` job (`Scan APK ... for secret patterns`).
- **Real Google TV behaviour** (launcher, CEC, Home handling): T11, on Brian's TV.

## First-run risks
- The `android-tv` x86 API 34 image download and cold boot are the slowest part (several minutes); if the job nears
  20 minutes, raise the timeout or add a pinned AVD cache.
- `uiautomator dump` can fail to get an idle state on Compose screens; the script then warns and relies on the resumed
  activity and the screenshot.
- `TotalTime` on a software-rendered emulator may be near 2000 ms; `MAX_COLD_MS` is an env var if the limit needs a
  documented change.
- `am crash` and `su` may be missing on this image; both degrade to a warning.
