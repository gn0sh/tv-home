# tv-home release signing key (T7)

No secrets in this file. Covers security findings #1 (secret via agent transcript) and #9 (CI hardening).

## What the script does
`scripts/new-signing-key.ps1` is run by Brian only. No agent runs it or reads `%USERPROFILE%\tv-home-signing\`.
1. Refuses to run from a pipe, file or agent session; stops if `gh` is missing or not signed in, or `keytool` is missing.
2. Refuses if `%USERPROFILE%\tv-home-signing\` already exists (never overwrites).
3. Generates two random passwords in-process (32 random bytes each) and creates a PKCS12 keystore there: RSA 4096, 10000 days, alias `tvhome-release`. Passwords reach keytool through process-only environment variables (`-storepass:env`, `-keypass:env`), cleared afterwards.
4. Saves the passwords to `passwords.dpapi` in the same folder, encrypted with Windows DPAPI (`ConvertFrom-SecureString`).
5. Sends `TVHOME_KEYSTORE_B64`, `TVHOME_KEYSTORE_PASSWORD`, `TVHOME_KEY_ALIAS`, `TVHOME_KEY_PASSWORD` to `gn0sh/tv-home` with `gh secret set` over stdin (never `--body`, never argv). These names match `.github/workflows/android.yml` and `app/build.gradle.kts`.
6. Prints only `done`, the certificate SHA-256 fingerprint and the backup folder.

## Run it
Open your own PowerShell window (not an agent or Remote Control session), then:
```
cd $HOME\tv-home; git pull; .\scripts\new-signing-key.ps1
```
(Adjust the path to wherever tv-home is cloned.) You should see `done`, a line `Certificate SHA-256: AA:BB:...`, and `Backup folder: C:\Users\<you>\tv-home-signing`. Write the fingerprint down; later builds can be checked against it.

## Backup and restore
- Copy the whole `tv-home-signing` folder to a second place (USB stick or your own cloud drive). The keystore file is useless without the passwords.
- `passwords.dpapi` opens only as your Windows user on this PC. If the PC is lost, the copy cannot be read elsewhere. In that case the keystore plus the passwords stored in GitHub secrets (write-only, not readable) are not recoverable, so treat it as rotation.
- To read the passwords on this PC (in your own window): `Get-Content $HOME\tv-home-signing\passwords.dpapi | ConvertTo-SecureString | ForEach-Object { [Net.NetworkCredential]::new('', $_).Password }`
- Restore: the folder back at the same path; re-run nothing. GitHub secrets keep working.

## Rotation / lost key
Android will not install an update signed with a different key over the old one: uninstall tv-home on the TV, then install the new build (app data and the stored provision values are lost; rerun `scripts/provision.ps1`). To rotate: move the old folder away (keep it), run the script again, install the next signed build after uninstalling. Rotate also if the PC, the backup or the GitHub account is thought compromised.

## Residual risk (#9, accepted)
Any workflow merged to `main` can read these secrets; environment protection rules may need a paid plan and are not bought. Mitigations: signing secrets are read only in the "Build release" steps on push to main, third-party actions are SHA-pinned, no `pull_request_target`, and only Brian merges to main. Anyone who can push to main can already get a signed APK, which is the same trust as owning the repo.
