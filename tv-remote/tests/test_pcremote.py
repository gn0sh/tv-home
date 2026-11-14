"""The PC on-screen remote's bridge (pcremote.py), against fake adb processes and a fake LG. No device is touched."""

import inspect
import json
import re
import subprocess
from pathlib import Path

import pytest

from tv_remote import pcremote
from tv_remote.devices import DeviceUnreachable, LgStatus, NotPaired
from tv_remote.pcremote import SEALED, AdbShell, LgVolume, TvChoice, make_bridge

SERIAL = "192.168.68.71:5555"
LG_HOST = "192.168.68.61"


class RemoteApi:
    """Test adapter: the five bridge functions as attributes, as the page sees them."""

    def __init__(self, tv, lg, choice=None, vz=None):
        for f in make_bridge(tv, lg, choice, vz):
            setattr(self, f.__name__, f)


class FakeStdin:
    def __init__(self, broken=False):
        self.data, self.broken = b"", broken

    def write(self, b):
        if self.broken:
            raise OSError("pipe closed")
        self.data += b

    def flush(self):
        pass


class FakeProc:
    def __init__(self, exited=False, broken=False):
        self.stdin = FakeStdin(broken)
        self.exited, self.killed = exited, False

    def poll(self):
        return 1 if self.exited else None

    def wait(self, timeout=None):
        self.exited = True

    def kill(self):
        self.killed = True

    @property
    def lines(self):
        return self.stdin.data.decode().splitlines()


class FakeAdb:
    """Stands in for subprocess.Popen and subprocess.run."""

    def __init__(self, packages="package:com.netflix.ninja\npackage:com.gnosh.tvhome\n", fail=False):
        self.procs, self.runs, self.packages_out, self.fail = [], [], packages, fail

    def popen(self, args, **kw):
        self.popen_args = args
        if self.fail:
            raise OSError("adb not reachable")
        p = FakeProc()
        self.procs.append(p)
        return p

    def run(self, args, **kw):
        self.runs.append(args)
        return subprocess.CompletedProcess(args, 0, stdout=self.packages_out if "pm list packages" in args else "")

    @property
    def lines(self):
        return [line for p in self.procs for line in p.lines]


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def shell(fake=None, clock=None):
    fake = fake or FakeAdb()
    return AdbShell("adb", SERIAL, popen=fake.popen, run=fake.run, clock=clock or Clock(), settle=0), fake


class FakeLg:
    def __init__(self, volume=10, muted=False, fail=None):
        self.volume, self.muted, self.fail, self.calls = volume, muted, fail, []

    async def status(self):
        if self.fail:
            raise self.fail
        return LgStatus("on", self.volume, self.muted, None, None)

    async def volume_step(self, direction):
        if self.fail:
            raise self.fail
        self.calls.append(direction)
        self.volume += 1 if direction == "up" else -1
        return self.volume

    async def set_volume(self, level):
        self.calls.append(("set", level))
        self.volume = level
        return level

    async def set_mute(self, muted):
        self.calls.append(("mute", muted))
        self.muted = muted


# ---- allowlists and command lines ----

def test_every_key_maps_to_one_keyevent_line():
    for code in pcremote.KEYS:
        assert pcremote.key_line(code) == f"input keyevent KEYCODE_{code}"


@pytest.mark.parametrize("bad", ["VOLUME_UP", "HOME; reboot", "home", "", "KEYCODE_HOME", None, 3, ["HOME"],
                                 "DPAD_UP\ninput keyevent POWER"])
def test_keys_outside_the_allowlist_send_nothing(bad):
    sh, fake = shell()
    api = RemoteApi(sh, None)
    r = api.key(bad)
    assert r["ok"] is False
    assert fake.procs == [] and fake.runs == []


def test_same_twelve_apps_as_the_classic_remote():
    ps1 = (Path(__file__).resolve().parents[2] / "scripts" / "tv-remote-pc.ps1").read_text(encoding="utf-8")
    classic = re.findall(r"^\s+'([^']+)'\s+=\s+'([a-z0-9_.]+)'$", ps1, re.M)
    assert list(pcremote.APPS) == classic and len(classic) == 12


def test_app_launch_line_and_unknown_package():
    assert pcremote.app_line("com.netflix.ninja") == (
        "monkey -p com.netflix.ninja -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1")
    sh, fake = shell()
    api = RemoteApi(sh, None)
    assert api.app("com.evil.app")["ok"] is False
    assert api.app("com.netflix.ninja; rm -rf /")["ok"] is False
    assert fake.lines == []
    assert api.app("com.netflix.ninja")["ok"] is True
    assert fake.lines == [pcremote.app_line("com.netflix.ninja")]


@pytest.mark.parametrize("text,line", [
    ("stranger things", "input text 'stranger%sthings'"),
    ("  bob@x.com ", "input text 'bob@x.com'"),
    ("a-b_c.d,e", "input text 'a-b_c.d,e'"),
])
def test_text_lines(text, line):
    assert pcremote.text_line(text) == line


@pytest.mark.parametrize("bad", ["-rf", "it's", "a;b", "$(reboot)", "a`b`", "x" * 101, "café", "a\nb", 5])
def test_text_refused(bad):
    assert pcremote.text_line(bad) is None
    sh, fake = shell()
    r = RemoteApi(sh, None).text(bad)
    assert r["ok"] is False and fake.lines == []


def test_empty_text_says_type_something():
    sh, fake = shell()
    assert RemoteApi(sh, None).text("   ")["status"] == "Type something first."


def test_serial_must_be_ip_port():
    with pytest.raises(ValueError):
        AdbShell("adb", "tv.local:5555")
    with pytest.raises(ValueError):
        AdbShell("adb", "192.168.68.71:5555 && calc")


def test_the_bridge_is_seven_named_functions_and_a_sealed_api_object():
    names = [f.__name__ for f in make_bridge(shell()[0], None)]
    assert names == ["start", "key", "app", "text", "volume", "set_tv", "tv_input"]
    assert dir(SEALED) == []
    for attr in ("__class__", "__dict__", "_tv", "start", "__init__"):
        assert getattr(SEALED, attr, None) is None


class BridgeWindow:
    """The parts of a pywebview Window that webview.util.js_bridge_call uses."""

    def __init__(self, functions, js_api):
        import threading

        self._functions = {f.__name__: f for f in functions}
        self._js_api = js_api
        self.results, self.answered = [], threading.Event()

    def evaluate_js(self, script):
        self.results.append(script)
        self.answered.set()


ATTACK_PATHS = [
    ("_tv.send", ["rm -rf /sdcard; reboot"]),
    ("_tv._popen", [["calc.exe"]]),
    ("_tv._run", [["calc.exe"]]),
    ("_tv._start", []),
    ("_lg", ["up"]),
    ("_lg._handle", [None, "up"]),
    ("__class__.__init__", [None, None, None]),
    ("__init__", [None, None]),
    ("key.__globals__.update", [{"KEYS": ["HOME; reboot"]}]),
    ("key.__closure__", []),
    ("start.__globals__.clear", []),
    ("volume.__wrapped__", ["up"]),
    ("__getattribute__", ["_tv"]),
    ("__setattr__", ["_tv", None]),
    ("__class__.__subclasses__", []),
    ("constructor", []),
]


def test_page_script_cannot_reach_past_the_five_functions():
    """Drives pywebview's real dispatcher (jsapi-private-dispatch): private, dunder and dotted names reach nothing."""
    import time

    from webview.util import js_bridge_call

    lg = FakeLg(10)
    lg_calls = []
    sh, fake = shell()
    window = BridgeWindow(make_bridge(sh, lambda cmd: lg_calls.append(cmd) or "ok 11"), SEALED)
    keys_before = pcremote.KEYS
    for i, (name, params) in enumerate(ATTACK_PATHS):
        js_bridge_call(window, name, params, str(i))
    time.sleep(0.3)  # any call that did resolve runs on its own thread
    assert window.results == []
    assert fake.procs == [] and fake.runs == [] and lg_calls == [] and lg.calls == []
    assert pcremote.KEYS is keys_before and "HOME; reboot" not in pcremote.KEYS
    # control: the real names do work through the same dispatcher
    js_bridge_call(window, "key", ["HOME"], "ok")
    assert window.answered.wait(5)
    assert fake.lines == ["input keyevent KEYCODE_HOME"]
    js_bridge_call(window, "volume", ["up"], "ok2")
    for _ in range(50):
        if lg_calls:
            break
        time.sleep(0.05)
    assert lg_calls == ["up"]


def test_a_plain_js_api_object_would_be_reachable():
    """Why SEALED exists: with an ordinary object, pywebview 6.2.1 walks straight to private attributes."""
    from webview.util import js_bridge_call

    hit = []

    class Plain:
        def __init__(self):
            self._secret = type("S", (), {"send": staticmethod(lambda x: hit.append(x))})()

    window = BridgeWindow([], Plain())
    js_bridge_call(window, "_secret.send", ["boom"], "1")
    assert window.answered.wait(5) and hit == ["boom"]


# ---- adb shell ----

def test_one_long_lived_shell_for_many_presses():
    sh, fake = shell()
    api = RemoteApi(sh, None)
    api.key("HOME")
    api.key("DPAD_UP")
    assert len(fake.procs) == 1
    assert fake.popen_args == ["adb", "-s", SERIAL, "shell"]
    assert fake.runs[0] == ["adb", "connect", SERIAL]
    assert fake.lines == ["input keyevent KEYCODE_HOME", "input keyevent KEYCODE_DPAD_UP"]
    assert api.key("HOME")["status"] == f"Sent to {SERIAL}"


def test_shell_restarts_when_it_ended():
    sh, fake = shell()
    sh.send("input keyevent KEYCODE_HOME")
    fake.procs[0].exited = True
    sh.send("input keyevent KEYCODE_BACK")
    assert len(fake.procs) == 2 and fake.procs[1].lines == ["input keyevent KEYCODE_BACK"]


def test_offline_tv_drops_presses_for_ten_seconds_then_retries():
    clock = Clock()
    sh, fake = shell(FakeAdb(fail=True), clock)
    ok, status = sh.send("input keyevent KEYCODE_HOME")
    assert not ok and "Can't reach the TV" in status
    runs = len(fake.runs)
    clock.t += 5
    assert not sh.send("input keyevent KEYCODE_HOME")[0]
    assert len(fake.runs) == runs  # no reconnect attempt inside the 10 s
    clock.t += 6
    fake.fail = False
    assert sh.send("input keyevent KEYCODE_HOME")[0]


def test_start_greys_out_missing_apps():
    sh, fake = shell()
    r = RemoteApi(sh, None).start()
    assert r["ok"] and r["lg"] is False
    installed = {a["name"]: a["installed"] for a in r["apps"]}
    assert installed["Netflix"] is True and installed["Dashboard"] is True and installed["Hulu"] is False
    assert ["adb", "-s", SERIAL, "shell", "pm list packages"] in fake.runs


def test_start_with_unknown_packages_leaves_all_apps_on():
    sh, fake = shell(FakeAdb(packages="error: closed"))
    r = RemoteApi(sh, None).start()
    assert {a["installed"] for a in r["apps"]} == {None}


def test_start_offline():
    sh, fake = shell(FakeAdb(fail=True))
    r = RemoteApi(sh, object()).start()
    assert r["ok"] is False and "Can't reach the TV" in r["status"] and len(r["apps"]) == 12


def test_close_exits_the_shell():
    sh, fake = shell()
    sh.send("input keyevent KEYCODE_HOME")
    sh.close()
    assert fake.procs[0].lines[-1] == "exit"


def test_find_adb(tmp_path, monkeypatch):
    monkeypatch.setattr(pcremote.sys, "platform", "win32")
    assert pcremote.find_adb(tmp_path) is None
    (tmp_path / "platform-tools").mkdir()
    (tmp_path / "platform-tools" / "adb.exe").write_bytes(b"")
    assert pcremote.find_adb(tmp_path) == tmp_path / "platform-tools" / "adb.exe"


# ---- volume: LG first, adb fallback ----

def lg_api(lg, fake=None):
    sh, fake = shell(fake)
    vol = LgVolume(lg, timeout=5)
    return RemoteApi(sh, vol), fake, vol


def test_volume_goes_to_the_lg_and_not_to_adb():
    lg = FakeLg(10)
    api, fake, vol = lg_api(lg)
    try:
        r = api.volume("up")
        assert r == {"ok": True, "status": "Volume 11", "via": "lg", "level": 11, "tv": "lg"}
        assert api.volume("down")["level"] == 10
        assert lg.calls == ["up", "down"] and fake.lines == []
    finally:
        vol.close()


def test_volume_cap_60_still_applies():
    lg = FakeLg(60)
    api, fake, vol = lg_api(lg)
    try:
        assert api.volume("up")["level"] == 60
        assert lg.calls == []  # lgpipe checks the cap before stepping
    finally:
        vol.close()


def test_mute_toggles_on_the_lg():
    lg = FakeLg(muted=False)
    api, fake, vol = lg_api(lg)
    try:
        assert api.volume("mute") == {"ok": True, "status": "Muted", "via": "lg", "muted": True, "tv": "lg"}
        assert api.volume("mute")["muted"] is False
    finally:
        vol.close()


@pytest.mark.parametrize("cmd,key", [("down", "VOLUME_DOWN"), ("mute", "VOLUME_MUTE")])
def test_unreachable_lg_falls_back_to_adb(cmd, key):
    lg = FakeLg(fail=DeviceUnreachable("lg", LG_HOST))
    api, fake, vol = lg_api(lg)
    try:
        r = api.volume(cmd)
        assert r["ok"] and r["via"] == "googletv"
        assert r["status"] == "The LG did not answer. Sent to the Google TV instead."
        assert fake.lines == [f"input keyevent KEYCODE_{key}"]
    finally:
        vol.close()


def test_volume_up_never_falls_back_to_adb():
    """adb volume has no cap (adb-volume-uncapped): up goes to the LG or nowhere."""
    lg = FakeLg(fail=DeviceUnreachable("lg", LG_HOST))
    api, fake, vol = lg_api(lg)
    try:
        r = api.volume("up")
    finally:
        vol.close()
    assert r == {"ok": False, "status": "The LG did not answer. Volume up was not sent.", "via": "none", "tv": "lg"}
    assert fake.lines == []
    sh, fake = shell()
    r = RemoteApi(sh, None).volume("up")
    assert r["ok"] is False and "not set up" in r["status"] and fake.lines == []


def test_unreachable_lg_is_skipped_for_ten_seconds():
    lg = FakeLg(fail=DeviceUnreachable("lg", LG_HOST))
    clock = Clock()
    sh, fake = shell()
    vol = LgVolume(lg, timeout=5, clock=clock)
    try:
        api = RemoteApi(sh, vol)
        api.volume("down")
        lg.fail = None
        api.volume("down")
        assert lg.calls == [] and len(fake.lines) == 2  # straight to adb, no 8 s wait per press
        clock.t += 11
        assert api.volume("down")["via"] == "lg"
    finally:
        vol.close()


def test_lg_not_set_up_uses_adb_for_down_and_mute():
    sh, fake = shell()
    r = RemoteApi(sh, None).volume("down")
    assert r["ok"] and r["via"] == "googletv" and "not set up" in r["status"]
    RemoteApi(sh, None).volume("mute")
    assert fake.lines == ["input keyevent KEYCODE_VOLUME_DOWN", "input keyevent KEYCODE_VOLUME_MUTE"]


def test_volume_refuses_other_commands():
    lg = FakeLg()
    api, fake, vol = lg_api(lg)
    try:
        for bad in ("set 100", "UP", "power", None, 60):
            assert api.volume(bad)["ok"] is False
        assert lg.calls == [] and fake.lines == []
    finally:
        vol.close()


def test_error_text_never_carries_secrets():
    secret = f"client-key=abc123 {LG_HOST} cert sha256:deadbeef"
    for fail in (RuntimeError(secret), DeviceUnreachable("lg", LG_HOST, secret), NotPaired("lg")):
        api, fake, vol = lg_api(FakeLg(fail=fail))
        try:
            r = api.volume("up")
        finally:
            vol.close()
        text = repr(r)
        assert r["status"].startswith("The LG")
        assert LG_HOST not in text and "abc123" not in text and "deadbeef" not in text and "client-key" not in text


def test_unexpected_lg_reply_is_not_shown():
    sh, fake = shell()
    r = RemoteApi(sh, lambda cmd: "error <script>192.168.68.61</script>").volume("down")
    assert r["status"] == "The LG: did not work. Sent to the Google TV instead."


@pytest.mark.parametrize("reply,said", [
    ("error the LG's certificate changed; pair it again", "The LG's certificate changed; pair it again."),
    ("error the LG is not paired", "The LG is not paired."),
    ("error RuntimeError", "The LG: RuntimeError."),
])
def test_lg_reasons_read_well(reply, said):
    sh, fake = shell()
    assert RemoteApi(sh, lambda cmd: reply).volume("down")["status"] == said + " Sent to the Google TV instead."


def test_unexpected_exceptions_never_reach_the_page():
    sh, fake = shell()

    def boom(cmd):
        raise RuntimeError(f"{LG_HOST} secret")

    r = RemoteApi(sh, boom).volume("up")
    assert r == {"ok": False, "status": "Something went wrong (RuntimeError). Try again."}


def test_unknown_lg_level_is_not_raised_through_adb_either():
    lg = FakeLg(None)
    api, fake, vol = lg_api(lg)
    try:
        r = api.volume("up")
    finally:
        vol.close()
    assert r["ok"] is False and "not raised" in r["status"]
    assert fake.lines == [] and lg.calls == []


def test_busy_lg_drops_the_press():
    sh, fake = shell()
    r = RemoteApi(sh, lambda cmd: "busy").volume("up")
    assert r["ok"] is False and fake.lines == []


def test_load_lg_without_config_is_none(tmp_path, monkeypatch):
    monkeypatch.setenv("TV_REMOTE_CONFIG_DIR", str(tmp_path))
    assert pcremote.load_lg() is None
    (tmp_path / "config.toml").write_text('[googletv]\nhost = "192.168.68.71"\n')
    assert pcremote.load_lg() is None


# ---- the page and the window ----

def test_page_is_self_contained_and_matches_the_allowlist():
    html = pcremote.ui_html()
    assert not re.search(r"(src|href)\s*=\s*[\"']?(https?:)?//", html), "the page must fetch nothing"
    assert "fetch(" not in html and "XMLHttpRequest" not in html and "WebSocket" not in html
    page_keys = set(re.findall(r'data-key="([A-Z_]+)"', html))
    assert page_keys == set(pcremote.KEYS)
    for method in ("start", "key", "app", "text", "volume", "set_tv"):
        assert f'"{method}"' in html


def test_icon_ships_with_the_package():
    data = Path(pcremote.icon_path()).read_bytes()
    assert data[:4] == b"\x00\x00\x01\x00"  # ICO header


def test_window_size_tall_or_wide():
    assert pcremote.window_size(1920, 1040) == (420, 980)
    w, h = pcremote.window_size(1366, 728)
    assert w > h and h <= 712
    w, h = pcremote.window_size(1093, 574)  # 1366x768 at 125%
    assert h <= 574 and w <= 1093
    assert pcremote.window_size(None, None) == (420, 900)


def test_work_area_from_pywebview_screens():
    class Frame:
        Width, Height = 1366, 728

    class Screen:
        width, height, frame = 1366, 768, Frame()

    class Wv:
        screens = [Screen()]

    assert pcremote._work_area(Wv()) == (1366, 728)
    Screen.frame = None
    assert pcremote._work_area(Wv()) == (1366, 720)

    class Broken:
        @property
        def screens(self):
            raise RuntimeError("no gui")

    assert pcremote._work_area(Broken()) == (None, None)


def test_close_is_prompt_cancels_waiting_presses_and_closes_the_lg():
    import asyncio
    import threading
    import time

    class SlowLg(FakeLg):
        closed = False

        async def status(self):
            await asyncio.sleep(30)

        async def close(self):
            SlowLg.closed = True

    lg = SlowLg()
    vol = LgVolume(lg, timeout=30)
    out = []
    t = threading.Thread(target=lambda: out.append(vol("up")))
    t.start()
    time.sleep(0.2)
    began = time.monotonic()
    vol.close()
    t.join(5)
    assert time.monotonic() - began < 3
    assert out == ["error the remote is closing"]
    assert SlowLg.closed
    assert vol("up") == "error the remote is closing"
    assert not vol._thread.is_alive()


def test_lg_remote_close_closes_its_link():
    import asyncio

    from tv_remote.lg import LgRemote

    closed = []

    class Link:
        async def close(self):
            closed.append(True)

    remote = LgRemote(LG_HOST, "AA:BB:CC:DD:EE:FF", vault=None)
    remote._link = Link()
    asyncio.run(remote.close())
    assert closed == [True] and remote._link is None
    asyncio.run(remote.close())  # closing twice is fine


def test_wrong_arity_through_the_real_dispatcher_is_a_short_error():
    """bridge-arity-traceback: a call with too few or too many arguments gets a short reply, never a traceback."""
    from webview.util import js_bridge_call

    sh, fake = shell()
    for name, params in (("key", []), ("key", ["HOME", "BACK"]), ("start", ["x"]), ("volume", []),
                         ("text", ["a", "b"]), ("app", [])):
        window = BridgeWindow(make_bridge(sh, None), SEALED)
        js_bridge_call(window, name, params, "1")
        assert window.answered.wait(5)
        out = window.results[0]
        assert "isError" not in out and "Traceback" not in out and "wrong call" in out
    assert fake.lines == [] and fake.runs == []


def test_new_window_links_never_open_the_browser():
    """newwindow-startfile: pywebview must not pass a link from the page to webbrowser.open (os.startfile)."""
    import webview

    before = webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"]
    try:
        pcremote.configure_webview(webview)
        assert webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"] is False
    finally:
        webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"] = before
    src = Path(pcremote.__file__).read_text(encoding="utf-8")
    assert src.index("configure_webview(webview)") < src.index("webview.start(")


def test_lock_down_failure_is_logged(monkeypatch, caplog):
    monkeypatch.setattr(pcremote.sys, "platform", "win32")

    class Window:
        native = None

    with caplog.at_level("WARNING", logger="tv_remote.pcremote"):
        pcremote._lock_down(Window())
    assert "could not lock the window down" in caplog.text


# ---- LG | Vizio toggle (ADR 0006 addendum) ----

def test_tv_choice_defaults_to_lg_and_persists(tmp_path):
    path = tmp_path / "tv-remote" / "remote-app.json"
    assert TvChoice(path).name == "lg"  # missing file
    sh, fake = shell()
    api = RemoteApi(sh, None, TvChoice(path))
    r = api.set_tv("vizio")
    assert r == {"ok": True, "status": "Volume buttons now drive the Vizio.", "tv": "vizio"}
    assert json.loads(path.read_text()) == {"tv": "vizio"}
    assert TvChoice(path).name == "vizio"  # next launch
    assert RemoteApi(sh, None, TvChoice(path)).start()["tv"] == "vizio"
    api.set_tv("lg")
    assert TvChoice(path).name == "lg"


@pytest.mark.parametrize("content", ["", "{not json", '"vizio"', '{"tv": "sony"}', '{"tv": ["vizio"]}', "[1]",
                                     '{"tv": "VIZIO"}', "\x00\xff"])
def test_corrupt_settings_file_means_lg(tmp_path, content):
    path = tmp_path / "remote-app.json"
    path.write_text(content, encoding="utf-8", errors="surrogateescape")
    assert TvChoice(path).name == "lg"


@pytest.mark.parametrize("bad", ["sony", "LG", "Vizio", "", None, 1, ["vizio"], "vizio; rm"])
def test_set_tv_allowlist(tmp_path, bad):
    choice = TvChoice(tmp_path / "remote-app.json")
    r = RemoteApi(shell()[0], None, choice).set_tv(bad)
    assert r["ok"] is False and choice.name == "lg" and not (tmp_path / "remote-app.json").exists()


def test_set_tv_arity_through_the_real_dispatcher(tmp_path):
    from webview.util import js_bridge_call

    choice = TvChoice(tmp_path / "remote-app.json")
    for params in ([], ["vizio", "lg"]):
        window = BridgeWindow(make_bridge(shell()[0], None, choice), SEALED)
        js_bridge_call(window, "set_tv", params, "1")
        assert window.answered.wait(5)
        assert "wrong call" in window.results[0] and "Traceback" not in window.results[0]
    assert choice.name == "lg"


def test_unsaved_choice_still_switches(tmp_path):
    blocker = tmp_path / "file"
    blocker.write_text("x")
    choice = TvChoice(blocker / "remote-app.json")  # parent is a file: cannot save
    r = RemoteApi(shell()[0], None, choice).set_tv("vizio")
    assert r["ok"] and "Not saved" in r["status"] and choice.name == "vizio"


@pytest.mark.parametrize("cmd,key", [("down", "VOLUME_DOWN"), ("mute", "VOLUME_MUTE")])
def test_unpaired_vizio_sends_down_and_mute_as_adb_keys_and_never_calls_the_lg(cmd, key):
    lg_calls = []
    choice = TvChoice(None)
    choice.set("vizio")
    sh, fake = shell()
    r = RemoteApi(sh, lambda c: lg_calls.append(c) or "ok 11", choice).volume(cmd)
    assert r["ok"] and r["via"] == "googletv" and r["tv"] == "vizio" and r["pair"] is True
    assert "tv-remote-vizio-pair" in r["status"]
    assert fake.lines == [f"input keyevent KEYCODE_{key}"]
    assert lg_calls == []


def test_unpaired_vizio_up_is_not_sent_and_says_how_to_pair():
    choice = TvChoice(None)
    choice.set("vizio")
    sh, fake = shell()
    r = RemoteApi(sh, lambda c: "ok 11", choice).volume("up")
    assert r == {"ok": False, "status": "Pair the Vizio: run tv-remote-vizio-pair.", "via": "none", "tv": "vizio",
                 "pair": True}
    assert fake.lines == []


def test_vizio_mode_does_not_touch_a_real_lg_volume_object():
    lg = FakeLg(10)
    choice = TvChoice(None)
    choice.set("vizio")
    sh, fake = shell()
    vol = LgVolume(lg, timeout=5)
    try:
        api = RemoteApi(sh, vol, choice)
        for cmd in ("up", "up", "down", "mute"):
            api.volume(cmd)
    finally:
        vol.close()
    assert lg.calls == [] and lg.volume == 10
    assert fake.lines == ["input keyevent KEYCODE_VOLUME_DOWN", "input keyevent KEYCODE_VOLUME_MUTE"]  # no up


def test_lg_mode_is_unchanged_after_switching_back():
    lg_calls = []
    choice = TvChoice(None)
    sh, fake = shell()
    api = RemoteApi(sh, lambda c: lg_calls.append(c) or "ok 11", choice)
    api.set_tv("vizio")
    api.set_tv("lg")
    assert api.volume("up") == {"ok": True, "status": "Volume 11", "via": "lg", "level": 11, "tv": "lg"}
    assert lg_calls == ["up"] and fake.lines == []


class FakeVizioVolume:
    """Stands in for LgVolume(VizioRemote, name="Vizio", unknown_up="step")."""

    def __init__(self, replies):
        self.replies, self.calls, self.level_known = list(replies), [], None

    def __call__(self, cmd):
        self.calls.append(cmd)
        reply = self.replies.pop(0)
        if cmd in ("up", "down"):
            self.level_known = reply[3:].isdigit() if reply.startswith("ok ") else False
        return reply


def vizio_api(vz, clock=None, lg=None):
    choice = TvChoice(None, clock=clock or Clock())
    choice.set("vizio")
    sh, fake = shell()
    return RemoteApi(sh, lg, choice, vz), fake, choice


def test_paired_vizio_volume_goes_to_smartcast_with_the_level():
    lg_calls = []
    vz = FakeVizioVolume(["ok 21", "ok 20", "ok muted"])
    api, fake, _ = vizio_api(vz, lg=lambda c: lg_calls.append(c) or "ok 1")
    assert api.volume("up") == {"ok": True, "status": "Volume 21", "via": "vizio", "level": 21, "tv": "vizio"}
    assert api.volume("down")["level"] == 20
    assert api.volume("mute")["muted"] is True
    assert vz.calls == ["up", "down", "mute"] and fake.lines == [] and lg_calls == []


def test_paired_vizio_at_cap_and_errors():
    vz = FakeVizioVolume(["ok 60", "error the Vizio did not answer", "error the Vizio did not answer",
                          "error the Vizio's certificate changed; pair it again"])
    api, fake, _ = vizio_api(vz, clock=Clock())
    assert api.volume("up")["status"] == "Volume 60 (the most this remote allows)"
    r = api.volume("up")
    assert r["ok"] is False and r["status"] == "The Vizio did not answer. Volume up was not sent." and fake.lines == []
    r = api.volume("down")  # down falls back to the adb key
    assert r["ok"] and r["via"] == "googletv" and fake.lines == ["input keyevent KEYCODE_VOLUME_DOWN"]
    r = api.volume("mute")
    assert "run tv-remote-vizio-pair again" in r["status"]


def test_vizio_up_backstop_only_while_the_level_is_unknown():
    clock = Clock()
    vz = FakeVizioVolume(["ok -", "ok -", "ok 31", "ok 32", "ok 33"])
    api, fake, _ = vizio_api(vz, clock=clock)
    assert api.volume("up")["ok"]  # level unknown after this
    clock.t += 0.2
    r = api.volume("up")
    assert r["ok"] is False and "One step per tap" in r["status"] and vz.calls == ["up"]
    clock.t += 0.25
    assert api.volume("up")["ok"]  # the second "ok -"
    clock.t += 0.5
    assert api.volume("up")["level"] == 31  # level known again
    clock.t += 0.05
    assert api.volume("up")["level"] == 32  # a held button repeats: the cap now guards it
    assert api.volume("up")["level"] == 33


def test_no_adb_fallback_if_switched_to_vizio_while_the_lg_was_asked():
    choice = TvChoice(None)
    sh, fake = shell()

    def lg(cmd):
        choice.set("vizio")  # the user switches while the LG call is in flight
        return "error the LG did not answer"

    r = RemoteApi(sh, lg, choice).volume("down")
    assert r["ok"] is False and "now on the Vizio" in r["status"]
    assert fake.lines == []


def test_settings_replace_is_retried_once(tmp_path, monkeypatch):
    real = pcremote.os.replace
    tries = []

    def flaky(a, b):
        tries.append(1)
        if len(tries) == 1:
            raise PermissionError("held by a scanner")
        return real(a, b)

    monkeypatch.setattr(pcremote.os, "replace", flaky)
    choice = TvChoice(tmp_path / "remote-app.json")
    assert choice.set("vizio") is True and len(tries) == 2
    assert TvChoice(tmp_path / "remote-app.json").name == "vizio"


# ---- inputs on the LG (lg-input-over-cec) ----

class FakeLgInputs(FakeLg):
    async def set_input(self, source):
        if self.fail:
            raise self.fail
        self.calls.append(("input", source))


def test_input_goes_to_the_lg_not_the_google_tv():
    lg = FakeLgInputs()
    sh, fake = shell()
    vol = LgVolume(lg, timeout=5)
    try:
        r = RemoteApi(sh, vol, TvChoice(None)).tv_input("HDMI_2")
        assert r["ok"] and r["input"] == "HDMI_2" and r["via"] == "lg" and "HDMI 2" in r["status"]
        assert lg.calls == [("input", "HDMI_2")] and fake.lines == []
    finally:
        vol.close()


def test_input_refuses_anything_but_hdmi_1_to_4():
    lg = FakeLgInputs()
    sh, fake = shell()
    vol = LgVolume(lg, timeout=5)
    try:
        api = RemoteApi(sh, vol, TvChoice(None))
        for bad in ("HDMI_5", "hdmi_1", "AV", 3, None):
            assert api.tv_input(bad)["ok"] is False
        assert lg.calls == [] and fake.lines == []
    finally:
        vol.close()


def test_input_when_the_lg_does_not_answer_says_so_and_sends_nothing_else():
    lg = FakeLgInputs(fail=DeviceUnreachable("lg", LG_HOST))
    sh, fake = shell()
    vol = LgVolume(lg, timeout=5)
    try:
        r = RemoteApi(sh, vol, TvChoice(None)).tv_input("HDMI_1")
        assert r["ok"] is False and r["status"] == "The LG did not answer. Input not changed."
        assert LG_HOST not in json.dumps(r) and fake.lines == []
    finally:
        vol.close()


def test_input_without_the_lg_or_on_the_vizio_uses_the_google_tv_key():
    sh, fake = shell()
    r = RemoteApi(sh, None, TvChoice(None)).tv_input("HDMI_1")
    assert r["via"] == "googletv"
    choice = TvChoice(None)
    choice.set("vizio")
    lg = FakeLgInputs()
    vol = LgVolume(lg, timeout=5)
    try:
        r = RemoteApi(sh, vol, choice).tv_input("HDMI_1")
        assert r["via"] == "googletv" and lg.calls == []
    finally:
        vol.close()
    assert any("KEYCODE_TV_INPUT" in line for line in fake.lines)


def test_dashboard_opens_the_dashboard_activity_not_ask_claude():
    line = pcremote.app_line("com.gnosh.tvhome")
    assert line.startswith("am start -n com.gnosh.tvhome/.AgendaActivity ") and "monkey" not in line
    assert "AskClaudeEntry" not in line
