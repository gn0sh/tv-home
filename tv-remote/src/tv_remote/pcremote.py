"""The PC on-screen remote (ADR 0006): a pywebview window whose page talks to this module only through the five
functions of make_bridge, registered with ``window.expose``; the ``js_api`` object is SEALED, so page script cannot
walk to anything else. No HTTP server, no listening port.

Google TV keys, app launches and typed text go over one long-lived ``adb -s <serial> shell``, exactly as the classic
PowerShell remote (scripts/tv-remote-pc.ps1) does. Volume and mute go to the paired LG directly (lgpipe.handle, cap 60,
ADR 0003), because the LG ignores HDMI-CEC volume from the Google TV; if the LG is not set up or does not answer, the
adb VOLUME key is sent instead and the status line says so.

Everything the page sends is checked here against fixed allowlists before anything reaches a device. Status strings
never carry the LG's address, client key, certificate or any exception text.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import subprocess
import sys
import threading
import time
from collections.abc import Callable
from concurrent.futures import CancelledError
from concurrent.futures import TimeoutError as FutureTimeout
from pathlib import Path
from typing import Any

from .lgpipe import VOLUME_CAP

log = logging.getLogger("tv_remote.pcremote")

DEFAULT_SERIAL = "192.168.68.71:5555"
SERIAL_RE = re.compile(r"\A\d{1,3}(\.\d{1,3}){3}:\d{1,5}\Z")
MUTEX_NAME = "Local\\gnosh-tv-remote"  # the same name as the classic remote, so only one of them runs at a time

# The only key codes the page may send (the classic remote's buttons and keyboard). Volume goes through volume().
KEYS = frozenset({
    "POWER", "TV_INPUT", "SETTINGS", "SEARCH", "HOME", "BACK",
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
    "MEDIA_REWIND", "MEDIA_PLAY_PAUSE", "MEDIA_FAST_FORWARD",
    "CHANNEL_DOWN", "GUIDE", "CHANNEL_UP",
})
# The app hotkeys, in order: (label, package). The same 12 as the classic remote.
APPS: tuple[tuple[str, str], ...] = (
    ("Dashboard", "com.gnosh.tvhome"),
    ("YouTube", "com.google.android.youtube.tv"),
    ("Netflix", "com.netflix.ninja"),
    ("Prime Video", "com.amazon.amazonvideo.livingroom"),
    ("Disney+", "com.disney.disneyplus"),
    ("Max", "com.wbd.stream"),
    ("Hulu", "com.hulu.livingroomplus"),
    ("Peacock", "com.peacocktv.peacockandroid"),
    ("Paramount+", "com.cbs.ott"),
    ("Apple TV", "com.apple.atve.androidtv.appletv"),
    ("YouTube TV", "com.google.android.youtube.tvunplugged"),
    ("Spotify", "com.spotify.tv.android"),
)
PACKAGES = frozenset(pkg for _, pkg in APPS)
VOLUME_KEYS = {"up": "VOLUME_UP", "down": "VOLUME_DOWN", "mute": "VOLUME_MUTE"}
# No leading '-', which `input text` would read as an option (input-text-leading-dash).
TEXT_RE = re.compile(r"\A[A-Za-z0-9 .,@_][A-Za-z0-9 .,@_-]*\Z")
TEXT_MAX = 100
TEXT_HELP = "Text can only use letters, numbers, spaces and . , - _ @ (up to 100)."
RETRY_AFTER = 10.0  # seconds: after a failed connect, presses are dropped with a message, not queued (ui-freeze)
LG_TIMEOUT = 20.0  # connect (8 s) + request (6 s) in lg.py, with room to spare
LG_MAX_PENDING = 2  # volume presses waiting on the LG; more are dropped rather than replayed late
LG_REASON_RE = re.compile(r"\A[A-Za-z' ;]{1,60}\Z")  # lgpipe's fixed reasons; anything else is not shown

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # adb must not flash a console from a GUI app


# ---- the command lines (pure, tested) ----

def key_line(code: object) -> str | None:
    if not isinstance(code, str) or code not in KEYS:
        return None
    return f"input keyevent KEYCODE_{code}"


def volume_key_line(cmd: str) -> str:
    return f"input keyevent KEYCODE_{VOLUME_KEYS[cmd]}"


def app_line(pkg: object) -> str | None:
    if not isinstance(pkg, str) or pkg not in PACKAGES:
        return None
    return f"monkey -p {pkg} -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1"


def text_line(text: object) -> str | None:
    """The `input text` line for typed text, or None when it is not allowed. Spaces become %s."""
    if not isinstance(text, str):
        return None
    t = text.strip()
    if not t or len(t) > TEXT_MAX or not TEXT_RE.match(t):
        return None
    return "input text '" + t.replace(" ", "%s") + "'"


def parse_packages(out: str) -> set[str] | None:
    found = set()
    for line in out.splitlines():
        m = re.match(r"\Apackage:([A-Za-z0-9_.]+)\s*\Z", line)
        if m:
            found.add(m.group(1))
    return found or None


def find_adb(base: Path) -> Path | None:
    """The adb the tv-home install puts under %LOCALAPPDATA%\\tv-home-tools (first match, like the classic remote)."""
    name = "adb.exe" if sys.platform == "win32" else "adb"
    try:
        return next((p for p in sorted(base.rglob(name)) if p.is_file()), None)
    except OSError:
        return None


# ---- the Google TV over adb ----

class AdbShell:
    """One long-lived `adb -s <serial> shell`, written one command line at a time (thread-safe)."""

    def __init__(self, adb: Path, serial: str, *, popen: Callable[..., Any] = subprocess.Popen,
                 run: Callable[..., Any] = subprocess.run, clock: Callable[[], float] = time.monotonic,
                 settle: float = 0.3):
        if not SERIAL_RE.match(serial):
            raise ValueError("Serial must look like 192.168.68.71:5555")
        self.serial = serial
        self._adb, self._popen, self._run, self._clock, self._settle = str(adb), popen, run, clock, settle
        self._proc = None
        self._failed_at: float | None = None
        self._lock = threading.Lock()

    @property
    def offline_message(self) -> str:
        return f"Can't reach the TV at {self.serial}. Is it on and on the home Wi-Fi? Try again in a few seconds."

    def _start(self) -> None:
        try:  # harmless if already connected; bounded, and its output is never shown
            self._run([self._adb, "connect", self.serial], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                      stderr=subprocess.DEVNULL, timeout=3, creationflags=NO_WINDOW)
        except subprocess.TimeoutExpired:
            pass
        proc = self._popen([self._adb, "-s", self.serial, "shell"], stdin=subprocess.PIPE,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
        if self._settle:
            time.sleep(self._settle)
        if proc.poll() is not None:
            raise OSError("adb shell ended at once")
        self._proc = proc

    def connect(self) -> bool:
        with self._lock:
            try:
                if self._proc is None or self._proc.poll() is not None:
                    self._start()
                return True
            except OSError:
                self._proc, self._failed_at = None, self._clock()
                return False

    def send(self, line: str) -> tuple[bool, str]:
        """Write one line. A write reaches the local adb; it does not prove the TV acted, so success says "Sent"."""
        with self._lock:
            try:
                if self._proc is None or self._proc.poll() is not None:
                    if self._failed_at is not None and self._clock() - self._failed_at < RETRY_AFTER:
                        return False, self.offline_message
                    self._start()
                self._proc.stdin.write((line + "\n").encode("ascii"))
                self._proc.stdin.flush()
                self._failed_at = None
                return True, f"Sent to {self.serial}"
            except (OSError, ValueError):
                self._proc, self._failed_at = None, self._clock()
                return False, self.offline_message

    def packages(self) -> set[str] | None:
        """Which packages the TV has (bounded at 5 s); None when unknown."""
        try:
            res = self._run([self._adb, "-s", self.serial, "shell", "pm list packages"], stdin=subprocess.DEVNULL,
                            capture_output=True, text=True, timeout=5, creationflags=NO_WINDOW)
            return parse_packages(res.stdout or "")
        except (OSError, subprocess.SubprocessError, UnicodeDecodeError):
            return None

    def close(self) -> None:
        with self._lock:
            proc, self._proc = self._proc, None
        if proc is None:
            return
        try:
            proc.stdin.write(b"exit\n")
            proc.stdin.flush()
            proc.wait(timeout=1)
        except Exception:  # noqa: BLE001  closing must never raise
            pass
        try:
            if proc.poll() is None:
                proc.kill()
        except Exception:  # noqa: BLE001
            pass


# ---- the LG, for volume ----

class LgVolume:
    """Runs lgpipe.handle on one private event loop thread, so the LG link is reused between presses."""

    def __init__(self, lg: Any, *, timeout: float = LG_TIMEOUT, clock: Callable[[], float] = time.monotonic):
        from .lgpipe import handle

        self._lg, self._handle, self._timeout, self._clock = lg, handle, timeout, clock
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._loop.run_forever, name="lg-volume", daemon=True)
        self._thread.start()
        self._pending: set[Any] = set()
        self._failed_at: float | None = None
        self._closed = False
        self._lock = threading.Lock()

    def __call__(self, cmd: str) -> str:
        """lgpipe's reply: ``ok <level|muted|unmuted|->`` or ``error <reason>``; ``busy`` when presses pile up."""
        with self._lock:
            if self._closed:
                return "error the remote is closing"
            if self._failed_at is not None and self._clock() - self._failed_at < RETRY_AFTER:
                return "error the LG did not answer"
            if len(self._pending) >= LG_MAX_PENDING:
                return "busy"
            fut = asyncio.run_coroutine_threadsafe(self._handle(self._lg, cmd), self._loop)
            self._pending.add(fut)
        try:
            reply = fut.result(timeout=self._timeout)
        except FutureTimeout:
            fut.cancel()
            reply = "error the LG did not answer"
        except CancelledError:
            reply = "error the remote is closing"
        except Exception as exc:  # noqa: BLE001  handle() already turns errors into text; this is a backstop
            reply = "error " + type(exc).__name__
        with self._lock:
            self._pending.discard(fut)
            if not self._closed:
                self._failed_at = self._clock() if reply == "error the LG did not answer" else None
        return reply

    def close(self, wait: float = 1.5) -> None:
        """Shutdown (shutdown-linger): refuse new presses, cancel waiting ones at once, close the LG's websocket and
        HTTP session (lg-session-leak), then stop the loop. Bounded by about 2 x ``wait`` seconds."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
            pending, self._pending = list(self._pending), set()
        for fut in pending:
            fut.cancel()
        closer = getattr(self._lg, "close", None)
        if closer is not None:
            from .lgpipe import lock_for

            async def close_after_current():
                # A command already running finishes its clamp first; one stuck on a TV that does not answer is
                # given up on after `wait` (it has nothing to clamp: the TV is not acting on it either).
                lock = lock_for(self._lg)
                try:
                    await asyncio.wait_for(lock.acquire(), wait)
                except asyncio.TimeoutError:
                    pass
                await closer()

            try:
                asyncio.run_coroutine_threadsafe(close_after_current(), self._loop).result(timeout=wait * 2)
            except Exception:  # noqa: BLE001  a TV that does not answer must not hold the exit
                pass
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(timeout=wait)


def load_lg() -> LgVolume | None:
    """The paired LG from %APPDATA%\\tv-remote, constructed as lgpipe.main does; None when it is not set up."""
    try:
        from .config import config_dir, load_config
        from .lg import LgRemote, LgVault
        from .secretstore import default_protector

        config = load_config()
        if not config.lg:
            return None
        return LgVolume(LgRemote(config.lg.host, config.lg.mac, LgVault(config_dir(), default_protector())))
    except Exception:  # noqa: BLE001  no LG means adb volume, never a crash or a message with the config details
        return None


# ---- the bridge the page calls (window.pywebview.api.*) ----

def _reply(ok: bool, status: str, **extra: Any) -> dict[str, Any]:
    return {"ok": ok, "status": status, **extra}


def _guard(fn: Callable[..., dict[str, Any]], want: int, backends: tuple[Any, ...], args: tuple[Any, ...]) -> dict[str, Any]:
    """Calls fn(*backends, *args) when the page passed exactly `want` arguments. No exception text (which could name
    a host or a file) ever reaches the page: pywebview would send it with a traceback (bridge-arity-traceback)."""
    if len(args) != want:
        return _reply(False, "That did not work (wrong call).")
    try:
        return fn(*backends, *args)
    except Exception as exc:  # noqa: BLE001
        return _reply(False, "Something went wrong (" + type(exc).__name__ + "). Try again.")


def _start(tv: AdbShell, lg: Any) -> dict[str, Any]:
    apps = [{"name": n, "pkg": p, "installed": None} for n, p in APPS]
    if not tv.connect():
        return _reply(False, tv.offline_message, apps=apps, lg=lg is not None, serial=tv.serial)
    have = tv.packages()
    status = f"Ready: {tv.serial}."
    if have is not None:
        for a in apps:
            a["installed"] = a["pkg"] in have
        status += " Dimmed apps are not on the TV."
    return _reply(True, status, apps=apps, lg=lg is not None, serial=tv.serial)


def _key(tv: AdbShell, code: object) -> dict[str, Any]:
    line = key_line(code)
    if line is None:
        return _reply(False, "That button is not on this remote.")
    return _reply(*tv.send(line))


def _app(tv: AdbShell, pkg: object) -> dict[str, Any]:
    line = app_line(pkg)
    if line is None:
        return _reply(False, "That app is not on this remote.")
    return _reply(*tv.send(line))


def _text(tv: AdbShell, text: object) -> dict[str, Any]:
    if isinstance(text, str) and not text.strip():
        return _reply(False, "Type something first.")
    line = text_line(text)
    if line is None:
        return _reply(False, TEXT_HELP)
    return _reply(*tv.send(line))


def _volume(tv: AdbShell, lg: Callable[[str], str] | None, cmd: object) -> dict[str, Any]:
    if not isinstance(cmd, str) or cmd not in VOLUME_KEYS:
        return _reply(False, "That button is not on this remote.")
    if lg is None:
        if cmd == "up":  # adb volume has no cap (adb-volume-uncapped), so up goes to the LG or nowhere
            return _reply(False, "Volume up needs the LG, and it is not set up.", via="none")
        ok, status = tv.send(volume_key_line(cmd))
        return _reply(ok, "Volume sent to the Google TV (the LG is not set up)." if ok else status, via="googletv")
    reply = lg(cmd)
    if reply == "busy":
        return _reply(False, "Still changing the volume...", via="lg")
    if reply.startswith("ok "):
        value = reply[3:].strip()
        if value in ("muted", "unmuted"):
            return _reply(True, "Muted" if value == "muted" else "Sound on", via="lg", muted=value == "muted")
        if value.isdigit():
            limit = " (the most this remote allows)" if int(value) >= VOLUME_CAP else ""
            return _reply(True, f"Volume {value}{limit}", via="lg", level=int(value))
        return _reply(True, "Volume changed", via="lg")
    reason = reply[6:] if reply.startswith("error ") else ""
    if reason == "the LG did not report its volume":
        # lgpipe refused to raise an unknown level (volume-step-cap); the adb key must not get round that.
        return _reply(False, "The LG did not report its volume, so it was not raised.", via="lg")
    reason = reason if LG_REASON_RE.match(reason) else "did not work"
    lg_said = "The LG" + reason[6:] if reason.startswith("the LG") else "The LG: " + reason
    if cmd == "up":
        return _reply(False, f"{lg_said}. Volume up was not sent.", via="none")
    ok, status = tv.send(volume_key_line(cmd))
    if ok:
        return _reply(True, f"{lg_said}. Sent to the Google TV instead.", via="googletv")
    return _reply(False, f"{lg_said}. {status}", via="none")


class _Sealed:
    """Passed as pywebview's ``js_api``: an object with no attributes at all, not even ``__class__``.

    pywebview 6.2.1 (util.js_bridge_call) resolves a call by exact name in the exposed-function table, and otherwise
    walks the dotted name from ``js_api`` with ``getattr(obj, part, None)``, private and dunder names included. A
    real js_api object (or None) would let page script walk to ``_tv._popen`` or ``key.__globals__``. Every lookup
    on this one raises AttributeError, which that getattr turns into None, so the walk ends at the first step.
    """

    __slots__ = ()

    def __getattribute__(self, name: str) -> Any:
        raise AttributeError(name)

    def __dir__(self) -> list[str]:
        return []


SEALED = _Sealed()


def make_bridge(tv: AdbShell, lg: Callable[[str], str] | None) -> tuple[Callable[..., dict[str, Any]], ...]:
    """The five functions the page may call, registered with ``window.expose`` (finding jsapi-private-dispatch).

    The backends live only in these closures. The page reaches a function only by its exact name in the exposed
    table; a dotted name such as ``key.__globals__`` is not in the table and falls through to the walk from
    SEALED, which finds nothing.
    """

    def start(*args):
        return _guard(_start, 0, (tv, lg), args)

    def key(*args):
        return _guard(_key, 1, (tv,), args)

    def app(*args):
        return _guard(_app, 1, (tv,), args)

    def text(*args):
        return _guard(_text, 1, (tv,), args)

    def volume(*args):
        return _guard(_volume, 1, (tv, lg), args)

    return start, key, app, text, volume


# ---- the window ----

def ui_html() -> str:
    from importlib.resources import files

    return files("tv_remote").joinpath("pcremote_ui", "remote.html").read_text(encoding="utf-8")


def icon_path() -> str:
    return str(Path(__file__).with_name("pcremote_ui") / "remote.ico")


def window_size(work_w: int | None, work_h: int | None) -> tuple[int, int]:
    """Outer window size in logical pixels: tall when the screen allows, otherwise wide and short. The page scales
    itself to whatever size the window ends up, so these only need to be sensible."""
    if not work_w or not work_h:
        return 420, 900
    if work_h >= 900:
        return 420, min(980, work_h - 24)
    return min(820, max(560, work_w - 40)), max(420, min(600, work_h - 16))


def _work_area(webview: Any) -> tuple[int | None, int | None]:
    """The primary screen's work area in logical pixels, from pywebview (dpi-mode: this process never changes its own
    DPI awareness; pywebview does that when the window starts, and sizes the window from logical pixels itself).
    Asked before webview.start, so Windows reports logical sizes. (None, None) when unknown."""
    try:
        screen = webview.screens[0]
        frame = getattr(screen, "frame", None)
        width = int(getattr(frame, "Width", 0) or screen.width)
        height = int(getattr(frame, "Height", 0) or screen.height - 48)  # 48: a typical taskbar
        return (width, height) if width > 0 and height > 0 else (None, None)
    except Exception:  # noqa: BLE001
        return None, None


def configure_webview(webview: Any) -> None:
    """Before start (newwindow-startfile): a link that asks for a new window must never reach webbrowser.open, which
    on Windows ends in os.startfile; with this off, pywebview loads it in this window instead, and _lock_down cancels
    that navigation."""
    webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"] = False


def _lock_down(window: Any) -> None:
    """Windows: nothing dragged onto the window can navigate it, and once the page has loaded no navigation away from
    it is allowed (jsapi-private-dispatch: another page in this window would get the bridge too)."""
    if sys.platform != "win32" or getattr(window, "_tv_locked", False):
        return
    window._tv_locked = True
    try:
        from System import Action

        form = window.native

        def deny(_sender, args):
            args.Cancel = True

        def no_new_window(_sender, args):
            args.Handled = True  # no popup; pywebview's own handler only loads it here, which `deny` cancels

        def apply():
            form.webview.AllowExternalDrop = False
            core = form.webview.CoreWebView2
            core.NavigationStarting += deny
            core.NewWindowRequested += no_new_window

        form.Invoke(Action(apply))
    except Exception as exc:  # noqa: BLE001  the page's own drop handlers still stop drops
        log.warning("could not lock the window down (drops and navigation): %s", type(exc).__name__)


def _single_instance():
    """A handle to the named mutex, or None when another remote already holds it. Always a handle off Windows."""
    if sys.platform != "win32":
        return True
    import ctypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    # Owned from the start (mutex-one-way): the classic remote's WaitOne(0) then fails and it exits.
    handle = kernel32.CreateMutexW(None, True, MUTEX_NAME)
    if handle and ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS: not ours, so not owned
        kernel32.CloseHandle(ctypes.c_void_p(handle))
        return None
    return handle or True


def _release(mutex: Any) -> None:
    if sys.platform != "win32" or mutex is True:
        return
    try:
        import ctypes

        kernel32 = ctypes.WinDLL("kernel32")
        kernel32.ReleaseMutex(ctypes.c_void_p(mutex))
        kernel32.CloseHandle(ctypes.c_void_p(mutex))
    except Exception:  # noqa: BLE001
        pass


def _log_to_file() -> None:
    """There is no console, so this module's own warnings (only exception type names, never hosts or keys) go to
    %LOCALAPPDATA%\\tv-remote\\remote-app.log, kept under 64 KB. Other libraries' logs are not written there."""
    try:
        from logging.handlers import RotatingFileHandler

        folder = Path(os.environ["LOCALAPPDATA"]) / "tv-remote"
        folder.mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(folder / "remote-app.log", maxBytes=64 * 1024, backupCount=1, encoding="utf-8")
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        log.addHandler(handler)
        log.setLevel(logging.INFO)
    except Exception:  # noqa: BLE001  no log file is not a reason to stop
        pass


def _show_error(message: str) -> None:
    if sys.platform == "win32":
        try:
            import ctypes

            ctypes.windll.user32.MessageBoxW(None, message, "TV Remote", 0x10)
            return
        except Exception:  # noqa: BLE001
            pass
    print(message, file=sys.stderr)


def main(argv: list[str] | None = None) -> None:
    import argparse

    parser = argparse.ArgumentParser(prog="tv-remote-app", description="On-screen remote for the Google TV")
    parser.add_argument("--serial", default=DEFAULT_SERIAL, help="the Google TV's adb address (ip:port)")
    args = parser.parse_args(argv)
    try:
        if not SERIAL_RE.match(args.serial):
            raise ValueError("Serial must look like 192.168.68.71:5555")
        mutex = _single_instance()
        if mutex is None:
            return
        base = Path(os.environ.get("LOCALAPPDATA", "")) / "tv-home-tools"
        adb = find_adb(base) if os.environ.get("LOCALAPPDATA") else None
        if adb is None:
            raise FileNotFoundError(f"adb.exe not found under {base} (the tv-home install puts it there).")
        import webview

        _log_to_file()
        if sys.platform == "win32":
            try:  # its own taskbar button and icon, not Python's
                import ctypes

                ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("gnosh.tv-remote")
            except Exception:  # noqa: BLE001
                pass
        configure_webview(webview)
        tv = AdbShell(adb, args.serial)
        lg = load_lg()
        width, height = window_size(*_work_area(webview))
        window = webview.create_window("TV Remote", html=ui_html(), js_api=SEALED,
                                       width=width, height=height, min_size=(340, 400),
                                       background_color="#0B0D12", text_select=False)
        window.expose(*make_bridge(tv, lg))
        window.events.loaded += lambda: _lock_down(window)
        try:
            webview.start(private_mode=True, icon=icon_path())
        finally:
            tv.close()
            if lg is not None:
                lg.close()
            _release(mutex)
    except Exception as exc:  # noqa: BLE001  started from an icon there is no console, so show it in a box
        _show_error(f"TV remote could not start: {exc}")
        sys.exit(1)


if __name__ == "__main__":
    main()
