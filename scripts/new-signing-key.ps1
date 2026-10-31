<#
.SYNOPSIS
  Creates the tv-home release signing key and stores it as GitHub secrets. Run it yourself in your own PowerShell window.
.DESCRIPTION
  Generates random passwords in this process, creates the keystore under %USERPROFILE%\tv-home-signing\ (never
  overwrites), and sends the keystore (base64) and passwords to GitHub with `gh secret set` over stdin. Nothing secret
  is printed or put on a command line. Prints only "done", the certificate SHA-256 fingerprint and the backup location.
  A DPAPI-protected copy of the passwords is saved next to the keystore (readable only by your Windows user).
  Never run this through an agent, a chat or a Remote Control session.
#>
[CmdletBinding()]
param(
    [string]$Repo = 'gn0sh/tv-home',
    [string]$Alias = 'tvhome-release'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Fail([string]$msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# Checked first, before anything else happens.
if ([Console]::IsInputRedirected -or -not [Environment]::UserInteractive) {
    Fail 'Run this in your own PowerShell window. It will not run from a pipe, file or agent session.'
}
if ($Alias -notmatch '^[A-Za-z0-9._-]+$') { Fail 'Alias has odd characters.' }
if ($Repo -notmatch '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$') { Fail 'Repo must look like owner/name.' }

$gh = Get-Command gh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $gh) { Fail 'gh (GitHub CLI) not found on PATH.' }
$kt = Get-Command keytool -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $kt) { Fail 'keytool not found on PATH. Install a JDK (or add Android Studio''s jbr\bin to PATH).' }

& $gh.Source auth status *> $null
if ($LASTEXITCODE -ne 0) { Fail 'gh is not signed in. Run: gh auth login' }

$dir = Join-Path $env:USERPROFILE 'tv-home-signing'
$ksPath = Join-Path $dir 'tv-home-release.jks'
$pwPath = Join-Path $dir 'passwords.dpapi'
if ((Test-Path -LiteralPath $dir) -or (Test-Path -LiteralPath $ksPath)) {
    Fail "$dir already exists. Not overwriting. Move it away yourself if you really want a new key (see docs/release/tv-home-signing.md, Rotation)."
}

function New-RandomPassword([int]$Bytes = 32) {
    $buf = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buf) } finally { $rng.Dispose() }
    # Base64url without padding: letters, digits, - and _ only, so it is safe in every tool.
    [Convert]::ToBase64String($buf).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# Sends one value to a GitHub secret on stdin (never argv, never --body).
function Set-GhSecret([string]$Name, [string]$Value) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $gh.Source
    $psi.Arguments = "secret set $Name --repo $Repo"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $o = $p.StandardOutput.ReadToEndAsync()
    $e = $p.StandardError.ReadToEndAsync()
    $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($Value)
    try {
        $p.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $p.StandardInput.Close()
        if (-not $p.WaitForExit(60000)) { try { $p.Kill() } catch { }; throw 'gh timed out' }
        $p.WaitForExit()
        if (-not $o.Wait(10000) -or -not $e.Wait(10000)) { throw 'gh output read timed out' }
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
    if ($p.ExitCode -ne 0) { throw "gh secret set $Name failed (exit $($p.ExitCode))" }
}

$storePw = $null
$keyPw = $null
try {
    $storePw = New-RandomPassword
    $keyPw = New-RandomPassword
    New-Item -ItemType Directory -Path $dir | Out-Null

    $env:TVHOME_GEN_STOREPASS = $storePw
    $env:TVHOME_GEN_KEYPASS = $keyPw
    $ktArgs = @(
        '-genkeypair', '-v', '-keystore', $ksPath, '-storetype', 'PKCS12',
        '-alias', $Alias, '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000',
        '-dname', 'CN=tv-home release, O=gnosh',
        '-storepass:env', 'TVHOME_GEN_STOREPASS', '-keypass:env', 'TVHOME_GEN_KEYPASS'
    )
    & $kt.Source @ktArgs *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $ksPath)) { throw 'keytool failed to create the keystore' }

    # Certificate fingerprint (public information). -list output never contains the passwords.
    $listOut = & $kt.Source -list -v -keystore $ksPath -alias $Alias '-storepass:env' TVHOME_GEN_STOREPASS 2>&1 | Out-String
    $fp = $null
    if ($listOut -match 'SHA256:\s*([0-9A-Fa-f:]{95})') { $fp = $Matches[1] }

    # DPAPI-protected backup of the passwords (current Windows user only on this PC).
    $backup = "storepass=$storePw`nkeypass=$keyPw`nalias=$Alias`n"
    $backup | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $pwPath -Encoding ASCII
    $backup = $null

    $b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($ksPath))
    Set-GhSecret 'TVHOME_KEYSTORE_B64' $b64
    Set-GhSecret 'TVHOME_KEYSTORE_PASSWORD' $storePw
    Set-GhSecret 'TVHOME_KEY_ALIAS' $Alias
    Set-GhSecret 'TVHOME_KEY_PASSWORD' $keyPw
    $b64 = $null

    Write-Host 'done'
    if ($fp) { Write-Host "Certificate SHA-256: $fp" } else { Write-Host 'Certificate SHA-256: (could not read; run keytool -list -v yourself)' }
    Write-Host "Backup folder: $dir"
    Write-Host 'Copy that whole folder to a second place (USB stick or your own cloud drive). Passwords in it open only as your Windows user on this PC; see docs/release/tv-home-signing.md.'
} catch {
    # Fixed text only: never the exception message, which could echo arguments.
    Write-Host 'ERROR: failed. Nothing was printed that is secret. If GitHub secrets were only partly set, rerun after moving the folder away (see docs/release/tv-home-signing.md, Rotation).' -ForegroundColor Red
    exit 1
} finally {
    Remove-Item Env:\TVHOME_GEN_STOREPASS -ErrorAction SilentlyContinue
    Remove-Item Env:\TVHOME_GEN_KEYPASS -ErrorAction SilentlyContinue
    $storePw = $null
    $keyPw = $null
}
