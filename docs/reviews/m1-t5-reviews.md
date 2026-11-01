# T5 reviews (ec37129..1c81bc8)

## Security review (security-engineer, opus/high, 2026-10-31): APPROVE WITH CHANGES (release OK with follow-ups)
Tests: 238/238.
Most serious: MEDIUM `crash-prehandler-logs-message` TvHomeApp.kt:21-37 and architecture section 5 (#3). Since Android 8, RuntimeInit.LoggingHandler logs "FATAL EXCEPTION" with message and stack to logcat -b crash before any custom handler. The custom handler does avoid the dropbox data_app_crash entry. Coroutine errors on API 28+ skip system logging; Looper/Compose/raw-thread errors are logged in full. Low risk today (fetch, provisioning, Keystore, cache paths catch everything). Repro: release build, throw RuntimeException("MARKER") from Handler(mainLooper).post{}, `adb logcat -b crash -d | grep MARKER`. Fix: correct KDoc + arch section 5; the real control is "no exception carrying a secret escapes"; T9 adds the marker repro and greps logcat -b crash.
Lower:
1. LOW `manifest-merged-not-checked` AppManifestTest.kt:42: source manifest only; AndroidX merges profileinstaller receiver (DUMP-guarded), InitializationProvider, DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. Fix (T6): CI asserts on merged release manifest.
2. LOW `backup-rules-no-path` backup_rules.xml:4-8, data_extraction_rules.xml:5-16: add path="." (lint may fail). Defence in depth (secrets in noBackupFilesDir).
3. LOW `app-deps-unverified` android.yml:34-36: :app lenient, no AndroidX entries; regenerate on CI before first signed release. Version downgrades unrecorded.
4. INFO `provision-intent-retained-on-relaunch` ProvisionActivity.kt:31: harmless.
Held up: Keystore AES-256-GCM random IV, tag + AAD, no plaintext fallback; atomic noBackupFilesDir storage; ProvisionActivity flags + DUMP + revalidation; ProvisionProvider DUMP + uid check, 1 KiB strict UTF-8 pipe, query 0/1 only; manifest (allowBackup=false, no cleartext, no QUERY_ALL_PACKAGES, non-debuggable); R8 strips Log + println; env-only signing; no saved state holding the URL.

## Code review (code-reviewer, opus/high, 2026-10-31): REQUEST CHANGES (1 must-fix)
Tests: 238/238. :app read by hand.
Most serious: must-fix `ci-core-includes-app` settings.gradle.kts:58-64 + android.yml core/live jobs. ubuntu-latest sets ANDROID_HOME, so :app is included in the core job; configuring it resolves AGP under strict verification with no AGP/Compose entries, failing before tests. Fix: include :app only on explicit opt-in (e.g. TVHOME_BUILD_APP=true in the app job), keeping the CI-no-SDK error inside that branch.
Should-fix:
2. `initial-focus-offscreen` AgendaScreen.kt:190-197: withFrameNanos then requestFocus before the scrolled row is attached; focus lost on busy days. Fix: wait for visibleItemsInfo to contain the item.
3. `release-crash-prehandler` TvHomeApp.kt:113-124 (= security's crash-prehandler-logs-message).
4. `provision-no-live-refresh` AgendaSession.kt:50-52: hasSecret read only at ON_START; content write provisioning shows "not set up" until relaunch. Fix: re-read on IO every tick.
Nits: `compile-sdk-drift` app/build.gradle.kts:14 (35 vs workflow's android-34); `app-lockfile-missing` app/build.gradle.kts:81; `row-key-by-index` AgendaScreen.kt:116,120 (key by event identity); ProvisionActivity.kt:32 Keystore/fsync on main; ProvisionProvider.kt:86 write after content write returns, query can race.
Held up: API 31 availability, tv-material/Compose API use at BOM 2024.12.01, lifecycle + cancellation, secrets/manifest, lint.

## Fix review (code-reviewer, opus/high, fresh because CI changed, 5175b0b..5d0fc33): APPROVE WITH NITS
Closed: ci-core-includes-app, compile-sdk-drift, initial-focus-offscreen, provision-no-live-refresh, backup-rules-no-path, crash-prehandler wording, manifest-merged-not-checked (fails closed on stubbed bad manifests). CI hardening intact.
Should-fix `app-optin-doc-stale` architecture line 41: fixed by manager in the next commit.
Open nits (deferred to the next app task): `row-key-day-rollover` AgendaScreen.kt:114-128 (key by date, not t/m); `apkanalyzer-guard` android.yml:216; `secret-change-same-value-state` AgendaSession.kt:66-71 (re-provisioning a new URL can wait up to 15 min).
T5 passes review.
