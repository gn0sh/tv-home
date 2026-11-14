<#
.SYNOPSIS
  An on-screen remote for the Google TV, for the PC's touchscreen or mouse. Run it in your own PowerShell window.
.DESCRIPTION
  Big tap buttons, laid out like a Google TV remote: Power, Input, Settings, Assistant, Home, Back, arrows and OK,
  rewind, play/pause, fast forward, volume, mute, channel and guide; hotkeys for the dashboard and the common streaming
  apps (greyed out when the TV does not have them); and a box to type text into a search field on the TV. The window's keyboard works too: arrow keys, Enter (OK), Backspace (Back)
  and Escape (Home).

  It drives the TV with the adb that the tv-home install uses (%LOCALAPPDATA%\tv-home-tools), over one long-lived
  "adb shell" so key presses arrive quickly and in order. Only fixed commands from the lists below are sent; typed text
  is limited to letters, digits, spaces and . , - _ @ and is single-quoted, so it cannot become a shell command.
  It opens no port and reads no secrets.
.PARAMETER Serial  the TV's adb address (default 192.168.68.71:5555)
#>
[CmdletBinding()]
param([string]$Serial = '192.168.68.71:5555')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Started from the "TV Remote" icon there is no console, so errors would vanish: show them in a box instead. Before the
# window: the trap. Inside it (button handlers): a ThreadException handler that shows the message and keeps running.
Add-Type -AssemblyName System.Windows.Forms
trap {
    [void][System.Windows.Forms.MessageBox]::Show("TV remote could not start: $($_.Exception.Message)", 'TV remote')
    exit 1
}
[System.Windows.Forms.Application]::SetUnhandledExceptionMode([System.Windows.Forms.UnhandledExceptionMode]::CatchException)
[System.Windows.Forms.Application]::add_ThreadException({
    param($s, $e)
    [void][System.Windows.Forms.MessageBox]::Show("TV remote: $($e.Exception.Message)", 'TV remote')
})
# One window at a time: a second tap on the icon would otherwise open a second remote.
$single = New-Object System.Threading.Mutex($false, 'Local\gnosh-tv-remote')
$owned = $false
try { $owned = $single.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $owned = $true } # mutex-abandon
if (-not $owned) { exit 0 }

if ($Serial -notmatch '\A\d{1,3}(\.\d{1,3}){3}:\d{1,5}\z') { throw "Serial must look like 192.168.68.71:5555" }

$adbItem = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'tv-home-tools') -Recurse -Filter adb.exe -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $adbItem) { throw "adb.exe not found under $env:LOCALAPPDATA\tv-home-tools (the tv-home install puts it there)." }
$Adb = $adbItem.FullName

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
# A form that sees every key before its buttons do (arrow keys would otherwise move between buttons), except while
# the text box has focus.
Add-Type -ReferencedAssemblies System.Windows.Forms, System.Drawing -TypeDefinition @'
using System;
using System.Windows.Forms;
public class TvRemoteForm : Form {
    public event EventHandler<KeyEventArgs> RemoteKey;
    protected override bool ProcessCmdKey(ref Message msg, Keys keyData) {
        if (RemoteKey != null && !(ActiveControl is TextBoxBase)) {
            KeyEventArgs e = new KeyEventArgs(keyData);
            RemoteKey(this, e);
            if (e.Handled) return true;
        }
        return base.ProcessCmdKey(ref msg, keyData);
    }
}
'@

# The only key codes and apps this remote sends.
$Apps = [ordered]@{
    'Dashboard'   = 'com.gnosh.tvhome'
    'YouTube'     = 'com.google.android.youtube.tv'
    'Netflix'     = 'com.netflix.ninja'
    'Prime Video' = 'com.amazon.amazonvideo.livingroom'
    'Disney+'     = 'com.disney.disneyplus'
    'Max'         = 'com.wbd.stream'
    'Hulu'        = 'com.hulu.livingroomplus'
    'Peacock'     = 'com.peacocktv.peacockandroid'
    'Paramount+'  = 'com.cbs.ott'
    'Apple TV'    = 'com.apple.atve.androidtv.appletv'
    'YouTube TV'  = 'com.google.android.youtube.tvunplugged'
    'Spotify'     = 'com.spotify.tv.android'
}

# Which of those the TV has (one "pm list packages" at start-up); the rest are greyed out. Null: unknown, all stay on.
function Get-TvPackages {
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $Adb; $psi.Arguments = "-s $Serial shell pm list packages"
        $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
        $psi.RedirectStandardOutput = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        $out = $p.StandardOutput.ReadToEndAsync()
        # Bounded both ways (readasync-hang): the process, and the read of its output.
        if (-not $p.WaitForExit(5000) -or -not $out.Wait(2000)) { try { $p.Kill() } catch {}; return $null }
        $set = @{}
        foreach ($line in ($out.Result -split "`r?`n")) { if ($line -match '\Apackage:([A-Za-z0-9_.]+)\s*\z') { $set[$Matches[1]] = $true } }
        if ($set.Count -eq 0) { return $null }
        return $set
    } catch { return $null }
}

$script:shell = $null
function Start-TvShell {
    # "adb connect" first (harmless if already connected). Run as a process, not with &, so its stderr can never turn
    # into a PowerShell error under ErrorActionPreference Stop.
    $c = New-Object System.Diagnostics.ProcessStartInfo
    $c.FileName = $Adb; $c.Arguments = "connect $Serial"; $c.UseShellExecute = $false; $c.CreateNoWindow = $true
    $c.RedirectStandardOutput = $true; $c.RedirectStandardError = $true
    $cp = [System.Diagnostics.Process]::Start($c)
    [void]$cp.WaitForExit(2000)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = "-s $Serial shell"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    # Drain output so the pipe never fills; nothing from the TV is shown.
    $p.BeginOutputReadLine(); $p.BeginErrorReadLine()
    Start-Sleep -Milliseconds 300
    if ($p.HasExited) { throw "adb shell to $Serial ended at once" }
    $script:shell = $p
}

# All of this runs on the window's thread, so a TV that is off must not freeze it press after press
# (ui-freeze-unreachable): one reconnect attempt per press (about 2.3 s at most), and none for 10 s after one failed,
# so presses made meanwhile are dropped with a message rather than queued and replayed later.
$script:failedAt = [DateTime]::MinValue
function Send-Tv([string]$line) {
    $offline = "Can't reach the TV at $Serial. Is it on and on the home Wi-Fi? Try again in a few seconds."
    try {
        if ($null -eq $script:shell -or $script:shell.HasExited) {
            if (([DateTime]::Now - $script:failedAt).TotalSeconds -lt 10) { $status.Text = $offline; return }
            $status.Text = 'Connecting...'; $status.Refresh()
            Start-TvShell
        }
        $script:shell.StandardInput.WriteLine($line)
        $script:shell.StandardInput.Flush()
        # A write reaches the local adb; it does not prove the TV acted (stale-connected-status), so say "Sent".
        $status.Text = 'Sent to ' + $Serial
    } catch {
        $script:shell = $null
        $script:failedAt = [DateTime]::Now
        $status.Text = $offline
    }
}

function Send-Key([string]$code) {
    if ($code -notmatch '\A[A-Z_0-9]{2,30}\z') { return }
    Send-Tv "input keyevent KEYCODE_$code"
}

function Send-App([string]$pkg) {
    if ($Apps.Values -notcontains $pkg) { return }
    if ($pkg -eq 'com.gnosh.tvhome') {
        # TV Home has two TV launcher entries (the dashboard and "Ask Claude"); monkey could pick either.
        Send-Tv "am start -n com.gnosh.tvhome/.AgendaActivity -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER >/dev/null 2>&1"
    } else {
        Send-Tv "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1"
    }
}

function Send-Text([string]$text) {
    $t = $text.Trim()
    if ($t.Length -eq 0) { return }
    # No leading '-', which input would read as an option (input-text-leading-dash).
    if ($t.Length -gt 100 -or $t -notmatch '\A[A-Za-z0-9 .,@_][A-Za-z0-9 .,@_-]*\z') {
        $status.Text = 'Text can only use letters, numbers, spaces and . , - _ @ (up to 100).'
        return
    }
    Send-Tv ("input text '" + ($t -replace ' ', '%s') + "'")
}

# ---- window ----
$bg = [System.Drawing.Color]::FromArgb(18, 22, 30)
$btnColor = [System.Drawing.Color]::FromArgb(40, 48, 64)
$okColor = [System.Drawing.Color]::FromArgb(255, 184, 28)
$form = New-Object TvRemoteForm
$form.Text = 'TV remote'
$form.BackColor = $bg
$form.ForeColor = [System.Drawing.Color]::White
$form.ClientSize = New-Object System.Drawing.Size(420, 960)

$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox = $false
$form.StartPosition = 'CenterScreen'
$font = New-Object System.Drawing.Font('Segoe UI', 14, [System.Drawing.FontStyle]::Regular)
$form.Font = $font

function New-Btn([string]$label, [int]$x, [int]$y, [int]$w, [int]$h, [scriptblock]$action, $color = $btnColor) {
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $label
    $b.SetBounds($x, $y, $w, $h)
    $b.FlatStyle = 'Flat'
    $b.FlatAppearance.BorderSize = 0
    $b.BackColor = $color
    $b.ForeColor = if ($color -eq $okColor) { [System.Drawing.Color]::Black } else { [System.Drawing.Color]::White }
    $b.TabStop = $false
    $b.Add_Click($action)
    $form.Controls.Add($b)
    $b
}

# The buttons of a Google TV remote, top to bottom.
$small = New-Object System.Drawing.Font('Segoe UI', 11)
function Row3([int]$y, [int]$h, $items) {
    $x = 20
    foreach ($it in $items) {
        $code = $it[1]
        $b = New-Btn $it[0] $x $y 120 $h { Send-Key $this.Tag }
        $b.Tag = $code
        if ($it[0].Length -gt 6) { $b.Font = $small }
        $x += 130
    }
}
Row3 20 56 @(@('Power', 'POWER'), @('Input', 'TV_INPUT'), @('Settings', 'SETTINGS'))
Row3 84 56 @(@('Assistant', 'SEARCH'), @('Home', 'HOME'), @('Back', 'BACK'))

# D-pad
New-Btn ([char]0x25B2) 150 150 120 76 { Send-Key 'DPAD_UP' } | Out-Null
New-Btn ([char]0x25C0) 20 232 120 104 { Send-Key 'DPAD_LEFT' } | Out-Null
New-Btn 'OK' 150 232 120 104 { Send-Key 'DPAD_CENTER' } $okColor | Out-Null
New-Btn ([char]0x25B6) 280 232 120 104 { Send-Key 'DPAD_RIGHT' } | Out-Null
New-Btn ([char]0x25BC) 150 342 120 76 { Send-Key 'DPAD_DOWN' } | Out-Null

# Media, volume, channels
Row3 432 52 @(@('<< Rew', 'MEDIA_REWIND'), @('Play/Pause', 'MEDIA_PLAY_PAUSE'), @('Fwd >>', 'MEDIA_FAST_FORWARD'))
Row3 490 52 @(@('Vol -', 'VOLUME_DOWN'), @('Mute', 'VOLUME_MUTE'), @('Vol +', 'VOLUME_UP'))
Row3 548 46 @(@('Ch -', 'CHANNEL_DOWN'), @('Guide', 'GUIDE'), @('Ch +', 'CHANNEL_UP'))

# Streaming hotkeys: three columns.
$appButtons = @()
$i = 0
foreach ($name in $Apps.Keys) {
    $b = New-Btn $name (20 + 130 * ($i % 3)) (608 + 52 * [math]::Floor($i / 3)) 120 44 { Send-App $this.Tag }
    $b.Tag = $Apps[$name]
    $b.Font = $small
    $appButtons += $b
    $i++
}

# Type text
$box = New-Object System.Windows.Forms.TextBox
$box.SetBounds(20, 828, 290, 40)
$box.BackColor = $btnColor
$box.ForeColor = [System.Drawing.Color]::White
$box.MaxLength = 100
$form.Controls.Add($box)
New-Btn 'Send' 320 826 80 40 { Send-Text $box.Text; $box.Clear() } | Out-Null

$status = New-Object System.Windows.Forms.Label
$status.SetBounds(20, 880, 380, 60)
$status.ForeColor = [System.Drawing.Color]::FromArgb(160, 176, 204)
$status.Font = New-Object System.Drawing.Font('Segoe UI', 10)
$status.Text = 'Connecting...'
$form.Controls.Add($status)

# Keyboard: arrows, Enter = OK (KeyData names it Return: enter-key-return), Backspace = Back, Escape = Home. In the text box, Enter sends the text and Escape
# leaves the box.
$keyMap = @{ Up = 'DPAD_UP'; Down = 'DPAD_DOWN'; Left = 'DPAD_LEFT'; Right = 'DPAD_RIGHT'; Enter = 'DPAD_CENTER'; Return = 'DPAD_CENTER'; Back = 'BACK'; Escape = 'HOME' }
$form.add_RemoteKey({
    param($s, $e)
    $k = [string]$e.KeyData
    if ($keyMap.ContainsKey($k)) { Send-Key $keyMap[$k]; $e.Handled = $true }
})
$box.Add_KeyDown({
    param($s, $e)
    if ($e.KeyCode -eq 'Enter') { Send-Text $box.Text; $box.Clear(); $e.SuppressKeyPress = $true }
    elseif ($e.KeyCode -eq 'Escape') { $form.ActiveControl = $null; $e.SuppressKeyPress = $true }
})
# A shorter screen (a laptop, or display scaling above 100%): shrink everything to fit rather than scroll, since a
# touchscreen cannot drag-scroll a window (autoscroll-touch).
$work = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$fit = [math]::Max(0.6, [math]::Min(1.0, ($work.Height - 50) / 960.0))
if ($fit -lt 1.0) {
    $form.Scale((New-Object System.Drawing.SizeF([single]$fit, [single]$fit)))
    # Read every size first: a child that inherits the form's font would otherwise shrink twice.
    $sizes = @{}; foreach ($c in @($form.Controls)) { $sizes[$c] = $c.Font }
    $formFont = $form.Font
    $form.Font = New-Object System.Drawing.Font($formFont.FontFamily, [single]($formFont.Size * $fit))
    foreach ($c in @($form.Controls)) { $f = $sizes[$c]; $c.Font = New-Object System.Drawing.Font($f.FontFamily, [single][math]::Max(8, $f.Size * $fit)) }
}
$form.Add_Shown({
    $form.ActiveControl = $null
    try {
        Start-TvShell; $status.Text = 'Ready: ' + $Serial + '. Checking which apps the TV has...'; $status.Refresh()
        $have = Get-TvPackages
        if ($null -ne $have) {
            $status.Text = 'Ready: ' + $Serial + '. Greyed-out apps are not on the TV.'
            foreach ($b in $appButtons) {
                if (-not $have.ContainsKey($b.Tag)) { $b.Enabled = $false; $b.BackColor = $bg }
            }
        }
    }
    catch { $script:failedAt = [DateTime]::Now; $status.Text = "Can't reach the TV at $Serial. Is it on and on the home Wi-Fi?" }
})
$form.Add_FormClosed({
    if ($script:shell -and -not $script:shell.HasExited) {
        try { $script:shell.StandardInput.WriteLine('exit'); [void]$script:shell.WaitForExit(1000) } catch {}
        try { if (-not $script:shell.HasExited) { $script:shell.Kill() } } catch {}
    }
})

[void]$form.ShowDialog()
