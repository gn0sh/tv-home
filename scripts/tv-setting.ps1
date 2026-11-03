<#
.SYNOPSIS
  Changes one named Google TV setting from an allowlist, and prints its old and new values.
.DESCRIPTION
  Only the settings listed in $Settings below can be read or changed; there is no way to send other adb commands.
  Uses the same adb as the remote (under %LOCALAPPDATA%\tv-home-tools) and the same TV address (192.168.68.71:5555,
  or -Serial like the remote's --serial). Debugging must be on on the TV, as for the remote.

  .\scripts\tv-setting.ps1 cec-volume on     the Google TV sends volume to the TV over HDMI-CEC
  .\scripts\tv-setting.ps1 cec-volume off
  .\scripts\tv-setting.ps1 cec-volume        shows the current value only
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$Name,
    [Parameter(Position = 1)][string]$Value,
    [string]$Serial = '192.168.68.71:5555'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Each entry: the fixed command that reads it, and the fixed command for each allowed value. Nothing typed is ever
# passed to the TV; the name and value only pick one of these strings.
$Settings = [ordered]@{
    'cec-volume' = @{
        Help = 'HDMI-CEC volume control (the Google TV sends Vol+/Vol-/Mute to the TV it is plugged into)'
        Get  = 'cmd hdmi_control cec_setting get volume_control_enabled'
        Set  = @{
            'on'  = 'cmd hdmi_control cec_setting set volume_control_enabled 1'
            'off' = 'cmd hdmi_control cec_setting set volume_control_enabled 0'
        }
    }
}

function Show-Usage {
    Write-Host 'Usage: .\scripts\tv-setting.ps1 <name> [<value>]'
    foreach ($k in $Settings.Keys) {
        Write-Host ("  {0} {1}   {2}" -f $k, (($Settings[$k].Set.Keys | Sort-Object) -join '|'), $Settings[$k].Help)
    }
}

if (-not $Name -or -not $Settings.Contains($Name.ToLowerInvariant())) {
    if ($Name) { Write-Host "Unknown setting '$Name'." }
    Show-Usage
    exit 2
}
$entry = $Settings[$Name.ToLowerInvariant()]
$command = $null
if ($Value) {
    $key = $Value.ToLowerInvariant()
    if (-not $entry.Set.ContainsKey($key)) {
        Write-Host "'$Value' is not allowed for $Name."
        Show-Usage
        exit 2
    }
    $command = $entry.Set[$key]
}

if ($Serial -notmatch '\A\d{1,3}(\.\d{1,3}){3}:\d{1,5}\z') { throw 'Serial must look like 192.168.68.71:5555' }
$tools = Join-Path $env:LOCALAPPDATA 'tv-home-tools'
$adbItem = Get-ChildItem $tools -Recurse -Filter adb.exe -ErrorAction SilentlyContinue | Sort-Object FullName |
    Select-Object -First 1
if (-not $adbItem) { throw "adb.exe not found under $tools (the tv-home install puts it there)." }
$adb = $adbItem.FullName

# adb writes to stderr; judge by exit code, not by Windows PowerShell's error stream.
function Invoke-Adb([string[]]$AdbArgs) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $adb @AdbArgs 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    # Printable characters only, and short: this is the TV's text.
    $text = (($out | Where-Object { $_ }) -join ' ') -replace '[^\x20-\x7E]', ''
    if ($text.Length -gt 200) { $text = $text.Substring(0, 200) }
    [pscustomobject]@{ Code = $code; Text = $text.Trim() }
}

[void](Invoke-Adb @('connect', $Serial))
$old = Invoke-Adb @('-s', $Serial, 'shell', $entry.Get)
if ($old.Code -ne 0) { throw "Could not reach the TV at $Serial (is it on, with debugging on?): $($old.Text)" }
Write-Host "$Name before: $($old.Text)"
if (-not $command) { exit 0 }

$set = Invoke-Adb @('-s', $Serial, 'shell', $command)
if ($set.Code -ne 0) { throw "The TV did not accept it: $($set.Text)" }
$new = Invoke-Adb @('-s', $Serial, 'shell', $entry.Get)
Write-Host "$Name now:    $($new.Text)"
