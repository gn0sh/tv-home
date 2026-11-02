"""The PC on-screen remote (ADR 0006): a pywebview window whose page talks to this module only through pywebview's
``js_api`` bridge. No HTTP server, no listening port.

Google TV keys, app launches and typed text go over one long-lived ``adb -s <serial> shell``, exactly as the classic
PowerShell remote (scripts/tv-remote-pc.ps1) does. Volume and mute go to the paired LG directly (lgpipe.handle, cap 60,
ADR 0003), because the LG ignores HDMI-CEC volume from the Google TV; if the LG is not set up or does not answer, the
adb VOLUME key is sent instead and the status line says so.

Everything the page sends is checked here against fixed allowlists before anything reaches a device. Status strings
never carry the LG's address, client key, certificate or any exception text.
"""

from __future__ import annotations

import asyncio
import functools
import os
import re
import subprocess
import sys
import threading
import time
from collections.abc import Callable
from concurrent.futures import TimeoutError as FutureTimeout
from pathlib import Path
from typing import Any

from .lgpipe import VOLUME_CAP

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
        self._pending = 0
        self._failed_at: float | None = None
        self._lock = threading.Lock()

    def __call__(self, cmd: str) -> str:
        """lgpipe's reply: ``ok <level|muted|unmuted|->`` or ``error <reason>``; ``busy`` when presses pile up."""
        with self._lock:
            if self._failed_at is not None and self._clock() - self._failed_at < RETRY_AFTER:
                return "error the LG did not answer"
            if self._pending >= LG_MAX_PENDING:
                return "busy"
            self._pending += 1
        fut = asyncio.run_coroutine_threadsafe(self._handle(self._lg, cmd), self._loop)
        try:
            reply = fut.result(timeout=self._timeout)
        except FutureTimeout:
            fut.cancel()
            reply = "error the LG did not answer"
        except Exception as exc:  # noqa: BLE001  handle() already turns errors into text; this is a backstop
            reply = "error " + type(exc).__name__
        with self._lock:
            self._pending -= 1
            self._failed_at = self._clock() if reply == "error the LG did not answer" else None
        return reply

    def close(self) -> None:
        self._loop.call_soon_threadsafe(self._loop.stop)


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


def _safe(method):
    """No exception text (which could name a host or a file) ever reaches the page: pywebview would send it."""

    @functools.wraps(method)
    def wrapper(self, *args):
        try:
            return method(self, *args)
        except Exception as exc:  # noqa: BLE001
            return _reply(False, "Something went wrong (" + type(exc).__name__ + "). Try again.")

    return wrapper


class RemoteApi:
    """Public methods are exposed to JavaScript; everything else is private (pywebview skips names starting with _)."""

    def __init__(self, tv: AdbShell, lg: Callable[[str], str] | None):
        self._tv, self._lg = tv, lg

    @_safe
    def start(self) -> dict[str, Any]:
        apps = [{"name": n, "pkg": p, "installed": None} for n, p in APPS]
        if not self._tv.connect():
            return _reply(False, self._tv.offline_message, apps=apps, lg=self._lg is not None, serial=self._tv.serial)
        have = self._tv.packages()
        status = f"Ready: {self._tv.serial}."
        if have is not None:
            for a in apps:
                a["installed"] = a["pkg"] in have
            status += " Dimmed apps are not on the TV."
        return _reply(True, status, apps=apps, lg=self._lg is not None, serial=self._tv.serial)

    @_safe
    def key(self, code: object) -> dict[str, Any]:
        line = key_line(code)
        if line is None:
            return _reply(False, "That button is not on this remote.")
        return _reply(*self._tv.send(line))

    @_safe
    def app(self, pkg: object) -> dict[str, Any]:
        line = app_line(pkg)
        if line is None:
            return _reply(False, "That app is not on this remote.")
        return _reply(*self._tv.send(line))

    @_safe
    def text(self, text: object) -> dict[str, Any]:
        if isinstance(text, str) and not text.strip():
            return _reply(False, "Type something first.")
        line = text_line(text)
        if line is None:
            return _reply(False, TEXT_HELP)
        return _reply(*self._tv.send(line))

    @_safe
    def volume(self, cmd: object) -> dict[str, Any]:
        if not isinstance(cmd, str) or cmd not in VOLUME_KEYS:
            return _reply(False, "That button is not on this remote.")
        if self._lg is None:
            ok, status = self._tv.send(volume_key_line(cmd))
            return _reply(ok, "Volume sent to the Google TV (the LG is not set up)." if ok else status, via="googletv")
        reply = self._lg(cmd)
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
        ok, status = self._tv.send(volume_key_line(cmd))
        if ok:
            return _reply(True, f"{lg_said}. Sent to the Google TV instead.", via="googletv")
        return _reply(False, f"{lg_said}. {status}", via="none")


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


def _work_area() -> tuple[int | None, int | None]:
    """The primary screen's work area in logical (96 dpi) pixels, on Windows; (None, None) elsewhere."""
    if sys.platform != "win32":
        return None, None
    try:
        import ctypes
        from ctypes import wintypes

        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)  # pywebview does the same; a second call is a no-op
        except Exception:  # noqa: BLE001
            pass
        rect = wintypes.RECT()
        ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(rect), 0)  # SPI_GETWORKAREA
        scale = ctypes.windll.user32.GetDpiForSystem() / 96 or 1
        return int((rect.right - rect.left) / scale), int((rect.bottom - rect.top) / scale)
    except Exception:  # noqa: BLE001
        return None, None


def _single_instance():
    """A handle to the named mutex, or None when another remote already holds it. Always a handle off Windows."""
    if sys.platform != "win32":
        return True
    import ctypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    handle = kernel32.CreateMutexW(None, False, MUTEX_NAME)
    if handle and ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS
        return None
    return handle or True


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

        if sys.platform == "win32":
            try:  # its own taskbar button and icon, not Python's
                import ctypes

                ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("gnosh.tv-remote")
            except Exception:  # noqa: BLE001
                pass
        tv = AdbShell(adb, args.serial)
        lg = load_lg()
        api = RemoteApi(tv, lg)
        width, height = window_size(*_work_area())
        webview.create_window("TV Remote", html=ui_html(), js_api=api, width=width, height=height,
                              min_size=(340, 400), background_color="#0B0D12", text_select=False)
        try:
            webview.start(private_mode=True, icon=icon_path())
        finally:
            tv.close()
            if lg is not None:
                lg.close()
        del mutex
    except Exception as exc:  # noqa: BLE001  started from an icon there is no console, so show it in a box
        _show_error(f"TV remote could not start: {exc}")
        sys.exit(1)


if __name__ == "__main__":
    main()
