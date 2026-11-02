# ADR 0006: the PC on-screen remote becomes a pywebview app, with volume sent to the LG

Status: accepted (frontend-engineer), 2026-11-02.
Code: `tv-remote/src/tv_remote/pcremote.py`, `tv-remote/src/tv_remote/pcremote_ui/` (page and icon),
`tv-remote/tools/make_icon.py`, `scripts/install-remote-shortcut.ps1`. Tests: `tv-remote/tests/test_pcremote.py`.

## Context
Brian asked for two things about the PC remote (`scripts/tv-remote-pc.ps1`, a WinForms window): make it look and feel
like a consumer product (dark, rounded, a real round D-pad, icon buttons, clear pressed feedback, app tiles, big
touch targets, nothing hover-only), and make the volume buttons work. The LG BX ignores HDMI-CEC volume from the
Google TV, so `KEYCODE_VOLUME_*` over adb does nothing to the sound.

## Decision
- The remote is a small Python desktop app in the existing `tv-remote` uv package: gui-script `tv-remote-app`
  (`tv_remote.pcremote:main`). Window: **pywebview 6.2.1** (BSD-3, free), which on Windows draws with Edge WebView2
  (preinstalled on Windows 10/11) through pythonnet 3.2.1 (supports Python 3.11 to 3.15).
- The UI is one self-contained HTML/CSS/JS page shipped in the package, loaded with `create_window(html=...)`. No CDN,
  no fonts or images fetched (icons are inline SVG drawn in the page). pywebview starts its bottle HTTP server only for
  local file URLs or `http_server=True`; neither is used, so **no port is opened**.
- The page reaches Python only through pywebview's `js_api` bridge. `RemoteApi` has exactly five public methods
  (`start`, `key`, `app`, `text`, `volume`; pywebview skips `_` names and the tests check that nothing else is public).
  Every argument is checked in Python against fixed allowlists: the 17 key codes of the classic remote, its 12 app
  packages, text matching the classic remote's rule, and `up|down|mute`.
- Google TV: the same mechanism as the classic remote, unchanged: adb from `%LOCALAPPDATA%\tv-home-tools`, `adb
  connect`, one long-lived `adb -s <serial> shell` fed `input keyevent`, `input text '...'` and `monkey -p <pkg> -c
  android.intent.category.LEANBACK_LAUNCHER 1` lines, a bounded `pm list packages` at start to dim missing apps, a 10 s
  backoff after a failed connect (presses dropped, not queued), and the same single-instance mutex name
  (`Local\gnosh-tv-remote`), so the old and new remotes never run at once. adb runs with `CREATE_NO_WINDOW`.
- Volume and Mute: `lgpipe.handle` in-process, on one private asyncio loop thread, with `LgRemote` built from
  `%APPDATA%\tv-remote` as `lgpipe.main` does (wss 3001 only, pinned certificate, 8 permissions, cap 60 per ADR 0003).
  If the LG is not configured, or `handle` returns an error, the adb VOLUME key is sent instead and the status says
  which. After the LG fails to answer, it is skipped for 10 s; at most 2 volume presses wait on the LG and more are
  dropped, so a TV that is off never replays a burst of volume changes later.
- Status text shows only the Google TV's adb address (as the classic remote did) and lgpipe's fixed reasons. Anything
  else from the LG path (unexpected text) shows as "did not work". The LG address, client key, certificate and DPAPI
  files never reach the page.
- Launch: `scripts/install-remote-shortcut.ps1` runs `uv sync --locked --project tv-remote` and points the icon at
  `tv-remote\.venv\Scripts\tv-remote-app.exe`. uv's docs: gui-scripts "are wrapped by a GUI executable so they can be
  started without a console" on Windows. This avoids depending on `uvw.exe`, which we could not confirm ships with
  every uv release, and `uv run` (a console program) would flash a window. `-Classic` keeps the PowerShell remote.
- Icon: drawn in code with Pillow (`tools/make_icon.py`, run by hand; Pillow is not an app dependency) and committed
  as `remote.ico`. App tiles show the service name on a brand-like colour; no logos.
- Layout: the page keeps fixed proportions and scales to the window; it picks a tall (400-wide) or wide (two-column)
  arrangement, whichever is bigger at the window's size, so short or scaled screens (1366x768 at 100 or 125%) fit
  without scrolling, which a touchscreen cannot do in a fixed window.

## Alternatives considered
- Keep WinForms and restyle it: owner-drawn round buttons and a circular D-pad in PowerShell is a lot of fragile code,
  and the LG pairing (DPAPI-sealed key, pinned TLS) lives in Python. It would need a second process (`tv-remote-lg`
  over a pipe) for volume anyway.
- A local web page in the browser with an HTTP server: opens a port on the PC; rejected.
- Electron/Tauri: a new toolchain and a large download for one window.

## Consequences and risks
- First run needs `uv sync` to download pywebview, pythonnet, clr-loader, cffi and bottle (all hashed in `uv.lock`).
- pythonnet needs .NET Framework 4.7.2+ (built into Windows 10/11) and WebView2 Runtime (preinstalled; if missing,
  the app shows a start-up error box). Not verified on Brian's PC from the Linux sandbox; first-run check is on him.
- No Content-Security-Policy meta on the page: pywebview injects its bridge script into the page, and a strict CSP
  could block it. The page loads nothing external and writes only fixed strings or numbers into the DOM
  (status via `textContent`).
- The taskbar button uses its own AppUserModelID (`gnosh.tv-remote`); a pinned "TV Remote" shortcut and the running
  window may show as two taskbar buttons. Cosmetic.
