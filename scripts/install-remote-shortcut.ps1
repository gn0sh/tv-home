<#
.SYNOPSIS
  Puts a "TV Remote" icon on the desktop and in the Start menu that opens the on-screen remote
  (scripts\tv-remote-pc.ps1) with no PowerShell window. -Remove takes both icons away.
.DESCRIPTION
  Changes only this PC (two shortcut files); nothing on the TV. The icons point at this tv-home folder, so run it again
  if you move the folder.
#>
[CmdletBinding()]
param([switch]$Remove)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$remote = Join-Path $PSScriptRoot 'tv-remote-pc.ps1'
if (-not (Test-Path $remote)) { throw "tv-remote-pc.ps1 not found next to this script ($PSScriptRoot)." }
$places = @(
    (Join-Path ([Environment]::GetFolderPath('Desktop')) 'TV Remote.lnk'),
    (Join-Path ([Environment]::GetFolderPath('Programs')) 'TV Remote.lnk')
)

if ($Remove) {
    foreach ($p in $places) { if (Test-Path $p) { Remove-Item $p; Write-Host "Removed $p" } }
    exit 0
}

$shell = New-Object -ComObject WScript.Shell
foreach ($p in $places) {
    $s = $shell.CreateShortcut($p)
    $s.TargetPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $s.Arguments = "-NoProfile -WindowStyle Hidden -File `"$remote`""
    $s.WorkingDirectory = Split-Path $PSScriptRoot -Parent
    $s.IconLocation = (Join-Path $env:SystemRoot 'System32\shell32.dll') + ',15'
    $s.Description = 'On-screen remote for the Google TV'
    $s.Save()
    Write-Host "Made $p"
}
Write-Host 'Done. Tap "TV Remote" on the desktop or in the Start menu. To pin it to the taskbar: right-click it in the Start menu > Pin to taskbar.'
