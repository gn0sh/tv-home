# T8 review (18631fe..89ac042)

## Security review (security-engineer, opus/high, also code lens, 2026-10-31): APPROVE WITH CHANGES
F1 and F2 must be fixed before Brian runs the script. test-provision.sh passes.
Most serious: MEDIUM `provision-fallback-argv` provision.ps1:99-106. `content` exits 0 on errors; when the app is not installed ("Unknown authority") the am start fallback fires and puts the ICS URL on adb.exe's and the TV's command line (visible to Brian's processes, kept by 4688/Sysmon/EDR).
F2 MEDIUM `provision-stale-success` ps1:108-110, sh:55: success checks configured=1 only; a refused rewrite reports success while the old link stays. Require last == SAVED_*.
F3 LOW `feed-check-redirects` ps1:116-118: HttpClient follows redirects (https->http on 5.1); AllowAutoRedirect=false; body read has no timeout.
F4 LOW `adb-path-resolution` ps1:36: use Get-Command adb -CommandType Application .Source.
F5 LOW `invoke-adb-deadlock` ps1:46: read stderr async, WaitForExit timeout.
F6 LOW `adb-left-on` / `clipboard-history` setup doc: turn off network debugging after; clear Win+V history; "nothing shows" is wrong (asterisks).
F7 LOW `provision-test-gaps`: test not in CI; fixture not the FakeSecrets token; no refused-rewrite test; sh trims all whitespace; offline devices counted; '.' in serial regex.
Nit `regex-doc-drift`: API key {20,500} in code vs {20,256} in docs.
Held up: main path never on argv, SecureString bypasses PSReadLine, non-interactive refused, fixed error strings, trim/regex parity, serial validation, -Clear, bash keeps value off argv, device-side guards, doc asks only at the hidden prompt.

Manager decision: delete the am start fallback (F1). content write is the only path; if it is unsupported, T9 finds out and the plan changes then.

## Delta (security-engineer, ec3e692..7b7275d): APPROVE
F1-F7 and regex-doc-drift closed. Reordered stdin refusal not weaker. New CI steps (test-provision.sh, pwsh parse + non-interactive smoke) keep hardening. ps1 re-read for 5.1/7: no errors.
Follow-ups: L1 architecture still described the fallback (fixed by manager in the next commit); L2 `invoke-adb-result-wait` ps1:60 `.Result` with no timeout (devops, with T7); L3 5.1 mid-read cancellation may hang, never leaks (accepted).
T8 passes review. ps1 first actually runs in CI (pwsh step) after Nov 1.
