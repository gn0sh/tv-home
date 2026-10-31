# T7 review (e7ff153..67832a4)

## Security review (security-engineer, opus/high, also code lens, 2026-10-31): REQUEST CHANGES
No secret leaks. Two bugs stop it producing a working setup.
Most serious: HIGH `p12-keypass-ignored` new-signing-key.ps1:80,84,89,100,108. PKCS12 ignores -keypass (warning hidden by line 91); TVHOME_KEY_PASSWORD would not open the key, so the first signed CI build fails. Reproduced on JDK 21. Fix: one password for store and key.
MEDIUM `ps51-native-stderr` lines 17,33,91,95: Windows PowerShell 5.1 with ErrorActionPreference=Stop stops on keytool's stderr ("Generating..."); fails safe.
LOW `stdin-bom` 53-66: on .NET Framework the StandardInput writer may add a BOM; secrets would not decode. provision.ps1 shares the pattern.
LOW `env-lifetime` 83-84,120: TVHOME_GEN_* inherited by gh child processes; remove right after keytool.
LOW `remote-overwrite`: run elsewhere silently replaces live GitHub secrets; check gh secret list, require -Rotate.
LOW `dpapi-backup-scope`: DPAPI doesn't protect against code running as Brian, nor survive PC loss; add "save passwords in your password manager" step.
LOW `doc-steps`: execution-policy line, which PowerShell, overclaim about refusing agent sessions, residual risk omits "Decode signing keystore" step.
INFO: add the script to the CI non-interactive smoke; module logging (4103) would log ConvertTo-SecureString -AsPlainText input (off by default).
Held: no secret on argv, nothing printed, names/base64 match workflow, keystore ~5 KB, fingerprint regex, overwrite refusal first, finally clears env, provision.ps1 timeout fix correct.

Manager decision: support both Windows PowerShell 5.1 and 7 (5.1 is what Windows ships). Run keytool via ProcessStartInfo (no stderr-as-error), write secrets to gh as raw UTF-8 bytes on StandardInput.BaseStream (no BOM) in both scripts.
