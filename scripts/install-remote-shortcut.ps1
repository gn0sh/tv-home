<#
.SYNOPSIS
  Puts a "TV Remote" icon on the desktop and in the Start menu that opens the on-screen remote with no console
  window, and an "Update TV Remote" icon next to it that runs update-tv-remote.ps1 (pull, reinstall if needed,
  restart). -Remove takes all four icons away.
.DESCRIPTION
  By default the icon opens the remote app (tv-remote\, ADR 0006): it first runs "uv sync --locked" for the tv-remote
  folder, which installs the app and its locked dependencies (not the test tools) into tv-remote\.venv, then points the icon at
  tv-remote\.venv\Scripts\tv-remote-app.exe, a windowless launcher that uv makes for the app.
  -Classic points the icon at the older PowerShell remote (scripts\tv-remote-pc.ps1) instead.

  Changes only this PC (two shortcut files, and the tv-remote\.venv folder); nothing on the TV. The icons point at this
  tv-home folder, so run it again if you move the folder, and after "git pull" when the remote has changed.
#>
[CmdletBinding()]
param([switch]$Remove, [switch]$Classic)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = Split-Path $PSScriptRoot -Parent
$project = Join-Path $repo 'tv-remote'
$icon = Join-Path $project 'src\tv_remote\pcremote_ui\remote.ico'
$places = @(
    (Join-Path ([Environment]::GetFolderPath('Desktop')) 'TV Remote.lnk'),
    (Join-Path ([Environment]::GetFolderPath('Programs')) 'TV Remote.lnk')
)
$updatePlaces = @(
    (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Update TV Remote.lnk'),
    (Join-Path ([Environment]::GetFolderPath('Programs')) 'Update TV Remote.lnk')
)

if ($Remove) {
    foreach ($p in $places + $updatePlaces) { if (Test-Path $p) { Remove-Item $p; Write-Host "Removed $p" } }
    exit 0
}

if ($Classic) {
    $remote = Join-Path $PSScriptRoot 'tv-remote-pc.ps1'
    if (-not (Test-Path $remote)) { throw "tv-remote-pc.ps1 not found next to this script ($PSScriptRoot)." }
    $target = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $arguments = "-NoProfile -WindowStyle Hidden -File `"$remote`""
    $workdir = $repo
} else {
    # uv: on PATH, or where its installer puts it.
    $uv = $null
    $cmd = Get-Command uv -ErrorAction SilentlyContinue
    if ($cmd) { $uv = $cmd.Source }
    elseif (Test-Path (Join-Path $env:USERPROFILE '.local\bin\uv.exe')) { $uv = Join-Path $env:USERPROFILE '.local\bin\uv.exe' }
    if (-not $uv) { throw 'uv not found (on PATH or in %USERPROFILE%\.local\bin). The tv-remote setup installs it; or use -Classic.' }
    if (-not (Test-Path (Join-Path $project 'uv.lock'))) { throw "tv-remote\uv.lock not found under $repo." }
    Write-Host 'Installing the remote app (uv sync --locked --no-dev)...'
    # uv reports progress on stderr; under Stop, Windows PowerShell could turn that into an error, so check the exit
    # code instead.
    $ErrorActionPreference = 'Continue'
    # --no-dev: only what the app needs (not pytest) goes into the venv the icon runs (sync-installs-dev).
    & $uv sync --locked --no-dev --project $project
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw "uv sync failed (exit $code). Nothing was changed; the old icon, if any, still works." }
    $target = Join-Path $project '.venv\Scripts\tv-remote-app.exe'
    if (-not (Test-Path $target)) { throw "uv sync did not make $target." }
    $arguments = ''
    $workdir = $project
}

$shell = New-Object -ComObject WScript.Shell
foreach ($p in $places) {
    $s = $shell.CreateShortcut($p)
    $s.TargetPath = $target
    $s.Arguments = $arguments
    $s.WorkingDirectory = $workdir
    $s.IconLocation = if (Test-Path $icon) { "$icon,0" } else { (Join-Path $env:SystemRoot 'System32\shell32.dll') + ',15' }
    $s.Description = 'On-screen remote for the Google TV'
    $s.Save()
    Write-Host "Made $p"
}

$updater = Join-Path $PSScriptRoot 'update-tv-remote.ps1'
if (Test-Path $updater) {
    $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    foreach ($p in $updatePlaces) {
        $s = $shell.CreateShortcut($p)
        $s.TargetPath = $ps
        $s.Arguments = "-NoProfile -WindowStyle Hidden -File `"$updater`""
        $s.WorkingDirectory = $repo
        $s.IconLocation = (Join-Path $env:SystemRoot 'System32\shell32.dll') + ',238'
        $s.Description = 'Get the latest TV Remote and restart it'
        $s.Save()
        Write-Host "Made $p"
    }
}
Write-Host 'Done. Tap "TV Remote" on the desktop or in the Start menu. After the team merges a change, double-click "Update TV Remote". To pin it to the taskbar: right-click it in the Start menu > Pin to taskbar.'
