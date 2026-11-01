# T9 emulator QA job: security and code review (cc0c776..119bf74)

**Verdict: REQUEST CHANGES** (no security blocker; one false-pass bug).

- `offline-not-really-off` (medium) scripts/ci/emulator-qa.sh:59-71, checks 3 and 9 (154-157, 189-192): CI log of job 124747979442 shows "Active default network: 100" and a warning after every "network off"; telephony absent. AC8 offline cold starts ran online and passed; QA doc rows 3 and 9 overclaim. Fix: fail (not warn) when a default network remains; cut networking a way the user image supports (e.g. `cmd connectivity set-package-networking-enabled false com.gnosh.tvhome`), or mark AC8-offline not verified.
- `qa-silent-degrade` (low) emulator-qa.sh:52-58: expect_text passes with a warning when the uiautomator dump fails. Make it fatal.
- `dump-guard-untested` (low, documented): user image has no su; AC6 "others cannot" rests on the manifest check. QA doc says partial.
- `qa-external-fetch` (info) emulator-qa.sh:194-200: each PR fetches calendar.google.com with a fake token. Harmless.
- `supply-chain-tofu-google` (low, open from prior review): new google() content filters are correct and don't weaken strict verification; sample hash check vs Google still open.

Checked and holds: contents: read only, no secrets in the emulator job, no pull_request_target; real keystore step only on push to main + strict; throwaway key is 1-day, umask 077, password via env, deleted, signed APK not uploaded; all actions SHA-pinned, persist-credentials false; only the PNG is uploaded; quoting OK; disk cleanup on fixed paths.
