# Security review: tv-home design (2026-10-31)

Reviewer: security-engineer (opus/high), read-only, design docs at fb24a2a (architecture, ADR 0001-0003, task lists M1/M1b/M1c).
Verdict: **APPROVE WITH CHANGES**. #1 must be fixed in the docs before T7, T8 or B9 runs; #2-#5 must be fixed before their tasks are reviewed. Security re-checks per task (T3, T5, T6, T8, B3, B7, C1, C3).

**Most serious problem:** `secret-via-agent-transcript`.

## Findings
1. **HIGH `secret-via-agent-transcript`** (arch §5, §6; T7, T8, T11, B9, C5). `Read-Host -AsSecureString` cannot prompt inside a Remote Control or agent shell. When it fails, the likely next step is Brian pasting the ICS URL or API key into chat, where it lands in transcripts. T7 as written would also let an agent see the keystore and `gh secret set` passwords. The ICS URL is a non-expiring bearer credential to his whole calendar.
   - Fix: Brian runs `provision.ps1` himself, in his own PowerShell window, never through Claude. Asks give a copy-paste command and never ask for the value.
   - Fix: the T7 script generates passwords in-process and pipes them via stdin to keytool and `gh secret set`, printing nothing. Agents never read `%USERPROFILE%\tv-home-signing\`.
   - Fix: drop the "generate in sandbox" fallback.
2. **MEDIUM `autolaunch-vs-spec-open`** (arch §7, §7b). The 3 s auto-launch contradicts spec M1b AC4. Web search results and now-playing titles are attacker-controlled, so injection could start an attacker's video on the TV.
   - Fix: require an explicit Open press, including in the overlay.
   - Fix: show the title, service and which fallback was used.
   - Fix: add injection fixtures to B-AC3.
3. **MEDIUM `okhttp-key-in-exception`** (arch §3, §4, §7; T3, B3). OkHttp 4.12's `Headers.checkValue` puts the header value in its exception text unless the header is Authorization or a cookie header. `x-api-key` is not exempt. Separately, R8 Log stripping doesn't cover System.out/err or dependency loggers.
   - Fix: trim the value, then apply strict regexes in both the script and ProvisionActivity. Key: `^sk-ant-[A-Za-z0-9_-]{20,256}$`. URL: `^https://calendar\.google\.com/calendar/ical/[A-Za-z0-9%._-]+/private-[0-9a-f]{32}/basic\.ics$`.
   - Fix: catch Throwable at the fetch and assistant boundaries. In release builds, install an uncaught-exception handler that logs class names only.
   - Fix: QA greps System.err, AndroidRuntime and the dropbox `data_app_crash` entries.
4. **MEDIUM `a11y-settings-overwrite`** (arch §7b, B7). `settings put secure enabled_accessibility_services X` replaces the whole list.
   - Fix: append to the list with `:`, set `accessibility_enabled 1`, and save the old value for rollback.
   - Fix: the key filter returns false at once for every key except the long-press, with no buffering or logging. Set `canRetrieveWindowContent=false`, with no gestures and no event types.
   - Fix: no code path from AskResult to `performGlobalAction` or `dispatchGesture`.
5. **MEDIUM `webos-broad-permissions`** (arch §8, ADR 0003). aiowebostv registers about 37 permissions, including WRITE_SETTINGS, input control and toasts, so the stored client key carries all of them.
   - Fix: register with a trimmed permission list (LAUNCH, CONTROL_POWER, CONTROL_AUDIO, CONTROL_INPUT_TV, READ_INPUT_DEVICE_LIST, READ_RUNNING_APPS, READ_POWER_STATE, READ_APP_STATUS). Verify that the TV honours it.
   - Fix: wss on 3001 only, never ws on 3000. Encrypt the key at rest with DPAPI.
6. **LOW `atv-remote-cert-trust`** (C4). Pin the TV's cert fingerprint at pairing; encrypt the client cert key with DPAPI.
7. **LOW `allowlist-not-a-boundary`** (C-AC5). Inside Claude Code the model has Bash, so the tool allowlist binds only MCP-only clients. Document this, and keep `tv_pair` and `lg_power` off auto-allow.
8. **LOW `supply-chain-pinning`** (C1, C5, T1).
   - Commit `uv.lock` with hashes, run with `--locked`, and install uv from a pinned winget version.
   - Gradle: wrapper-validation action, `distributionSha256Sum`, dependency locking and `verification-metadata.xml`.
9. **LOW `ci-hardening`** (T6).
   - Workflow: `permissions: contents: read`, `persist-credentials: false`, signing only on push to main (never `pull_request_target` or `workflow_run`), passwords via env not `-P`, no `--scan`.
   - Checks: the APK grep must unzip and scan dex and resources. Use one fixed fake secret for fixtures.
   - Residual risk: any workflow on main can read the signing secrets (environment protection may need a paid plan). Accept and record.
10. **LOW `provision-extras-retained`** (arch §5). ProvisionActivity: no intent-filter, `excludeFromRecents`, `noHistory`, `setIntent(Intent())` after reading, `finishAndRemoveTask()`. Optional: send the value via stdin to a DUMP-guarded ContentProvider so it never appears as a command-line argument.
11. **LOW `notif-listener-scope`** (arch §7b). The listener sees every notification, including 2FA codes. Don't override `onNotificationPosted`, set `default_filter_types` to the minimum, and get Brian's explicit OK before `allow_listener`.
12. **LOW `deeplink-id-regex`** (arch §7 services.json).
    - Id regexes exclude `/ ? # % : @` and `..`; add a Spotify URI regex; every LaunchStep calls `setPackage`; add these cases as test vectors.
    - Never add `web_fetch`. Show Clarify and NotFound replies as capped plain text.
13. **LOW `daily-cap-persistence`** (B3). Store the per-date counter in `noBackupFilesDir`; the workspace spend limit must exist before the key is provisioned.

## Checked and holds up
- The DUMP guard (shell-only).
- On-device storage: allowBackup=false, noBackupFilesDir, Keystore AES-GCM with a random IV each write, non-debuggable release.
- The calendar fetch design: enum errors, HTTPS before I/O, no SSL redirect, 10 MB cap, URL not cached.
- No calendar data is sent to Anthropic, and Claude's `service` is a strict enum.
- M1c posture: stdio only, no listening port, RFC 1918 host check, no network adb for daily use.
- No screen capture and no QUERY_ALL_PACKAGES.
- Dependencies:
  - biweekly 0.6.8: parse text ICS only, never `parseXml`; cap total instances at 500.
  - OkHttp 4.12: fine apart from #3.
  - androidtvremote2 0.3.2: fine.
  - aiowebostv 0.10.0: fine apart from #5.
  - mcp 2.3.0: acceptable.
  - anthropic-java 3.2.0: a large tree.
- Steelman: running Claude on the PC via M1c keeps the API key and SDK tree off the TV entirely, which is strictly better, so build M1c first.

## Owners
#1 devops-engineer plus manager (ask wording). #2, #4, #10, #11 frontend-engineer. #3, #12, #13 backend-engineer, with T5 for ProvisionActivity. #5, #6, #7 backend-engineer (tv-remote). #8, #9 devops-engineer.
