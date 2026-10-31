# C1 review (2dd6d92..6d93609): code + security (opus/high): REQUEST CHANGES
80 tests pass; real stdio client confirmed allowlist + range checks on the request path.
Most serious: must-fix `supply-chain-pinning` pyproject.toml:20-24: hatchling build deps (packaging, pathspec, pluggy, trove-classifiers) fetched unhashed on every fresh install. Fix: uv_build backend (tested: no downloads), re-lock. README:38 claim false until then.
Should-fix: `ci-pep668` tv-remote.yml:42 (pip --user likely refused on 24.04; use a venv); `volume-step-cap` server.py:184, devices.py:50 (enforce 60 in the tool, test with non-clamping fake); `unreachable-nothing-sent` server.py:109,168 (report k of N presses); `deeplink-id-regex` server.py:74 (exclude : and .. per #12).
Nits: `search-query-bidi` (exclude Cf), `extra-args-ignored`, `host-net-bcast`, `readme-tense` (DPAPI planned).
Held: stdio only, exact allowlist, type coercion range-checked, config refuses IPv6/mapped/0/127/link-local/CGNAT/hostnames, no keys in errors, 48 locked packages hashed, CI hardening, README #7.
