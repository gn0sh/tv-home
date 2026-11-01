# M2 tile home (PR #3): code + security review

Round 1 on 57b2bf2: APPROVE with should-fixes: qa-own-app-counted, apps-reload-every-start, panel-focus-drop; nits tile-bitmap-resolution, row-empty-vs-loading, focus-steal-on-load, qa-launch-target-unchecked; note clickable-touch-mode. Checked: LEANBACK queries (no QUERY_ALL_PACKAGES), NEW_TASK launch + toast on failure, off-main-thread loading, no HOME category, permissions/exported unchanged.
CI on 57b2bf2: :app compiled; emulator failed at M2 Back (YouTube keeps Back).
Delta on 454b19c: APPROVE. Closed all but panel-focus-drop (nit, focus may drop until next key; accepted). qa-back-via-force-stop (nit): fallback proves our task is underneath, not Back for apps that hold it. home-theme-parse OK (4 KB cap, strict keys, #RRGGBB, bounded ints, all-or-default). CI green on 454b19c.
