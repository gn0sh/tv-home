<#
.SYNOPSIS
  Makes the Google TV remote's Home button open the dashboard (-TurnOn), or undoes that (-Rollback). Run it yourself in your own PowerShell window; it changes a setting on the TV.
.DESCRIPTION
  -TurnOn:   turns on the app's key service ("Dashboard Home button") in the TV's accessibility settings. It
             appends to the list already there (saved to a rollback file first), so nothing else is switched off.
             The service reads no screen content: it sees only that the TV's own home came to the front, and then
             opens the dashboard (a brief flash of the TV's home first). The mic button stays Google Assistant.
  -Rollback: takes only that service out of the accessibility list (other services stay on) and, if the list is then
             what -TurnOn found, puts the accessibility switch back as it was. See docs/user/tv-home-setup.md for
             doing it by hand.
  Why not "default home": Google TV's own launcher declares Home at a priority no other app can match, so the TV keeps
  choosing it; and the system handles the Home and mic keys before any app could catch them.
  It asks you to type YES before changing anything, and prints each adb command it runs.
.PARAMETER TurnOn    Home opens the dashboard
.PARAMETER Rollback  undo
.PARAMETER Serial  the TV's adb address (default 192.168.68.71:5555)
#>
[CmdletBinding()]
param(
    [switch]$TurnOn,
    [switch]$Rollback,
    [string]$Serial = '192.168.68.71:5555'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($TurnOn.IsPresent -eq $Rollback.IsPresent) { throw 'Choose -TurnOn or -Rollback.' }
if ($Serial -notmatch '\A\d{1,3}(\.\d{1,3}){3}:\d{1,5}\z') { throw 'Serial must look like 192.168.68.71:5555' }

$Tools = Join-Path $env:LOCALAPPDATA 'tv-home-tools'
$adbItem = Get-ChildItem $Tools -Recurse -Filter adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $adbItem) { throw "adb.exe not found under $Tools (the tv-home install puts it there)." }
$Adb = $adbItem.FullName
# One rollback file per TV (rollback-file-not-per-serial).
$RollbackFile = Join-Path $Tools ('takeover-rollback-' + ($Serial -replace '[.:]', '-') + '.json')

$Pkg = 'com.gnosh.tvhome'
$Service = "$Pkg/$Pkg.HomeWatchService"
$ServiceShort = "$Pkg/.HomeWatchService"
# What the accessibility list may contain; anything else read from the TV is refused. $ is allowed (inner-class
# services, a11y-list-regex-too-strict), so the list is always written inside single quotes, which the TV's shell
# leaves alone; no quote, space or ; can get in.
$A11yListRe = '\A([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)(:[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)*\z'

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
    # Some shell tools print an exception and still exit 0 (content-exit-code).
    if (($out + "`n" + $err) -match '(?m)Exception|^Error') { throw "adb reported an error: $((($out + ' ' + $err).Trim() -split "`r?`n")[0])" }
    return $out.Trim()
}

function Get-TvA11yList {
    $v = Adb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
    if ($v -eq 'null' -or $v -eq '') { return '' }
    if ($v -notmatch $A11yListRe) {
        throw "The TV's accessibility list has an unexpected form; stopping without changes. To turn it off with the remote: open Settings from the dashboard's app row, then System > Accessibility > Dashboard Home button."
    }
    return $v
}

function Load-Rollback {
    if (Test-Path $RollbackFile) {
        try { $r = Get-Content $RollbackFile -Raw | ConvertFrom-Json } catch { $r = $null }
        if ($null -eq $r -or -not ($r.PSObject.Properties.Name -contains 'a11y' -and $r.PSObject.Properties.Name -contains 'a11yOn')) {
            throw "The rollback file $RollbackFile is damaged. Delete it and run again (-Rollback still works without it)."
        }
        return $r
    }
    return [pscustomobject]@{ a11y = $null; a11yOn = $null }
}

function Confirm-Change([string]$what) {
    Write-Host ""
    Write-Host $what
    $answer = Read-Host 'Type YES to go ahead'
    if ($answer -cne 'YES') { Write-Host 'Nothing changed.'; exit 0 }
}

$null = & { $c = New-Object System.Diagnostics.ProcessStartInfo; $c.FileName = $Adb; $c.Arguments = "connect $Serial"; $c.UseShellExecute = $false; $c.CreateNoWindow = $true; $c.RedirectStandardOutput = $true; $c.RedirectStandardError = $true; $cp = [System.Diagnostics.Process]::Start($c); [void]$cp.WaitForExit(5000) }

$saved = Load-Rollback

if ($Rollback) {
    Confirm-Change "This will take ""Dashboard Home button"" out of the TV's accessibility settings; anything else there stays on."
    # Remove only our service (a11y-settings-overwrite): a service turned on after -TurnOn, such as TalkBack, stays.
    $items = @((Get-TvA11yList) -split ':' | Where-Object { $_ })
    $rest = @($items | Where-Object { $_ -ne $Service -and $_ -ne $ServiceShort })
    if ($rest.Count -ne $items.Count) {
        if ($rest.Count -eq 0) { Adb @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') | Out-Null }
        else { Adb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ("'" + ($rest -join ':') + "'")) | Out-Null }
    }
    # The master switch goes back to its old value only if the list is back to exactly what -TurnOn found.
    if ($null -ne $saved.a11y -and ($rest -join ':') -eq $saved.a11y) {
        $on = if ($saved.a11yOn -eq '1') { '1' } else { '0' }
        Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', $on) | Out-Null
    }
    Remove-Item $RollbackFile -ErrorAction SilentlyContinue
    Write-Host 'Done. Home is the TV''s own again.'
    exit 0
}

Confirm-Change ("This will change a setting on the TV:`n - Home: the remote's Home button (and turning the TV on) opens the dashboard.`nUndo any time with: .\scripts\tv-home-takeover.ps1 -Rollback")
$list = Get-TvA11yList
if ($null -eq $saved.a11y) {
    $on = Adb @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')
    $saved.a11y = $list
    $saved.a11yOn = if ($on -eq '1') { '1' } else { '0' }
    $saved | ConvertTo-Json | Set-Content -Path $RollbackFile -Encoding UTF8
}
$items = @($list -split ':' | Where-Object { $_ })
if ($items -notcontains $Service -and $items -notcontains $ServiceShort) {
    Adb @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ("'" + ((@($items) + $Service) -join ':') + "'")) | Out-Null
}
Adb @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
Write-Host 'Done. Press Home on the remote to check.'
