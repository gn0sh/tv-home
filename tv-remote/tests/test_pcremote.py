"""The PC on-screen remote's bridge (pcremote.py), against fake adb processes and a fake LG. No device is touched."""

import inspect
import re
import subprocess
from pathlib import Path

import pytest

from tv_remote import pcremote
from tv_remote.devices import DeviceUnreachable, LgStatus, NotPaired
from tv_remote.pcremote import AdbShell, LgVolume, RemoteApi

SERIAL = "192.168.68.71:5555"
LG_HOST = "192.168.68.61"


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


def test_only_the_five_bridge_methods_are_public():
    public = {n for n, _ in inspect.getmembers(RemoteApi, inspect.isfunction) if not n.startswith("_")}
    assert public == {"start", "key", "app", "text", "volume"}
    api = RemoteApi(shell()[0], None)
    assert all(n.startswith("_") for n in vars(api))  # pywebview exposes public attributes too


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
        assert r == {"ok": True, "status": "Volume 11", "via": "lg", "level": 11}
        assert api.volume("down")["level"] == 10
        assert lg.calls == ["up", "down"] and fake.lines == []
    finally:
        vol.close()


def test_volume_cap_60_still_applies():
    lg = FakeLg(60)
    api, fake, vol = lg_api(lg)
    try:
        assert api.volume("up")["level"] == 60
        assert lg.calls == ["up", ("set", 60)]
    finally:
        vol.close()


def test_mute_toggles_on_the_lg():
    lg = FakeLg(muted=False)
    api, fake, vol = lg_api(lg)
    try:
        assert api.volume("mute") == {"ok": True, "status": "Muted", "via": "lg", "muted": True}
        assert api.volume("mute")["muted"] is False
    finally:
        vol.close()


@pytest.mark.parametrize("cmd,key", [("up", "VOLUME_UP"), ("down", "VOLUME_DOWN"), ("mute", "VOLUME_MUTE")])
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


def test_unreachable_lg_is_skipped_for_ten_seconds():
    lg = FakeLg(fail=DeviceUnreachable("lg", LG_HOST))
    clock = Clock()
    sh, fake = shell()
    vol = LgVolume(lg, timeout=5, clock=clock)
    try:
        api = RemoteApi(sh, vol)
        api.volume("up")
        lg.fail = None
        api.volume("up")
        assert lg.calls == [] and len(fake.lines) == 2  # straight to adb, no 8 s wait per press
        clock.t += 11
        assert api.volume("up")["via"] == "lg"
    finally:
        vol.close()


def test_lg_not_set_up_uses_adb():
    sh, fake = shell()
    r = RemoteApi(sh, None).volume("up")
    assert r["ok"] and r["via"] == "googletv" and "not set up" in r["status"]
    assert fake.lines == ["input keyevent KEYCODE_VOLUME_UP"]


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
    r = RemoteApi(sh, lambda cmd: "error <script>192.168.68.61</script>").volume("up")
    assert r["status"] == "The LG: did not work. Sent to the Google TV instead."


@pytest.mark.parametrize("reply,said", [
    ("error the LG's certificate changed; pair it again", "The LG's certificate changed; pair it again."),
    ("error the LG is not paired", "The LG is not paired."),
    ("error RuntimeError", "The LG: RuntimeError."),
])
def test_lg_reasons_read_well(reply, said):
    sh, fake = shell()
    assert RemoteApi(sh, lambda cmd: reply).volume("down")["status"] == said + " Sent to the Google TV instead."


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
    for method in ("start", "key", "app", "text", "volume"):
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
