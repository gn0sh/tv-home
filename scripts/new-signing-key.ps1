<#
.SYNOPSIS
  Creates the tv-home release signing key and stores it as GitHub secrets. Run it yourself in your own PowerShell window.
.DESCRIPTION
  Works in Windows PowerShell 5.1 and PowerShell 7. Generates one random password in this process, creates the keystore under %USERPROFILE%\tv-home-signing\ (never
  overwrites), and sends the keystore (base64) and passwords to GitHub with `gh secret set` over stdin. Nothing secret
  is printed or put on a command line. Prints only "done", the certificate SHA-256 fingerprint and the backup location.
  If any TVHOME_* secret already exists on GitHub it stops, unless you pass -Rotate.
  A DPAPI-protected copy of the password is saved next to the keystore (readable only by your Windows user).
  Run it only in your own window; never through an agent, a chat or a Remote Control session.
#>
[CmdletBinding()]
param(
    [string]$Repo = 'gn0sh/tv-home',
    [string]$Alias = 'tvhome-release',
    [switch]$Rotate
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

# Runs a native tool via ProcessStartInfo: stdout/stderr read asynchronously, every wait has a timeout, success is
# judged by exit code only (stderr text is never an error; that breaks Windows PowerShell 5.1). $Args are fixed
# strings, never secrets. $Stdin is raw bytes (no BOM, no newline) or $null. Returns Code and Text (Text only for
# callers that need non-secret output).
function Invoke-Native([string]$Exe, [string[]]$ToolArgs, [byte[]]$Stdin = $null, [int]$TimeoutMs = 60000) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = ($ToolArgs | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    # Windows PowerShell 5.1: Process.Start wraps stdin in a writer using Console.InputEncoding, which can emit a UTF-8
    # BOM. Use BOM-less UTF-8 for the Start call only (the set can throw when there is no console).
    $oldEnc = $null; $swapped = $false
    if ($PSVersionTable.PSVersion.Major -lt 6) {
        try { $oldEnc = [Console]::InputEncoding; [Console]::InputEncoding = (New-Object System.Text.UTF8Encoding($false)); $swapped = $true } catch { }
    }
    try { $p = [System.Diagnostics.Process]::Start($psi) }
    finally { if ($swapped) { try { [Console]::InputEncoding = $oldEnc } catch { } } }
    $o = $p.StandardOutput.ReadToEndAsync()
    $e = $p.StandardError.ReadToEndAsync()
    try {
        if ($null -ne $Stdin) {
            $w = $p.StandardInput.BaseStream.WriteAsync($Stdin, 0, $Stdin.Length)
            if (-not $w.Wait($TimeoutMs)) { throw 'stdin write timed out' }
            $p.StandardInput.BaseStream.Flush()
        }
        $p.StandardInput.Close()
        if (-not $p.WaitForExit($TimeoutMs)) { throw 'timed out' }
        $p.WaitForExit()
        if (-not $o.Wait($TimeoutMs) -or -not $e.Wait($TimeoutMs)) { throw 'output read timed out' }
    } catch {
        try { $p.Kill() } catch { }
        return [pscustomobject]@{ Code = -1; Text = '' }
    }
    [pscustomobject]@{ Code = $p.ExitCode; Text = ($o.Result + $e.Result) }
}

if ((Invoke-Native $gh.Source @('auth', 'status') $null 30000).Code -ne 0) { Fail 'gh is not signed in. Run: gh auth login' }

$dir = Join-Path $env:USERPROFILE 'tv-home-signing'
$ksPath = Join-Path $dir 'tv-home-release.jks'
$pwPath = Join-Path $dir 'password.dpapi'
if ((Test-Path -LiteralPath $dir) -or (Test-Path -LiteralPath $ksPath)) {
    Fail "$dir already exists. Not overwriting. Move it away yourself if you really want a new key (see docs/release/tv-home-signing.md, Rotation)."
}

# Do not silently replace live secrets (for example when run on another PC).
$ls = Invoke-Native $gh.Source @('secret', 'list', '--repo', $Repo) $null 30000
if ($ls.Code -ne 0) { Fail 'Could not list GitHub secrets (is the repo name right and are you allowed to change its secrets?).' }
if (($ls.Text -match '(?m)^TVHOME_') -and -not $Rotate) {
    Fail 'TVHOME_* secrets already exist on GitHub. Not replacing them. If you mean to rotate the key, run again with -Rotate (see docs/release/tv-home-signing.md, Rotation).'
}

function New-RandomPassword([int]$Bytes = 32) {
    $buf = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buf) } finally { $rng.Dispose() }
    # Base64url without padding: letters, digits, - and _ only, so it is safe in every tool.
    [Convert]::ToBase64String($buf).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# Sends one value to a GitHub secret on stdin as raw UTF-8 bytes (no BOM, no newline; never argv, never --body).
function Set-GhSecret([string]$Name, [string]$Value) {
    $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($Value)
    try {
        $r = Invoke-Native $gh.Source @('secret', 'set', $Name, '--repo', $Repo) $bytes 60000
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
    if ($r.Code -ne 0) { $script:GhErr = $r.Text.Trim(); throw "gh secret set $Name failed" }
}

$pw = $null
$step = 'start'
$script:GhErr = ''
try {
    # One password for the store and the key: PKCS12 keystores ignore a separate key password.
    $step = 'generate password'
    $pw = New-RandomPassword
    New-Item -ItemType Directory -LiteralPath $dir | Out-Null

    $step = 'keytool'
    $env:TVHOME_GEN_PASS = $pw
    try {
        $k = Invoke-Native $kt.Source @(
            '-genkeypair', '-keystore', $ksPath, '-storetype', 'PKCS12',
            '-alias', $Alias, '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000',
            '-dname', 'CN=tv-home release, O=gnosh',
            '-storepass:env', 'TVHOME_GEN_PASS', '-keypass:env', 'TVHOME_GEN_PASS') $null 180000
        # Certificate fingerprint (public information); -list output never contains the password.
        $l = Invoke-Native $kt.Source @('-list', '-v', '-keystore', $ksPath, '-alias', $Alias, '-storepass:env', 'TVHOME_GEN_PASS') $null 60000
    } finally {
        # Gone before any gh process starts, so gh never inherits it.
        Remove-Item Env:\TVHOME_GEN_PASS -ErrorAction SilentlyContinue
    }
    if ($k.Code -ne 0 -or -not (Test-Path -LiteralPath $ksPath)) { throw 'keytool failed to create the keystore' }
    $fp = $null
    if ($l.Code -eq 0 -and $l.Text -match 'SHA256:\s*([0-9A-Fa-f:]{95})') { $fp = $Matches[1] }

    $step = 'save DPAPI backup'
    # DPAPI-protected backup of the password (this Windows user on this PC only).
    ConvertTo-SecureString -String $pw -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $pwPath -Encoding ASCII

    $step = 'gh secret set'
    $b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($ksPath))
    Set-GhSecret 'TVHOME_KEYSTORE_B64' $b64
    Set-GhSecret 'TVHOME_KEYSTORE_PASSWORD' $pw
    Set-GhSecret 'TVHOME_KEY_ALIAS' $Alias
    Set-GhSecret 'TVHOME_KEY_PASSWORD' $pw
    $b64 = $null

    Write-Host 'done'
    if ($fp) { Write-Host "Certificate SHA-256: $fp" } else { Write-Host 'Certificate SHA-256: (could not read; run keytool -list -v yourself)' }
    Write-Host "Backup folder: $dir"
    Write-Host 'Next: read the password in this window and save it in your password manager, then copy the folder to a second place. Steps: docs/release/tv-home-signing.md'
} catch {
    # Fixed text only, never the exception message. gh's own stderr is safe to show (gh never echoes stdin values).
    Write-Host "ERROR: failed at step '$step'. Nothing secret was printed." -ForegroundColor Red
    if ($script:GhErr) { Write-Host "gh said: $($script:GhErr)" -ForegroundColor Red }
    Write-Host 'If GitHub secrets were only partly set, see docs/release/tv-home-signing.md, Rotation.' -ForegroundColor Red
    exit 1
} finally {
    Remove-Item Env:\TVHOME_GEN_PASS -ErrorAction SilentlyContinue
    $pw = $null
}
