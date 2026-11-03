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
Check '2 signing block reader has v2, v3 and v31 lists' ($blk.ContainsKey('v2') -and $blk.ContainsKey('v3') -and $blk.ContainsKey('v31'))
$all = @($blk.v2) + @($blk.v3) + @($blk.v31)
Check '2 signing block has a certificate' ($all.Count -ge 1)
Check '2 every v2/v3/v3.1 cert equals the apksigner digest' (@($all | Where-Object { $_ -cne $exp }).Count -eq 0) "got $($all -join ',')"

# links in incoming are not APKs; tool lookup skips incoming and fetch-*
$tmpRoot = Join-Path ([IO.Path]::GetTempPath()) ('tvl-' + [Guid]::NewGuid().ToString('N'))
$Incoming = Join-Path $tmpRoot 'incoming'
New-Item -ItemType Directory -Force -Path $Incoming | Out-Null
Copy-Item $QaApk (Join-Path $Incoming 'real.apk')
$linked = $false
try { New-Item -ItemType SymbolicLink -Path (Join-Path $Incoming 'link.apk') -Target $QaApk | Out-Null; $linked = $true } catch { Write-Host "SKIP  symbolic links not allowed here: $($_.Exception.Message)" }
if ($linked) {
    $got = @(Get-IncomingApks)
    Check '3 a symbolic link in incoming is not counted as an APK' ($got.Count -eq 1 -and $got[0].Name -ceq 'real.apk') (($got | ForEach-Object { $_.Name }) -join ',')
}
$Tools = $tmpRoot
foreach ($d in @('incoming', 'fetch-1', 'sdk\platform-tools')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $tmpRoot $d) | Out-Null
    Set-Content -Path (Join-Path $tmpRoot "$d\adb.exe") -Value 'x'
}
$found = @(Find-ToolFile @('adb.exe'))
Check '4 tool lookup skips incoming and fetch-*' ($found.Count -eq 1 -and $found[0].FullName -like '*sdk\platform-tools\adb.exe') (($found | ForEach-Object { $_.FullName }) -join ',')
Remove-Item $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue

# TV state messages and config validation (pure functions)
$msg = Get-TvProblem 'error: device unauthorized.' '' '1.2.3.4:5555' 'C:\cfg.json'
Check '6 unauthorized tells him to choose Allow' ($msg -match 'choose Allow' -and $msg -match 'Always allow') $msg
$msg = Get-TvProblem 'error: device offline' '' '1.2.3.4:5555' 'C:\cfg.json'
Check '6 offline says restart the TV' ($msg -match 'offline' -and $msg -match 'Restart') $msg
$msg = Get-TvProblem 'error: no devices' 'failed to connect to 1.2.3.4:5555' '1.2.3.4:5555' 'C:\cfg.json'
Check '6 no answer includes the connect output' ($msg -match 'failed to connect' -and $msg -match 'did not answer') $msg
$cfgDir = Join-Path ([IO.Path]::GetTempPath()) ('tvc-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
$cf = Join-Path $cfgDir 'c.json'
Set-Content -LiteralPath $cf -Value '{"serial":"192.168.68.71:5555"}'
Check '6 good config is read' ((Read-TvSerial $cf).Serial -ceq '192.168.68.71:5555')
Set-Content -LiteralPath $cf -Value '{"other":1}'
$x = Read-TvSerial $cf
Check '6 config without serial gives a Fix message' (-not $x.Serial -and $x.Error -match '^Fix ')
Set-Content -LiteralPath $cf -Value 'not json {'
$x = Read-TvSerial $cf
Check '6 broken JSON gives a Fix message' (-not $x.Serial -and $x.Error -match '^Fix ')
Set-Content -LiteralPath $cf -Value '{"serial":"nope"}'
Check '6 bad address gives a Fix message' ((Read-TvSerial $cf).Error -match '^Fix ')
Remove-Item $cfgDir -Recurse -Force -ErrorAction SilentlyContinue

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
