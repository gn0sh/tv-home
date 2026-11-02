<#
.SYNOPSIS
  An on-screen remote for the Google TV, for the PC's touchscreen or mouse. Run it in your own PowerShell window.
.DESCRIPTION
  Big buttons: arrows and OK, Back, Home, the dashboard, play/pause, volume, mute, power, app shortcuts and a box to
  type text into a search field on the TV. The window's keyboard works too: arrow keys, Enter (OK), Backspace (Back)
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
# Started from the "TV Remote" icon there is no console, so a startup error would vanish: show it in a box instead.
trap {
    Add-Type -AssemblyName System.Windows.Forms
    [void][System.Windows.Forms.MessageBox]::Show("TV remote could not start: $($_.Exception.Message)", 'TV remote')
    exit 1
}

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
    'Dashboard' = 'com.gnosh.tvhome'
    'YouTube'   = 'com.google.android.youtube.tv'
    'Play Store'= 'com.android.vending'
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
    Send-Tv "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1"
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
$form.ClientSize = New-Object System.Drawing.Size(420, 760)
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

# Top row
New-Btn 'Power' 20 20 120 60 { Send-Key 'POWER' } | Out-Null
New-Btn 'Home' 150 20 120 60 { Send-Key 'HOME' } | Out-Null
New-Btn 'Back' 280 20 120 60 { Send-Key 'BACK' } | Out-Null

# D-pad
New-Btn ([char]0x25B2) 150 100 120 90 { Send-Key 'DPAD_UP' } | Out-Null
New-Btn ([char]0x25C0) 20 200 120 120 { Send-Key 'DPAD_LEFT' } | Out-Null
New-Btn 'OK' 150 200 120 120 { Send-Key 'DPAD_CENTER' } $okColor | Out-Null
New-Btn ([char]0x25B6) 280 200 120 120 { Send-Key 'DPAD_RIGHT' } | Out-Null
New-Btn ([char]0x25BC) 150 330 120 90 { Send-Key 'DPAD_DOWN' } | Out-Null

# Media and volume
New-Btn 'Vol -' 20 440 120 60 { Send-Key 'VOLUME_DOWN' } | Out-Null
New-Btn 'Play/Pause' 150 440 120 60 { Send-Key 'MEDIA_PLAY_PAUSE' } | Out-Null
New-Btn 'Vol +' 280 440 120 60 { Send-Key 'VOLUME_UP' } | Out-Null
New-Btn 'Mute' 150 510 120 50 { Send-Key 'VOLUME_MUTE' } | Out-Null

# Apps
$x = 20
foreach ($name in $Apps.Keys) {
    $pkg = $Apps[$name]
    $b = New-Btn $name $x 580 120 50 { Send-App $this.Tag }
    $b.Tag = $pkg
    $b.Font = New-Object System.Drawing.Font('Segoe UI', 11)
    $x += 130
}

# Type text
$box = New-Object System.Windows.Forms.TextBox
$box.SetBounds(20, 650, 290, 40)
$box.BackColor = $btnColor
$box.ForeColor = [System.Drawing.Color]::White
$box.MaxLength = 100
$form.Controls.Add($box)
New-Btn 'Send' 320 648 80 40 { Send-Text $box.Text; $box.Clear() } | Out-Null

$status = New-Object System.Windows.Forms.Label
$status.SetBounds(20, 705, 380, 40)
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
$form.Add_Shown({
    $form.ActiveControl = $null
    try { Start-TvShell; $status.Text = 'Ready: ' + $Serial }
    catch { $script:failedAt = [DateTime]::Now; $status.Text = "Can't reach the TV at $Serial. Is it on and on the home Wi-Fi?" }
})
$form.Add_FormClosed({
    if ($script:shell -and -not $script:shell.HasExited) {
        try { $script:shell.StandardInput.WriteLine('exit'); [void]$script:shell.WaitForExit(1000) } catch {}
        try { if (-not $script:shell.HasExited) { $script:shell.Kill() } } catch {}
    }
})

[void]$form.ShowDialog()
