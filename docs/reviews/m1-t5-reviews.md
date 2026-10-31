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
