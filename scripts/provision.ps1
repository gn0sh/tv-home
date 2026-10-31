<#
.SYNOPSIS
  Puts a secret (calendar URL or API key) on the TV over adb. Run it yourself in your own PowerShell window.
.DESCRIPTION
  The value is typed at a hidden prompt, checked, and sent to the TV on adb's stdin
  (adb shell content write), so it is never a command-line argument, never printed, never logged.
  Only the fixed words "configured" and "last" are read back from the TV.
  Never run this through an agent, a chat or a Remote Control session.
.PARAMETER Key     calendar_url (default) or anthropic_api_key
.PARAMETER Serial  adb device serial or ip:port; needed only if more than one device is connected
.PARAMETER Clear   remove the stored value instead of setting one
#>
[CmdletBinding()]
param(
    [ValidateSet('calendar_url', 'anthropic_api_key')][string]$Key = 'calendar_url',
    [string]$Serial,
    [switch]$Clear
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Authority = 'com.gnosh.tvhome.provision'
$Uri = "content://$Authority/$Key"
$MaxBytes = 1024
# Same patterns as core/.../ProvisionValidator.kt. \A...\z and -cmatch: whole string, case-sensitive.
$Patterns = @{
    calendar_url      = '\Ahttps://calendar\.google\.com/calendar/ical/[A-Za-z0-9%._-]+/private-[0-9a-f]{32}/basic\.ics\z'
    anthropic_api_key = '\Ask-ant-[A-Za-z0-9_-]{20,500}\z'
}

function Fail([string]$msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# Runs adb with fixed arguments (never the secret). Returns exit code and combined output; $Stdin is raw bytes or $null.
function Invoke-Adb([string[]]$AdbArgs, [byte[]]$Stdin = $null) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'adb'
    $prefix = if ($Serial) { "-s $Serial " } else { '' }
    $psi.Arguments = $prefix + ($AdbArgs -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    if ($null -ne $Stdin) { $p.StandardInput.BaseStream.Write($Stdin, 0, $Stdin.Length); $p.StandardInput.BaseStream.Flush() }
    $p.StandardInput.Close()
    $out = $p.StandardOutput.ReadToEnd() + $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    [pscustomobject]@{ Code = $p.ExitCode; Text = $out }
}

# --- adb present, exactly one device -------------------------------------------------------------------------
if ($Serial -and $Serial -notmatch '^[A-Za-z0-9:._-]+$') { Fail 'Serial has odd characters.' }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail 'adb not found on PATH. Install Android platform-tools first.' }
$devs = @((& adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\S' })
if ($Serial) { $devs = @($devs | Where-Object { $_ -match ('^' + [regex]::Escape($Serial) + '\s') }) }
if ($devs.Count -eq 0) { Fail 'No device connected. Run adb connect <tv-ip>:<port> first (see docs/user/tv-home-setup.md).' }
if ($devs.Count -gt 1) { Fail 'More than one device is connected. Re-run with -Serial <serial or ip:port>.' }
if ($devs[0] -notmatch '\sdevice$') { Fail 'The device is not ready (offline or unauthorized). Accept the prompt on the TV.' }

function Show-State {
    $q = Invoke-Adb @('shell', 'content', 'query', '--uri', $Uri)
    # Only these two fixed fields are ever shown.
    if ($q.Text -match 'configured=(\d)') { $c = $Matches[1] } else { $c = '?' }
    if ($q.Text -match 'last=([A-Z_]+)') { $l = $Matches[1] } else { $l = '?' }
    Write-Host "configured=$c last=$l"
    return @{ Configured = $c; Last = $l }
}

# --- clear -----------------------------------------------------------------------------------------------------
if ($Clear) {
    $r = Invoke-Adb @('shell', 'content', 'delete', '--uri', $Uri)
    if ($r.Code -ne 0) { Fail 'The TV refused the clear. Is the TV Home app installed?' }
    $s = Show-State
    if ($s.Configured -eq '0') { Write-Host 'Removed.' } else { Fail 'Still configured.' }
    exit 0
}

# --- interactive only ------------------------------------------------------------------------------------------
if ([Console]::IsInputRedirected -or -not [Environment]::UserInteractive) {
    Fail 'Run this in your own PowerShell window. It will not read the value from a pipe, file or agent session.'
}

$secure = Read-Host -Prompt "Paste the $Key (hidden, then press Enter)" -AsSecureString
$bstr = [IntPtr]::Zero
$value = $null
$bytes = $null
try {
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $value = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $value = $value.Trim(@(' ', "`t"))          # same trim as the TV: space and tab only
    if ($value.Length -eq 0) { Fail 'Nothing entered.' }
    if ($value.Length -gt $MaxBytes -or $value -cnotmatch $Patterns[$Key]) {
        Fail 'Not valid for this key, so nothing was sent. Copy the whole link again.'
    }
    Write-Host 'Format valid. Sending to the TV...'
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($value)   # no BOM, no newline: the TV refuses a trailing newline

    $w = Invoke-Adb @('shell', 'content', 'write', '--uri', $Uri) $bytes
    $unavailable = ($w.Code -ne 0) -or ($w.Text -match 'Unknown command|No such|Error|Exception|not found')
    if ($unavailable) {
        # Fallback: value rides in a command line for under a second (documented, accepted in the design).
        Write-Host 'content write is not available; using the am start fallback.'
        $am = "am start -n com.gnosh.tvhome/.ProvisionActivity --es $Key '$value'"
        $f = Invoke-Adb @('shell', ('"' + $am + '"'))
        $am = $null; $f = $null
    }
    Start-Sleep -Milliseconds 700
    $s = Show-State
    if ($s.Configured -ne '1') { Fail 'The TV did not store it. Is the TV Home app installed and the TV awake?' }
    Write-Host 'Saved on the TV.'

    # Calendar only: fetch the feed once from this PC, print counts only (ADR 0004 limits).
    if ($Key -eq 'calendar_url') {
        try {
            Add-Type -AssemblyName System.Net.Http
            $client = New-Object System.Net.Http.HttpClient
            $client.Timeout = [TimeSpan]::FromSeconds(30)
            $resp = $client.GetAsync($value, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
            if (-not $resp.IsSuccessStatusCode) { throw 'status' }
            $stream = $resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
            $ms = New-Object System.IO.MemoryStream
            $buf = New-Object byte[] 65536
            $cap = 10MB + 1
            while ($ms.Length -lt $cap -and ($n = $stream.Read($buf, 0, [Math]::Min($buf.Length, $cap - $ms.Length))) -gt 0) { $ms.Write($buf, 0, $n) }
            $size = $ms.Length
            $text = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
            $events = ([regex]::Matches($text, '(?m)^BEGIN:VEVENT')).Count
            $rrules = ([regex]::Matches($text, '(?m)^RRULE[:;]')).Count
            $ok = ($size -le 10MB) -and ($events -le 50000) -and ($rrules -le 5000)
            $kb = [Math]::Round($size / 1KB)
            $okWord = if ($ok) { 'yes' } else { 'no' }
            $sizeNote = if ($size -gt 10MB) { '>' } else { '' }
            Write-Host "Feed: size=$sizeNote$kb KB, VEVENT=$events, RRULE=$rrules, within app limits: $okWord"
            $text = $null
        } catch {
            Write-Host 'Feed check: could not download the feed from this PC (the link is saved anyway).'
        } finally {
            $client = $null
        }
    }
} finally {
    if ($bstr -ne [IntPtr]::Zero) { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    if ($bytes) { [Array]::Clear($bytes, 0, $bytes.Length) }
    $value = $null; $secure = $null; $bytes = $null; $text = $null
    [GC]::Collect()
}
