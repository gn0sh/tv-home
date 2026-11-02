<#
.SYNOPSIS
  One step to update the PC remote after a change is merged: pulls main, reinstalls only if needed, restarts TV Remote.
.DESCRIPTION
  The "Update TV Remote" icon (made by install-remote-shortcut.ps1) runs this with no console; the result shows in a
  small box. In PowerShell from the tv-home folder: .\scripts\update-tv-remote.ps1

  1. Runs "git pull --ff-only" in this tv-home folder. It stops, changing nothing, if the folder is not on main or has
     uncommitted changes: it never stashes, resets or merges.
  2. Closes a running TV Remote the polite way (as if its close button were pressed) so its files can be replaced.
  3. If tv-remote\pyproject.toml, tv-remote\uv.lock or scripts\install-remote-shortcut.ps1 changed (or the app is not
     installed yet), runs install-remote-shortcut.ps1 (uv sync --locked --no-dev, and the icons) again.
  4. Opens TV Remote again.
  Changes only this PC; nothing on the TV. Windows PowerShell 5.1 or later.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = Split-Path $PSScriptRoot -Parent
$installer = Join-Path $PSScriptRoot 'install-remote-shortcut.ps1'
$app = Join-Path $repo 'tv-remote\.venv\Scripts\tv-remote-app.exe'
$icon = Join-Path ([Environment]::GetFolderPath('Programs')) 'TV Remote.lnk'
$reinstallWhen = @('tv-remote/pyproject.toml', 'tv-remote/uv.lock', 'scripts/install-remote-shortcut.ps1')

function Show-Result([string]$message, [bool]$ok) {
    try {
        Add-Type -AssemblyName System.Windows.Forms
        $kind = if ($ok) { [System.Windows.Forms.MessageBoxIcon]::Information } else { [System.Windows.Forms.MessageBoxIcon]::Error }
        [void][System.Windows.Forms.MessageBox]::Show($message, 'Update TV Remote', 'OK', $kind)
    } catch {
        Write-Host $message
        Write-Host 'Press any key to close.'
        [void]$Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
    }
}

# git writes progress to stderr; under Stop, Windows PowerShell 5.1 would turn that into an error, so each call runs
# under Continue and is judged by its exit code.
function Invoke-Git([string[]]$GitArgs) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & git -C $repo @GitArgs 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    [pscustomobject]@{ Code = $code; Text = (($out | Where-Object { $_ }) -join "`n").Trim() }
}

function Close-TvRemote {
    # The new app's window is "TV Remote" and the classic one "TV remote" (-eq ignores case). Only these processes.
    $names = @('python', 'pythonw', 'tv-remote-app', 'powershell', 'pwsh')
    $open = @(Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $names -contains $_.ProcessName -and $_.MainWindowTitle -eq 'TV Remote' })
    foreach ($p in $open) { [void]$p.CloseMainWindow() }
    foreach ($p in $open) {
        if (-not $p.WaitForExit(8000)) { return $false }  # never killed: it may be in the middle of a volume step
    }
    # uv's launcher (tv-remote-app.exe) ends just after the window's process; wait for it so uv sync can replace it.
    $launchers = @(Get-Process -Name 'tv-remote-app' -ErrorAction SilentlyContinue)
    foreach ($p in $launchers) { [void]$p.WaitForExit(5000) }
    return $true
}

try {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'git was not found on this PC.' }

    $branch = Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')
    if ($branch.Code -ne 0) { throw "$repo is not a git checkout." }
    if ($branch.Text -ne 'main') {
        throw "The tv-home folder is on branch '$($branch.Text)', not main, so nothing was changed. In PowerShell from the tv-home folder: git switch main, then run Update TV Remote again."
    }
    $dirty = Invoke-Git @('status', '--porcelain', '--untracked-files=no')
    if ($dirty.Code -ne 0) { throw "git status failed: $($dirty.Text)" }
    if ($dirty.Text) {
        throw "The tv-home folder has changes of its own, so nothing was pulled or changed:`n$($dirty.Text)`n`nTell the team; they will sort it out."
    }

    $before = (Invoke-Git @('rev-parse', 'HEAD')).Text
    $pull = Invoke-Git @('pull', '--ff-only')
    if ($pull.Code -ne 0) { throw "git pull --ff-only did not work, so nothing was changed:`n$($pull.Text)" }
    $after = (Invoke-Git @('rev-parse', 'HEAD')).Text

    $changed = @()
    if ($before -ne $after) {
        $changed = @((Invoke-Git @('diff', '--name-only', $before, $after)).Text -split "`n" | Where-Object { $_ })
    }
    $reinstall = (-not (Test-Path $app)) -or @($changed | Where-Object { $reinstallWhen -contains $_ }).Count -gt 0

    if (-not (Close-TvRemote)) {
        throw 'TV Remote did not close. Close it yourself, then run Update TV Remote again. (The update was pulled; nothing else was changed.)'
    }

    if ($reinstall) { & $installer *> $null }

    if (Test-Path $icon) { Start-Process -FilePath $icon }
    elseif (Test-Path $app) { Start-Process -FilePath $app }

    $what = if ($before -eq $after) { 'Already up to date.' } else { "Updated ($($changed.Count) files changed)." }
    if ($reinstall) { $what += ' The remote app was reinstalled.' }
    Show-Result "$what TV Remote is open again." $true
    exit 0
} catch {
    Show-Result "$($_.Exception.Message)" $false
    exit 1
}
