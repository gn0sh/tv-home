<#
.SYNOPSIS
  Makes the dashboard the Google TV's Home (-SetHome) and the remote's mic button open Ask Claude (-SetMic), or undoes
  both (-Rollback). Run it yourself in your own PowerShell window; it changes settings on the TV.
.DESCRIPTION
  -SetHome: asks the app to turn on its HomeEntry (off as installed), makes it the default home with
         "cmd package set-home-activity", and saves the TV's own home to a rollback file first.
  -SetMic:  turns on the app's mic key service in the TV's accessibility settings. It appends to the list already
         there (saved to the rollback file first), so nothing else is switched off. Google Assistant stays
         installed; the service only takes the mic, voice and search keys.
  -Rollback: puts back the home the rollback file saved, turns HomeEntry off, and takes only the mic service out of
         the accessibility list (other services stay on). See docs/user/tv-home-setup.md for doing it by hand.
  HomeEntry is switched by the app itself (a shell-only provider path): Android does not let adb change a release
  app's components directly.
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
    # Some shell tools (content, cmd) print an exception and still exit 0 (content-exit-code).
    if (($out + "`n" + $err) -match '(?m)Exception|^Error') { throw "adb reported an error: $((($out + ' ' + $err).Trim() -split "`r?`n")[0])" }
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
    if (Test-Path $RollbackFile) {
        try { $r = Get-Content $RollbackFile -Raw | ConvertFrom-Json } catch { $r = $null }
        if ($null -eq $r -or -not ($r.PSObject.Properties.Name -contains 'home' -and $r.PSObject.Properties.Name -contains 'a11y' -and $r.PSObject.Properties.Name -contains 'a11yOn')) {
            throw "The rollback file $RollbackFile is damaged. Delete it and run again (-Rollback still works without it)."
        }
        return $r
    }
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
    $plan += 'The dashboard''s Home entry is turned off.'
    $plan += 'The dashboard mic button is taken out of the accessibility settings; anything else there stays on.'
    Confirm-Change ("This will:`n - " + ($plan -join "`n - "))
    $problems = @()
    # Each part runs even if another fails, so a failed home restore cannot leave the mic service on.
    # Turning HomeEntry off alone gives the TV its own home back, so it runs even if the restore fails
    # (rollback-home-blocks-disable).
    try { Adb @('shell', 'content', 'delete', '--uri', 'content://com.gnosh.tvhome.provision/home_entry') | Out-Null } catch { $problems += "Home entry: $($_.Exception.Message)" }
    try {
        if ($saved.home) {
            if ($saved.home -notmatch $ComponentRe) { throw 'the rollback file has an unexpected home' }
            Adb @('shell', 'cmd', 'package', 'set-home-activity', $saved.home) | Out-Null
        }
    } catch { $problems += "Home: $($_.Exception.Message)" }
    try {
        # Remove only our service (a11y-settings-overwrite): a service turned on after -SetMic, such as TalkBack, stays.
        $items = @((Get-TvA11yList) -split ':' | Where-Object { $_ })
        $rest = @($items | Where-Object { $_ -ne $MicService -and $_ -ne "$Pkg/.MicKeyService" })
        if ($rest.Count -ne $items.Count) {
            if ($rest.Count -eq 0) { Adb @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') | Out-Null }
            else { Adb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ($rest -join ':')) | Out-Null }
        }
        # The master switch goes back to its old value only if the list is back to exactly what -SetMic found.
        if ($null -ne $saved.a11y -and ($rest -join ':') -eq $saved.a11y) {
            $on = if ($saved.a11yOn -eq '1') { '1' } else { '0' }
            Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', $on) | Out-Null
        }
    } catch { $problems += "Mic: $($_.Exception.Message)" }
    if ($problems.Count -gt 0) {
        Write-Warning ("Not everything was undone; the rollback file is kept, so you can run -Rollback again:`n" + ($problems -join "`n"))
        exit 1
    }
    Remove-Item $RollbackFile -ErrorAction SilentlyContinue
    Write-Host "Done. The Home role is now held by: $(Adb @('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.HOME'))"
    exit 0
}

$plan = @()
if ($SetHome) { $plan += 'Home: the remote''s Home button opens the dashboard.' }
if ($SetMic) { $plan += 'Mic: the remote''s mic button opens Ask Claude (Google Assistant stays installed).' }
Confirm-Change ("This will change settings on the TV:`n - " + ($plan -join "`n - ") + "`nUndo any time with: .\scripts\tv-home-takeover.ps1 -Rollback")

if ($SetHome) {
    $before = Get-TvHome
    # Not our own entry, and not the system chooser (android/...): neither is a home to go back to.
    if (-not $saved.home -and $before -and $before -notlike "$Pkg/*" -and $before -notlike 'android/*') { $saved.home = $before }
    if (-not $saved.home) { throw "Couldn't read the TV's current home app, so there would be nothing to roll back to; stopping." }
    Save-Rollback $saved
    Adb @('shell', 'content', 'insert', '--uri', 'content://com.gnosh.tvhome.provision/home_entry', '--bind', 'on:i:1') | Out-Null
    Adb @('shell', 'cmd', 'package', 'set-home-activity', $HomeComponent) | Out-Null
    # set-home-activity makes the app the HOME role holder; resolve-activity keeps listing the system launcher, so the
    # role is what is checked.
    $now = Adb @('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.HOME')
    if ($now -ne $Pkg) { Write-Warning "The Home role is held by '$now', not the dashboard. Run -Rollback and tell Claude." }
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
    if ($items -notcontains $MicService -and $items -notcontains "$Pkg/.MicKeyService") {
        $new = (@($items) + $MicService) -join ':'
        Adb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $new) | Out-Null
    }
    Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
    Write-Host 'Mic: done. Press the mic button on the remote to check.'
}
