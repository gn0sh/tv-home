# Platform facts: Brian's setup (tv-home)

Only facts someone checked. "Verified" says how; "Unverified" is a working assumption to confirm before it shapes a design.
IPs come from DHCP and can drift. No secrets belong in this file.

## Devices and network
| Fact | Status | How / source |
|---|---|---|
| Google TV (the app's target) is at 192.168.68.71, adb port 5555 | Verified 2026-11-01 | Installs and provisioning worked against it; same default in `scripts/tv-home-takeover.ps1`; team memory `lg-tv-customization.md` (15:25Z) |
| LG BX OLED is at 192.168.68.80 (pairing over secure wss port 3001; ports 3000 and 3001 open when on) | Verified 2026-11-01 | Found and paired by RC, volume tested 7 to 8 to 7 (memory 19:30Z, 19:50Z). The earlier .61 and .81 guesses were wrong |
| Vizio TV address | Unverified | Docs only give 192.168.68.90 as an example; Brian reads it from the Vizio's Network Information (`docs/user/tv-home-setup.md`) |
| Everything is on one home LAN, 192.168.68.0/24 | Verified (inferred from the above addresses working from his PC) | Same subnet for TV, LG and PC |
| adb (Android platform-tools) lives under `%LOCALAPPDATA%\tv-home-tools` on his PC, not on PATH | Verified 2026-11-01 | Memory 15:25Z; every script finds it with `Get-ChildItem ... -Filter adb.exe -Recurse` (`tv-home-takeover.ps1`, `tv-setting.ps1`) |
| `apksigner`, `aapt` and Java are in the tools folder | Unverified | Platform-tools does not include them; `install-tv-home.ps1` uses apksigner only if present and otherwise reads the APK itself |
| The GitHub CLI (`gh`) is installed and signed in on his PC | Unverified | Not checked; `install-tv-home.ps1 -Fetch` says what to do by hand if it is missing |
| TV Home runs on a Google TV (Android 12+, minSdk 31, targetSdk 34) | Verified | `app/build.gradle.kts` |

## His PC
| Fact | Status | How / source |
|---|---|---|
| Windows PC, Windows PowerShell 5.1 target; scripts are allowed (CurrentUser, RemoteSigned) | Verified | The signing script needed a 5.1 fix (PR #2); the updater and installer run from the repo; skill `brian-pc-steps` |
| Remote Control sessions on his PC are blocked from running `adb install` and from changing TV settings over adb; read-only adb works | Verified 2026-11-01 and 2026-11-02 | Classifier denied M1b installs ("Modify Shared Resources", "Code from External"); memory 19:30Z, 20:30Z |
| The release APK is the `tv-home-release-apk` artifact of the `android` workflow's push-to-main run, kept 7 days, signed with Brian's key | Verified | `.github/workflows/android.yml` (Upload APK step, `retention-days: 7`) |
| Release signing cert SHA-256 is `18:E2:7C:A1:DF:5A:EA:B8:4C:E4:65:8E:0E:A3:94:02:67:77:1F:71:DE:9C:4B:49:07:96:F2:7C:2C:4B:64:2A` | Verified 2026-11-01 | Checked on the M1 release APK (`docs/status.md`). The signing key itself is in his profile and is never read by the team |
| Scripts read the TV's address from a default or a parameter (`-Serial`), and the installer from `%LOCALAPPDATA%\tv-home-tools\tv-home-install.json` | Verified | The scripts in `scripts/` |

## Process
| Fact | Status | How / source |
|---|---|---|
| Brian merges to `main` himself in this repo; the team does not | Verified | Team memory 2026-11-01 15:25Z ("Brian merges to main himself"); `docs/status.md` waiting-on-CEO line |
| Only reviewed changes reach `main`, so a self-updating script that fast-forwards `main` is safe here | Verified by the rule above | `update-tv-remote.ps1` only pulls `main`, `--ff-only` |

## Platform limits (Google TV)
| Fact | Status | How / source |
|---|---|---|
| HOME, SEARCH and ASSIST key events never reach an app, not even an accessibility key filter | Verified (API 34 emulator in CI) | `docs/status.md` blockers, PR #15 CI |
| The stock launcher wins HOME: a HOME alias plus `set-home-activity` loses to its priority-2 HOME filter | Verified (API 34 emulator in CI) | Same; so the app watches for the TV home and then opens the dashboard (a brief flash of the TV's home first) |
| The mic button cannot be taken over; it stays Google Assistant | Verified (platform limit) | `docs/status.md` stage line |
| Behaviour on Brian's real Google TV (launcherx, Home watch) | Unverified | The on-TV checks in spec M3.1 are open |
| Replacing the Google TV OS is not worthwhile (locked bootloader, DRM apps lose HD or stop working) | Judgment from research | Advice to Brian, memory 2026-11-01 21:25Z |

## TV control
| Fact | Status | How / source |
|---|---|---|
| HDMI-CEC power works: both TVs sleep, and the Google TV remote wakes both | Verified 2026-11-01 | Tested on the real TVs (memory 03:26Z) |
| The LG BX ignores CEC volume and mute, even from its physical remote | Verified 2026-11-01 | Same test |
| LG volume therefore goes through the PC remote over wss 3001 (paired) | Verified | Memory 19:50Z |
| CEC volume from the Google TV is a setting: `scripts/tv-setting.ps1 cec-volume on\|off` | Verified | The script and `docs/user/tv-home-setup.md` |
| Vizio volume goes over SmartCast once paired; unpaired, Vol- and Mute fall back to CEC and the Vizio may ignore them | Verified from docs, pairing not confirmed on his Vizio | `docs/user/tv-home-setup.md` |
