# Security review: build and CI changes ca7baa4..e241e86 (excluding tv-remote/ and docs/)

security-engineer, 2026-11-01 (opus, high). Verdict: **APPROVE** (no critical, high or medium findings).

Findings, with what was done about each:
- `supply-chain-tofu-google` (low): about 292 Google Maven checksums are trust-on-first-use from one CI run, and `google()` had no content filter on CI. **Fixed:** `google()` is now limited to com.android.*, com.google.* and androidx.* on CI and in the root buildscript. **Open:** compare a sample of the Google hashes against Google's published checksums from a machine that can reach Google Maven.
- `buildscript-unlocked` (low): the root buildscript classpath has no dependency locking. **Deferred:** strict verification already fails closed on drift; logged as tech debt.
- `stale-lenient-scaffold` (low/info): the regeneration steps and the "lenient" comment were stale. **Fixed:** both removed.
- `signing-now-live` (info): strict verification means the first push to main signs the build. This is intended; the residual risk was already accepted (#9).
- `emulator-stdout-unscanned` (info): only the dummy FakeSecrets value exists in that job. **Accepted.**

Checked and holding:
- **Metadata:** no trust or ignored-keys weakening.
- **Hand-added entries:** all 4 match Maven Central, as did a sample of 12 Central artifacts.
- **Strict mode:** in effect in every job.
- **AGP:** gated to the app job.
- **Write-back job:** fully removed.
- **Triggers and permissions:** contents: read only; no pull_request_target.
- **Pins:** every action is pinned to a full SHA.
- **Emulator key:** a throwaway key; no secrets involved.
- **Secret scan:** the tripwire is intact.
