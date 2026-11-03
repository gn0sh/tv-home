<#
.SYNOPSIS
  One step to install the latest signed TV Home release on the Google TV. Double-click "Install TV Home.cmd".
.DESCRIPTION
  1. Finds the release APK in %LOCALAPPDATA%\tv-home-tools\incoming\ (exactly one .apk; more than one is refused).
     With -Fetch and an empty folder, it gets the APK itself with the GitHub CLI (gh), from the newest successful
     push-to-main run of gn0sh/tv-home only (the run's branch and event are checked). No gh: it says what to do by hand.
  2. Refuses unless the APK's signing certificate SHA-256 equals the pinned value below, the package is
     com.gnosh.tvhome and the APK is not debuggable. The certificate is read with apksigner if it is in the tools
     folder (full check), else by this script reading the APK's signing block. See docs/platform-facts.md.
  3. Reads the TV's adb address from %LOCALAPPDATA%\tv-home-tools\tv-home-install.json (made on first run with the
     address the other scripts use), then runs: adb install -r
  4. Moves the installed APK to incoming\installed\, so running it again never reinstalls an old file.
  Changes only the TV Home app on the TV. It never downloads or runs code, never reads the signing-key folder or the
  calendar link, and prints no secrets. Windows PowerShell 5.1 or later.
.PARAMETER Fetch  get the APK with gh when the incoming folder has none
.PARAMETER Force  with -Fetch, reinstall even if this build was already installed
#>
[CmdletBinding()]
param(
    [switch]$Fetch,
    [switch]$Force
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PinnedSha256 = '18:E2:7C:A1:DF:5A:EA:B8:4C:E4:65:8E:0E:A3:94:02:67:77:1F:71:DE:9C:4B:49:07:96:F2:7C:2C:4B:64:2A'
$Package = 'com.gnosh.tvhome'
$Repo = 'gn0sh/tv-home'
$Workflow = 'android.yml'
$ArtifactName = 'tv-home-release-apk'
$DefaultSerial = '192.168.68.71:5555'   # same default as tv-home-takeover.ps1

$Tools = Join-Path $env:LOCALAPPDATA 'tv-home-tools'
$Incoming = Join-Path $Tools 'incoming'
$Done = Join-Path $Incoming 'installed'
$Config = Join-Path $Tools 'tv-home-install.json'
$LastRun = Join-Path $Tools 'installed-run.txt'

function Fail([string]$msg) {
    Write-Host ''
    Write-Host "NOT INSTALLED. $msg" -ForegroundColor Red
    exit 1
}

# ---- little-endian readers; every read is bounds-checked, so a damaged file fails closed ----------------------
function U16([byte[]]$b, [long]$o) { if ($o -lt 0 -or $o + 2 -gt $b.Length) { throw 'short read' }; [long][BitConverter]::ToUInt16($b, [int]$o) }
function U32([byte[]]$b, [long]$o) { if ($o -lt 0 -or $o + 4 -gt $b.Length) { throw 'short read' }; [long][BitConverter]::ToUInt32($b, [int]$o) }
function U64([byte[]]$b, [long]$o) { if ($o -lt 0 -or $o + 8 -gt $b.Length) { throw 'short read' }; [long][BitConverter]::ToUInt64($b, [int]$o) }
function Read-At([IO.FileStream]$fs, [long]$pos, [long]$n) {
    if ($pos -lt 0 -or $n -lt 0 -or $n -gt 16MB -or $pos + $n -gt $fs.Length) { throw 'bad range' }
    $buf = New-Object byte[] ([int]$n)
    [void]$fs.Seek($pos, 'Begin')
    $got = 0
    while ($got -lt $n) {
        $r = $fs.Read($buf, $got, [int]($n - $got))
        if ($r -le 0) { throw 'short read' }
        $got += $r
    }
    return , $buf
}
function Format-Sha256([byte[]]$der) {
    $h = [Security.Cryptography.SHA256]::Create().ComputeHash($der)
    return (($h | ForEach-Object { $_.ToString('X2') }) -join ':')
}

# Signer certificates from the APK Signature Scheme v2 (0x7109871a) and v3 (0xf05368c0) blocks. Returns SHA-256 lists.
# This reads which certificate the APK claims; Android itself checks the signature when the APK is installed.
function Get-SigningBlockCerts([string]$Path) {
    $fs = [IO.File]::Open($Path, 'Open', 'Read', 'Read')
    try {
        $len = $fs.Length
        $tailLen = [long][Math]::Min($len, 65557)
        $tail = Read-At $fs ($len - $tailLen) $tailLen
        $eocd = -1
        for ($i = [int]$tailLen - 22; $i -ge 0; $i--) {
            if ($tail[$i] -eq 0x50 -and $tail[$i + 1] -eq 0x4b -and $tail[$i + 2] -eq 5 -and $tail[$i + 3] -eq 6 -and
                ($i + 22 + (U16 $tail ($i + 20))) -eq $tailLen) { $eocd = $i; break }
        }
        if ($eocd -lt 0) { throw 'no end-of-directory record' }
        $cdOff = U32 $tail ($eocd + 16)
        $foot = Read-At $fs ($cdOff - 24) 24
        if ([Text.Encoding]::ASCII.GetString($foot, 8, 16) -cne 'APK Sig Block 42') { throw 'no signing block' }
        $size = U64 $foot 0                       # block size, not counting this first 8-byte field
        if ($size -lt 32 -or $size -gt 16MB) { throw 'odd signing block size' }
        $blk = Read-At $fs ($cdOff - $size - 8) ($size + 8)
        if ((U64 $blk 0) -ne $size) { throw 'signing block sizes differ' }
        $end = $size + 8 - 24
        $res = @{ v2 = @(); v3 = @() }
        $pos = 8
        while ($pos + 12 -le $end) {
            $plen = U64 $blk $pos
            $id = U32 $blk ($pos + 8)
            $valStart = $pos + 12
            $valEnd = $pos + 8 + $plen
            if ($plen -lt 4 -or $valEnd -gt $end) { throw 'odd signing block entry' }
            $key = if ($id -eq 0x7109871a) { 'v2' } elseif ($id -eq 0xf05368c0) { 'v3' } else { $null }
            if ($key) {
                $seqLen = U32 $blk $valStart
                $p = $valStart + 4
                $seqEnd = $p + $seqLen
                if ($seqEnd -gt $valEnd) { throw 'odd signer list' }
                while ($p -lt $seqEnd) {
                    $signerLen = U32 $blk $p
                    $sd = $p + 4                      # signed data: digests, certificates, ...
                    $q = $sd + 4
                    $q += 4 + (U32 $blk $q)           # skip digests
                    $certsLen = U32 $blk $q
                    if ($certsLen -lt 4) { throw 'signer without certificate' }
                    $c1 = U32 $blk ($q + 4)           # first certificate is the signer's own
                    if ($c1 -lt 1 -or $q + 8 + $c1 -gt $blk.Length) { throw 'odd certificate' }
                    $der = New-Object byte[] ([int]$c1)
                    [Array]::Copy($blk, $q + 8, $der, 0, [int]$c1)
                    $res[$key] += , (Format-Sha256 $der)
                    $p = $sd + $signerLen
                }
            }
            $pos += 8 + $plen
        }
        return $res
    } finally { $fs.Dispose() }
}

function Get-ApksignerCert([string]$Path) {
    $signer = Get-ChildItem $Tools -Recurse -Include 'apksigner.bat', 'apksigner.jar' -ErrorAction SilentlyContinue |
        Sort-Object { $_.Extension -ne '.bat' } | Select-Object -First 1
    if (-not $signer) { return $null }
    $java = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($signer.Extension -eq '.jar') {
        if (-not $java) { return $null }
        $argv = @('-jar', $signer.FullName, 'verify', '--print-certs', $Path); $exe = $java.Source
    } else {
        if (-not $java) { return $null }   # the .bat needs java too
        $argv = @('verify', '--print-certs', $Path); $exe = $signer.FullName
    }
    $saved = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $exe @argv 2>&1 | ForEach-Object { "$_" }; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { Fail 'apksigner says this APK is not validly signed.' }
    $hashes = @($out | ForEach-Object { if ($_ -match 'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})') { $Matches[1].ToUpper() } })
    if ($hashes.Count -eq 0) { Fail 'apksigner printed no certificate.' }
    return @($hashes | ForEach-Object { ($_ -replace '(..)(?!$)', '$1:') })
}

# ---- binary AndroidManifest.xml: package name and the debuggable flag ----------------------------------------
function Get-ManifestInfo([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry('AndroidManifest.xml')
        if (-not $entry -or $entry.Length -gt 8MB) { throw 'no manifest' }
        $ms = New-Object IO.MemoryStream
        $s = $entry.Open(); try { $s.CopyTo($ms) } finally { $s.Dispose() }
        $b = $ms.ToArray()
    } finally { $zip.Dispose() }

    if ((U16 $b 0) -ne 3) { throw 'not a binary manifest' }
    $total = [Math]::Min((U32 $b 4), $b.Length)
    $strings = @(); $resIds = @(); $pkg = $null; $debuggable = $false; $sawApp = $false
    $pos = U16 $b 2
    while ($pos + 8 -le $total) {
        $type = U16 $b $pos; $hdr = U16 $b ($pos + 2); $size = U32 $b ($pos + 4)
        if ($size -lt 8 -or $pos + $size -gt $b.Length) { throw 'odd chunk' }
        if ($type -eq 0x0001) {
            $count = U32 $b ($pos + 8); $flags = U32 $b ($pos + 16); $start = U32 $b ($pos + 20)
            if ($count -gt 100000) { throw 'odd string pool' }
            $strings = New-Object string[] ([int]$count)
            for ($i = 0; $i -lt $count; $i++) {
                $o = $pos + $start + (U32 $b ($pos + $hdr + 4 * $i))
                if ($flags -band 0x100) {
                    if ((U16 $b $o) -band 0x80) { $o += 2 } else { $o += 1 }   # character count (unused)
                    $l = [long]$b[$o]
                    if ($l -band 0x80) { $l = (($l -band 0x7F) -shl 8) -bor [long]$b[$o + 1]; $o += 2 } else { $o += 1 }
                    if ($o + $l -gt $b.Length) { throw 'odd string' }
                    $strings[$i] = [Text.Encoding]::UTF8.GetString($b, [int]$o, [int]$l)
                } else {
                    $l = U16 $b $o
                    if ($l -band 0x8000) { $l = (($l -band 0x7FFF) -shl 16) -bor (U16 $b ($o + 2)); $o += 4 } else { $o += 2 }
                    if ($o + 2 * $l -gt $b.Length) { throw 'odd string' }
                    $strings[$i] = [Text.Encoding]::Unicode.GetString($b, [int]$o, [int](2 * $l))
                }
            }
        } elseif ($type -eq 0x0180) {
            $n = [int](($size - $hdr) / 4)
            $resIds = New-Object long[] $n
            for ($i = 0; $i -lt $n; $i++) { $resIds[$i] = U32 $b ($pos + $hdr + 4 * $i) }
        } elseif ($type -eq 0x0102) {
            $nameIdx = U32 $b ($pos + 20)
            $attrStart = U16 $b ($pos + 24); $attrSize = U16 $b ($pos + 26); $attrCount = U16 $b ($pos + 28)
            if ($nameIdx -ge $strings.Length -or $attrSize -lt 20) { throw 'odd element' }
            $elem = $strings[$nameIdx]
            for ($a = 0; $a -lt $attrCount; $a++) {
                $ao = $pos + 16 + $attrStart + $a * $attrSize
                $an = U32 $b ($ao + 4); $raw = U32 $b ($ao + 8); $dtype = [long]$b[$ao + 15]; $data = U32 $b ($ao + 16)
                if ($an -ge $strings.Length) { continue }
                if ($elem -ceq 'manifest' -and $strings[$an] -ceq 'package' -and $raw -lt $strings.Length -and $raw -ge 0) { $pkg = $strings[$raw] }
                if ($elem -ceq 'application') {
                    $sawApp = $true
                    $isDbg = ($an -lt $resIds.Length -and $resIds[$an] -eq 0x0101000f) -or ($strings[$an] -ceq 'debuggable')
                    if ($isDbg -and ($dtype -ne 0x12 -or $data -ne 0)) { $debuggable = $true }   # anything but a plain false counts
                }
            }
        }
        $pos += $size
    }
    if (-not $pkg -or -not $sawApp) { throw 'manifest incomplete' }
    [pscustomobject]@{ Package = $pkg; Debuggable = $debuggable }
}

# Runs a program under Continue (adb and gh write progress to stderr, which Stop would turn into an error).
function Invoke-Tool([string]$Exe, [string[]]$Argv) {
    $saved = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $Exe @Argv 2>&1 | ForEach-Object { "$_" }; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
    [pscustomobject]@{ Code = $code; Text = (($out | Where-Object { $_ }) -join "`n").Trim() }
}

function Get-IncomingApks {
    @(Get-ChildItem $Incoming -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -ieq '.apk' })
}

# ---- fetch from the newest successful push-to-main run, with gh ------------------------------------------------
function Get-LatestMainApk {
    $gh = Get-Command gh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $gh) {
        Fail ("No APK in $Incoming and the GitHub CLI (gh) is not on this PC.`n" +
              "By hand: open https://github.com/$Repo/actions/workflows/$Workflow , open the newest green run on main,`n" +
              "download the '$ArtifactName' file, unzip it, put the single .apk in $Incoming , then double-click Install TV Home again.")
    }
    if ((Invoke-Tool $gh.Source @('auth', 'status')).Code -ne 0) {
        Fail "gh is not signed in to GitHub. In PowerShell, run: gh auth login (once). Or download by hand: https://github.com/$Repo/actions/workflows/$Workflow"
    }
    $list = Invoke-Tool $gh.Source @('run', 'list', '--repo', $Repo, '--workflow', $Workflow, '--branch', 'main', '--event', 'push',
        '--status', 'success', '--limit', '1', '--json', 'databaseId,headBranch,headSha,event,conclusion')
    if ($list.Code -ne 0) { Fail 'gh could not list the build runs.' }
    $runs = @($list.Text | ConvertFrom-Json)
    if ($runs.Count -ne 1) { Fail 'No successful build on main was found.' }
    $run = $runs[0]
    # Checked again here, not just asked of gh: only a push to main is ever installed.
    if ($run.headBranch -cne 'main' -or $run.event -cne 'push' -or $run.conclusion -cne 'success') { Fail 'The newest run is not a successful push to main.' }
    $id = [string]$run.databaseId
    if ($id -notmatch '^\d+$') { Fail 'Odd run id from gh.' }
    $last = if (Test-Path $LastRun) { (Get-Content $LastRun -TotalCount 1) } else { '' }
    if ($last -eq $id -and -not $Force) {
        Write-Host "Build $id (main $($run.headSha.Substring(0, 7))) is already installed. Nothing to do. (Use -Force to install it again.)" -ForegroundColor Green
        Write-Host 'DONE. TV Home on the TV is already the newest build from main.'
        exit 0
    }
    Write-Host "Getting the APK from build $id (main $($run.headSha.Substring(0, 7)))..."
    $tmp = Join-Path $Tools ('fetch-' + $id)
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    $dl = Invoke-Tool $gh.Source @('run', 'download', $id, '--repo', $Repo, '--name', $ArtifactName, '--dir', $tmp)
    if ($dl.Code -ne 0) { Fail 'gh could not download the APK (builds are kept 7 days; push a new build if this one is older).' }
    $found = @(Get-ChildItem $tmp -Recurse -File | Where-Object { $_.Extension -ieq '.apk' })
    if ($found.Count -ne 1) { Remove-Item $tmp -Recurse -Force; Fail "The build held $($found.Count) APK files, expected exactly one." }
    Move-Item $found[0].FullName (Join-Path $Incoming ('tv-home-' + $id + '.apk'))
    Remove-Item $tmp -Recurse -Force
    return $id
}

# ================================ main ========================================================================
$fetchedRun = $null
try {
    New-Item -ItemType Directory -Force -Path $Incoming | Out-Null
    $apks = Get-IncomingApks
    if ($apks.Count -eq 0 -and $Fetch) { $fetchedRun = Get-LatestMainApk; $apks = Get-IncomingApks }
    if ($apks.Count -eq 0) { Fail "No APK in $Incoming . Double-click Install TV Home (it fetches the newest one), or put the single .apk there." }
    if ($apks.Count -gt 1) { Fail "$($apks.Count) APK files are in $Incoming ; there must be exactly one. Nothing was installed. Tell the team which is the right one." }
    $apk = $apks[0].FullName
    Write-Host "APK: $apk"

    # --- signing certificate ---
    $certs = Get-ApksignerCert $apk
    $how = 'apksigner'
    if ($null -eq $certs) {
        $how = 'signing block'
        $blk = Get-SigningBlockCerts $apk
        $certs = @($blk.v2) + @($blk.v3)
        if (@($blk.v2).Count -gt 1 -or @($blk.v3).Count -gt 1) { Fail 'The APK has more than one signer.' }
    }
    if (@($certs).Count -eq 0) { Fail 'The APK carries no readable signing certificate (an unsigned build?).' }
    foreach ($c in $certs) {
        if ($c -cne $PinnedSha256) { Fail "The APK's signing certificate does not match Brian's key ($c). Do not install it; tell the team." }
    }
    Write-Host "Signing certificate matches ($how): $PinnedSha256" -ForegroundColor Green

    # --- package and debuggable ---
    $m = Get-ManifestInfo $apk
    if ($m.Package -cne $Package) { Fail "The APK's package is '$($m.Package)', not $Package." }
    if ($m.Debuggable) { Fail 'The APK is a debuggable build.' }
    Write-Host "Package is $Package and the build is not debuggable." -ForegroundColor Green

    # --- adb and the TV ---
    $adbItem = Get-ChildItem $Tools -Recurse -Filter adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $adbItem) { Fail "adb.exe not found under $Tools (the tv-home install puts it there)." }
    if (-not (Test-Path $Config)) {
        (@{ serial = $DefaultSerial } | ConvertTo-Json) | Set-Content -Path $Config -Encoding ASCII
        Write-Host "Made $Config with the TV address $DefaultSerial ; edit it if the TV's address changes."
    }
    $serial = [string](Get-Content $Config -Raw | ConvertFrom-Json).serial
    if ($serial -notmatch '\A\d{1,3}(\.\d{1,3}){3}:\d{1,5}\z') { Fail "The TV address in $Config must look like 192.168.68.71:5555." }
    [void](Invoke-Tool $adbItem.FullName @('connect', $serial))
    $state = Invoke-Tool $adbItem.FullName @('-s', $serial, 'get-state')
    if ($state.Code -ne 0 -or $state.Text -cne 'device') {
        Fail "The TV at $serial did not answer. Turn the TV on, check its address (Settings > System > About > Status; update $Config), and make sure USB/network debugging is on."
    }

    # --- install ---
    Write-Host "Installing on $serial ..."
    $r = Invoke-Tool $adbItem.FullName @('-s', $serial, 'install', '-r', $apk)
    if ($r.Code -ne 0 -or $r.Text -notmatch '(?m)^Success') {
        $why = ($r.Text -split "`n" | Select-Object -Last 1)
        if ($why -match 'VERSION_DOWNGRADE') { $why = 'The TV already has a newer version.' }
        elseif ($why -match 'UPDATE_INCOMPATIBLE|SIGNATURES_DO_NOT_MATCH') { $why = 'The TV has a copy signed with a different key; tell the team (it must not be uninstalled without asking).' }
        Fail "adb said: $why"
    }
    New-Item -ItemType Directory -Force -Path $Done | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    Move-Item $apk (Join-Path $Done ($stamp + '-' + (Split-Path $apk -Leaf)))
    if ($fetchedRun) { Set-Content -Path $LastRun -Value $fetchedRun -Encoding ASCII }
    Write-Host ''
    Write-Host 'DONE. TV Home is installed on the TV. On the TV, open TV Home (or press Home if you turned that on): it is the new version, and your calendar and settings are still there.' -ForegroundColor Green
    exit 0
} catch {
    Fail 'Something unexpected stopped the install before anything was changed on the TV (the APK could not be read as an APK, or a file could not be used). Tell the team.'
}
