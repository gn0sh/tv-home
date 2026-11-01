# Signing script PS 5.1 fix (8b6188b): security delta review

**Verdict: APPROVE.** Read-through; not run on Windows 5.1 here.
- Root cause (confirmed on Brian's PC, PSVersion 5.1.26100): New-Item has no -LiteralPath on 5.1. Run stopped before keytool/gh; no folder, no secrets.
- Fix `-Path $dir` is safe: $dir = %USERPROFILE%\tv-home-signing; worst case with [ ] in the profile name is a loud error; line 80 still refuses an existing folder.
- Rest of the script checked for 5.1 / .NET 4.x compatibility (env vars, keytool :env options, Test-Path/Set-Content -LiteralPath, DPAPI, ReadAllBytes, WriteAsync): no other gap found.
- `signing-partial-rerun` (info): a failure after line 118 leaves the folder; rerun needs move-away + -Rotate (documented).
