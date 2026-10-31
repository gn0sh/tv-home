# tv-home release signing key (T7)

No secrets in this file. Covers security findings #1 (secret via agent transcript) and #9 (CI hardening).

## What the script does
`scripts/new-signing-key.ps1` is meant to be run by Brian only, in his own window. No agent runs it or reads `%USERPROFILE%\tv-home-signing\`. The script's checks (it stops on piped or non-interactive input) are a guard against accidents, not a security boundary; the rule is that Brian runs it himself. It works in Windows PowerShell 5.1 and PowerShell 7.
1. Stops if `gh` or `keytool` is missing or `gh` is not signed in.
2. Refuses if `%USERPROFILE%\tv-home-signing\` already exists (never overwrites), and refuses if any `TVHOME_*` secret already exists on `gn0sh/tv-home` unless you pass `-Rotate`.
3. Generates one random password in-process (32 random bytes). A PKCS12 keystore uses the same password for the store and the key, so it is used for both. keytool gets it through a process-only environment variable, removed as soon as keytool finishes, before any `gh secret set` call (the earlier `gh auth status` and `gh secret list` calls run before the variable exists). Keystore: RSA 4096, 10000 days, alias `tvhome-release`.
4. Saves the password to `password.dpapi` in the same folder, encrypted with Windows DPAPI.
5. Sends `TVHOME_KEYSTORE_B64`, `TVHOME_KEYSTORE_PASSWORD`, `TVHOME_KEY_ALIAS`, `TVHOME_KEY_PASSWORD` (same value as the store password) to `gn0sh/tv-home` with `gh secret set` over stdin as raw UTF-8 bytes (never `--body`, never argv). Names match `.github/workflows/android.yml` and `app/build.gradle.kts`.
6. Prints only `done`, the certificate SHA-256 fingerprint and the backup folder.

## Run it
Run this only after the team says it is merged to `main` (the ask will tell you). Open your own PowerShell window (Windows PowerShell or PowerShell 7, either works; not an agent or Remote Control session), then run these one at a time:
```
Set-ExecutionPolicy -Scope Process Bypass -Force
cd $HOME\tv-home
git pull
.\scripts\new-signing-key.ps1
```
(Adjust the path to wherever tv-home is cloned. The execution-policy change lasts only for this window.) You should see `done`, a line `Certificate SHA-256: AA:BB:...`, and `Backup folder: C:\Users\<you>\tv-home-signing`. Write the fingerprint down; later builds can be checked against it.

## Backup and restore
Do these in your own window right after it prints `done`:
1. Read the password and save it in your password manager (entry name `tv-home signing`):
   `Get-Content $HOME\tv-home-signing\password.dpapi | ConvertTo-SecureString | ForEach-Object { [Net.NetworkCredential]::new('', $_).Password }`
2. Copy the whole `tv-home-signing` folder to a second place (USB stick or your own cloud drive). The keystore is useless without the password.
- `password.dpapi` opens only as your Windows user on this PC. It does not protect against other code running as you, and it does not survive losing the PC; the password manager entry is the real backup. GitHub secrets can't be read back.
- Restore: put the folder back with the keystore and the password from the password manager. The GitHub secrets keep working; nothing to re-run.

## Rotation / lost key
Android will not install an update signed with a different key over the old one: uninstall tv-home on the TV, then install the new build (app data and stored provision values are lost; rerun `scripts/provision.ps1`). To rotate: move the old folder away (keep it), run `.\scripts\new-signing-key.ps1 -Rotate` (it replaces the GitHub secrets), update the password manager entry, install the next signed build after uninstalling. Rotate also if the PC, the backup or the GitHub account is thought compromised. If a run fails part-way, GitHub may hold a mix of old and new secrets: move the folder away and run `.\scripts\new-signing-key.ps1 -Rotate`.

## Residual risk (#9, accepted)
Any workflow merged to `main` can read these secrets. The "Decode signing keystore" and "Build release" steps read them on push to `main`, but a new or changed workflow on `main` could read them too. Environment protection rules may need a paid plan and are not bought. Mitigations: third-party actions are SHA-pinned, no `pull_request_target`, signing runs only on push to main, and only Brian merges to main. Anyone who can push to main can already obtain a signed APK, which is the same trust as owning the repo.
