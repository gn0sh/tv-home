<#
.SYNOPSIS
  CI dry run of scripts/install-tv-home.ps1 on Windows PowerShell 5.1, against the qa APK. No adb, no secrets.
.PARAMETER QaApk           the pull request's qa APK (debuggable, debug key)
.PARAMETER ExpectedSha256  its signer SHA-256 as computed by apksigner on Linux (hex, any case, colons optional)
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$QaApk,
    [Parameter(Mandatory)][string]$ExpectedSha256
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Child PowerShells on this throwaway runner run the repo's own scripts under RemoteSigned (not Bypass).
$env:PSExecutionPolicyPreference = 'RemoteSigned'

$checkout = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$install = Join-Path $checkout 'scripts\install-tv-home.ps1'
$cmd = Join-Path $checkout 'scripts\Install TV Home.cmd'
$failures = @()
function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) { Write-Host "PASS  $name" } else { Write-Host "FAIL  $name $detail" -ForegroundColor Red; $script:failures += $name }
}

if (-not (Test-Path $QaApk)) { throw "qa APK not found: $QaApk" }
$exp = (($ExpectedSha256.ToUpper() -replace '[^0-9A-F]', '') -replace '(..)(?!$)', '$1:')
if ($exp.Length -ne 95) { throw 'ExpectedSha256 is not a SHA-256' }
Write-Host "PowerShell $($PSVersionTable.PSVersion); expected signer $exp"

# 1 and 2: the script's own readers (no apksigner on this runner's tools folder)
. $install -LibraryOnly
$m = Get-ManifestInfo $QaApk
Check '1 package is com.gnosh.tvhome' ($m.Package -ceq 'com.gnosh.tvhome') "got $($m.Package)"
Check '1 qa APK is read as debuggable' ($m.Debuggable -eq $true)
$blk = Get-SigningBlockCerts $QaApk
$all = @($blk.v2) + @($blk.v3)
Check '2 signing block has a certificate' ($all.Count -ge 1)
Check '2 every v2/v3 cert equals the apksigner digest' (@($all | Where-Object { $_ -cne $exp }).Count -eq 0) "got $($all -join ',')"

# 3, 4 and the real path's refusal: run the whole script as a child with a private LOCALAPPDATA
$savedLocal = $env:LOCALAPPDATA
function Invoke-Install([string[]]$Apks, [string]$Pin) {
    $root = Join-Path ([IO.Path]::GetTempPath()) ('tvi-' + [Guid]::NewGuid().ToString('N'))
    $inc = Join-Path $root 'tv-home-tools\incoming'
    New-Item -ItemType Directory -Force -Path $inc | Out-Null
    $i = 0
    foreach ($a in $Apks) { $i++; Copy-Item $a (Join-Path $inc "build$i.apk") }
    $env:LOCALAPPDATA = $root
    $saved = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = (& powershell.exe -NoProfile -File $install -DryRun -DryRunPin $Pin 2>&1 | ForEach-Object { "$_" }) -join "`n"
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved; $env:LOCALAPPDATA = $savedLocal }
    [pscustomobject]@{ Code = $code; Text = $out }
}
$wrong = ('00:' * 31) + '00'
$r = Invoke-Install @($QaApk) $wrong
Check '3 wrong pin is refused' ($r.Code -eq 1 -and $r.Text -match 'does not match' -and $r.Text -match 'NOT INSTALLED') $r.Text
$r = Invoke-Install @($QaApk, $QaApk) $exp
Check '4 two APKs are refused' ($r.Code -eq 1 -and $r.Text -match 'exactly one') $r.Text
$r = Invoke-Install @($QaApk) $exp
Check '1 right pin passes the cert check, then the debuggable qa APK is refused' ($r.Code -eq 1 -and $r.Text -match 'Signing certificate matches' -and $r.Text -match 'debuggable') $r.Text
$r = Invoke-Install @() $exp
Check '0 empty folder is refused' ($r.Code -eq 1 -and $r.Text -match 'No APK') $r.Text

# 5: parse and lint
foreach ($f in @($install, $PSCommandPath, (Join-Path $checkout 'scripts\update-tv-remote.ps1'), (Join-Path $checkout 'scripts\install-remote-shortcut.ps1'))) {
    $errs = $null; $tok = $null
    [void][Management.Automation.Language.Parser]::ParseFile($f, [ref]$tok, [ref]$errs)
    Check "5 parses: $(Split-Path $f -Leaf)" ($errs.Count -eq 0) (($errs | ForEach-Object { $_.Message }) -join '; ')
}
$cmdText = Get-Content $cmd -Raw
Check '5 .cmd runs the script by file, no policy override' ($cmdText -match 'install-tv-home\.ps1' -and $cmdText -notmatch '(?i)bypass|unblock')
Check '5 install script has no bypass or Unblock-File' ((Get-Content $install -Raw) -notmatch '(?i)-ExecutionPolicy|Unblock-File\s')
if (Get-Module -ListAvailable -Name PSScriptAnalyzer) {
    $found = @(Invoke-ScriptAnalyzer -Path $install -Severity Error) + @(Invoke-ScriptAnalyzer -Path $PSCommandPath -Severity Error)
    Check '5 PSScriptAnalyzer: no errors' ($found.Count -eq 0) (($found | ForEach-Object { "$($_.ScriptName):$($_.Line) $($_.Message)" }) -join '; ')
} else { Write-Host 'SKIP  PSScriptAnalyzer is not available on this runner' }

if ($failures.Count -gt 0) { Write-Host "$($failures.Count) check(s) failed." -ForegroundColor Red; exit 1 }
Write-Host 'All checks passed.'
# Exit explicitly: the runner exits with the last native command's code otherwise.
exit 0
