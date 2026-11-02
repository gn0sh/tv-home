<#
.SYNOPSIS
  Makes the dashboard the Google TV's Home (-SetHome) and the remote's mic button open Ask Claude (-SetMic), or undoes
  both (-Rollback). Run it yourself in your own PowerShell window; it changes settings on the TV.
.DESCRIPTION
  -SetHome: turns on the app's HomeEntry (off as installed), makes it the default home with
         "cmd package set-home-activity", and saves the TV's own home to a rollback file first.
  -SetMic:  turns on the app's mic key service in the TV's accessibility settings. It appends to the list already
         there (saved to the rollback file first), so nothing else is switched off. Google Assistant stays
         installed; the service only takes the mic, voice and search keys.
  -Rollback: puts back what the rollback file saved (the old home, the old accessibility list) and turns
         HomeEntry off. Three commands by hand do the same; see docs/user/tv-home-setup.md.
  It asks you to type YES before changing anything, and prints each adb command it runs.
  A Google TV system update can undo -SetHome; run it again after one.
.PARAMETER SetHome   make the dashboard the TV's Home
.PARAMETER SetMic    make the mic button open Ask Claude
.PARAMETER Rollback  undo both
.PARAMETER Serial  the TV's adb address (default 192.168.68.71:5555)
#>
[CmdletBinding()]
param(
    [switch]$SetHome,
    [switch]$SetMic,
    [switch]$Rollback,
    [string]$Serial = '192.168.68.71:5555'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not ($SetHome -or $SetMic -or $Rollback)) { throw 'Choose -SetHome, -SetMic, both, or -Rollback.' }
if ($Rollback -and ($SetHome -or $SetMic)) { throw '-Rollback runs on its own.' }
if ($Serial -notmatch '\A\d{1,3}(\.\d{1,3}){3}:\d{1,5}\z') { throw 'Serial must look like 192.168.68.71:5555' }

$Tools = Join-Path $env:LOCALAPPDATA 'tv-home-tools'
$adbItem = Get-ChildItem $Tools -Recurse -Filter adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $adbItem) { throw "adb.exe not found under $Tools (the tv-home install puts it there)." }
$Adb = $adbItem.FullName
$RollbackFile = Join-Path $Tools 'takeover-rollback.json'

$Pkg = 'com.gnosh.tvhome'
$HomeComponent = "$Pkg/.HomeEntry"
$MicService = "$Pkg/$Pkg.MicKeyService"
# What a component name or accessibility list may contain; anything else read from the TV is refused. No $, quotes,
# spaces or ; so a value read back and written again cannot be expanded by the TV's shell.
$ComponentRe = '\A[A-Za-z0-9_.]+/[A-Za-z0-9_.]+\z'
$A11yListRe = '\A([A-Za-z0-9_.]+/[A-Za-z0-9_.]+)(:[A-Za-z0-9_.]+/[A-Za-z0-9_.]+)*\z'

function Adb([string[]]$argv) {
    Write-Host ('  adb ' + ($argv -join ' ')) -ForegroundColor DarkGray
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = (@('-s', $Serial) + $argv) -join ' '
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEnd(); $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw "adb failed ($($p.ExitCode)): $($err.Trim())" }
    return $out.Trim()
}

function Get-TvHome {
    $out = Adb @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    $last = ($out -split "`r?`n" | Where-Object { $_ -match '/' } | Select-Object -Last 1)
    if ($null -eq $last) { return $null }
    $last = $last.Trim()
    if ($last -notmatch $ComponentRe) { return $null }
    return $last
}

function Get-TvA11yList {
    $v = Adb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
    if ($v -eq 'null' -or $v -eq '') { return '' }
    if ($v -notmatch $A11yListRe) { throw "The TV's accessibility list has an unexpected form; stopping without changes." }
    return $v
}

function Load-Rollback {
    if (Test-Path $RollbackFile) { return (Get-Content $RollbackFile -Raw | ConvertFrom-Json) }
    return [pscustomobject]@{ home = $null; a11y = $null; a11yOn = $null }
}

function Save-Rollback($r) { $r | ConvertTo-Json | Set-Content -Path $RollbackFile -Encoding UTF8 }

function Confirm-Change([string]$what) {
    Write-Host ""
    Write-Host $what
    $answer = Read-Host 'Type YES to go ahead'
    if ($answer -cne 'YES') { Write-Host 'Nothing changed.'; exit 0 }
}

$null = & { $c = New-Object System.Diagnostics.ProcessStartInfo; $c.FileName = $Adb; $c.Arguments = "connect $Serial"; $c.UseShellExecute = $false; $c.CreateNoWindow = $true; $c.RedirectStandardOutput = $true; $c.RedirectStandardError = $true; $cp = [System.Diagnostics.Process]::Start($c); [void]$cp.WaitForExit(5000) }

$saved = Load-Rollback

if ($Rollback) {
    $plan = @()
    if ($saved.home) { $plan += "Home goes back to $($saved.home)." }
    if ($null -ne $saved.a11y) { $plan += 'The accessibility list goes back to what it was before -SetMic.' }
    $plan += 'The dashboard''s Home entry is turned off.'
    Confirm-Change ("This will:`n - " + ($plan -join "`n - "))
    if ($saved.home) {
        if ($saved.home -notmatch $ComponentRe) { throw 'The rollback file has an unexpected home; stopping.' }
        Adb @('shell', 'cmd', 'package', 'set-home-activity', $saved.home) | Out-Null
    }
    Adb @('shell', 'pm', 'disable', $HomeComponent) | Out-Null
    if ($null -ne $saved.a11y) {
        if ($saved.a11y -eq '') {
            Adb @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') | Out-Null
        } else {
            if ($saved.a11y -notmatch $A11yListRe) { throw 'The rollback file has an unexpected accessibility list; stopping.' }
            Adb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $saved.a11y) | Out-Null
        }
        $on = if ($saved.a11yOn -eq '1') { '1' } else { '0' }
        Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', $on) | Out-Null
    }
    Remove-Item $RollbackFile -ErrorAction SilentlyContinue
    Write-Host "Done. Home is now: $(Get-TvHome)"
    exit 0
}

$plan = @()
if ($SetHome) { $plan += 'Home: the remote''s Home button opens the dashboard.' }
if ($SetMic) { $plan += 'Mic: the remote''s mic button opens Ask Claude (Google Assistant stays installed).' }
Confirm-Change ("This will change settings on the TV:`n - " + ($plan -join "`n - ") + "`nUndo any time with: .\scripts\tv-home-takeover.ps1 -Rollback")

if ($SetHome) {
    $before = Get-TvHome
    if (-not $saved.home -and $before -and $before -notlike "$Pkg/*") { $saved.home = $before }
    if (-not $saved.home) { throw "Couldn't read the TV's current home app, so there would be nothing to roll back to; stopping." }
    Save-Rollback $saved
    Adb @('shell', 'pm', 'enable', $HomeComponent) | Out-Null
    Adb @('shell', 'cmd', 'package', 'set-home-activity', $HomeComponent) | Out-Null
    $now = Get-TvHome
    if ($now -ne $HomeComponent) { Write-Warning "Home now resolves to '$now', not the dashboard. Run -Rollback and tell Claude." }
    else { Write-Host 'Home: done. Press Home on the remote to check.' }
}

if ($SetMic) {
    $list = Get-TvA11yList
    if ($null -eq $saved.a11y) {
        $on = Adb @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')
        $saved.a11y = $list
        $saved.a11yOn = if ($on -eq '1') { '1' } else { '0' }
        Save-Rollback $saved
    }
    $items = @($list -split ':' | Where-Object { $_ })
    if ($items -notcontains $MicService) {
        $new = (@($items) + $MicService) -join ':'
        Adb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $new) | Out-Null
    }
    Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
    Write-Host 'Mic: done. Press the mic button on the remote to check.'
}
