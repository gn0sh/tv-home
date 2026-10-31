import asyncio
import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from tv_remote.config import Config, GoogleTvConfig
from tv_remote.devices import Discovered, FakeGoogleTv, GoogleTvStub, LgStub
from tv_remote.server import ALLOWED_TOOLS, SERVICE_PACKAGES, build_server
from tv_remote.devices import FakeDiscovery

from conftest import Harness


def test_exactly_the_allowlist_is_registered(h):
    assert set(h.tools()) == set(ALLOWED_TOOLS)
    assert len(ALLOWED_TOOLS) == 10


def test_schemas_carry_enums_and_ranges(h):
    t = h.tools()
    key = t["tv_key"].input_schema
    assert key["properties"]["repeat"]["minimum"] == 1 and key["properties"]["repeat"]["maximum"] == 10
    keys = str(key)
    for k in ["UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME", "PLAY_PAUSE", "REWIND", "FAST_FORWARD"]:
        assert k in keys
    vol = str(t["lg_volume"].input_schema)
    assert "'maximum': 60" in vol and "'minimum': 0" in vol and "'up'" in vol
    assert "HDMI_4" in str(t["lg_input"].input_schema)
    assert set(SERVICE_PACKAGES) <= set(str(t["tv_launch_app"].input_schema).replace("'", " ").split())
    assert t["tv_status"].annotations.read_only_hint is True


@pytest.mark.parametrize(
    "name", ["tv_uninstall", "uninstall_app", "install_app", "factory_reset", "change_settings", "lg_settings",
             "adb_shell", "ssap_request", "open_browser", "send_notification", "account_sign_out", "TV_STATUS"],
)
def test_anything_outside_the_list_is_refused_clearly(h, name):
    with pytest.raises(ToolError) as e:
        h.call(name)
    msg = str(e.value)
    assert msg.startswith(f"Refused: '{name}'") and "uninstalling" in msg and "factory reset" in msg
    assert h.lg.calls == [] and h.gtv.calls == []


@pytest.mark.parametrize(
    "name,args,expect",
    [
        ("lg_volume", {"level": 61}, "60"),
        ("lg_volume", {"level": -1}, "0"),
        ("lg_volume", {"level": "max"}, "lg_volume"),
        ("tv_key", {"key": "POWER"}, "tv_key"),
        ("tv_key", {"key": "OK", "repeat": 11}, "10"),
        ("tv_key", {"key": "OK", "repeat": 0}, "1"),
        ("lg_input", {"source": "USB"}, "lg_input"),
        ("lg_power", {"state": "reboot"}, "lg_power"),
        ("tv_launch_app", {"service": "com.evil.app"}, "tv_launch_app"),
        ("tv_open", {"service": "netflix", "content_id": "x/../../y"}, "tv_open"),
        ("tv_open", {"service": "netflix", "search_query": "a" * 101}, "tv_open"),
        ("tv_pair", {"device": "roku"}, "tv_pair"),
        ("tv_pair", {"device": "googletv", "code": "12345"}, "tv_pair"),
    ],
)
def test_out_of_range_arguments_are_refused(h, name, args, expect):
    with pytest.raises(ToolError, match=expect):
        h.call(name, **args)
    assert h.lg.calls == [] and h.gtv.calls == []


def test_happy_paths(h):
    assert "volume is 45" in h.text("lg_volume", level=45)
    assert "volume is 46" in h.text("lg_volume", level="up")
    h.text("lg_mute", muted=True)
    h.text("lg_input", source="HDMI_2")
    h.text("lg_power", state="off")
    h.text("lg_power", state="on")
    assert [c[0] for c in h.lg.calls] == ["set_volume", "volume_step", "set_mute", "set_input", "power_off", "power_on"]
    h.text("tv_key", key="DOWN", repeat=3)
    h.text("tv_launch_app", service="netflix")
    assert h.gtv.calls == [("send_key", "DOWN")] * 3 + [("launch_package", "com.netflix.ninja")]


def test_volume_up_never_passes_cap(h):
    h.lg.volume = 60
    assert "volume is 60" in h.text("lg_volume", level="up")


def test_unreachable_names_the_device_and_does_nothing_else(h):
    h.gtv.reachable = False
    with pytest.raises(ToolError) as e:
        h.call("tv_key", key="OK", repeat=5)
    assert "Google TV at 192.168.68.81 is unreachable" in str(e.value) and "Nothing was sent" in str(e.value)
    h.lg.reachable = False
    with pytest.raises(ToolError, match="LG TV at 192.168.68.61 is unreachable"):
        h.call("lg_mute", muted=True)
    assert h.lg.calls == [] and h.gtv.calls == []


def test_status_reports_each_device_separately(h):
    h.lg.reachable = False
    s = json.loads(h.text("tv_status"))
    assert s["lg"]["reachable"] is False and "LG TV" in s["lg"]["error"]
    assert s["googletv"] == {"reachable": True, "current_app": "com.google.android.apps.tv.launcherx"}


def test_missing_device_config():
    gtv = FakeGoogleTv()
    server = build_server(Config(None, GoogleTvConfig(gtv.host)), None, gtv, FakeDiscovery())
    with pytest.raises(ToolError, match="LG TV is not set up"):
        asyncio.run(server.call_tool("lg_power", {"state": "on"}))


def test_tv_open_uses_injected_link_builder():
    seen = []
    h = Harness(links=lambda s, c, q: seen.append((s, c, q)) or f"https://example.test/{s}")
    h.text("tv_open", service="netflix", content_id="80057281")
    assert seen == [("netflix", "80057281", None)] and h.gtv.calls == [("open_link", "https://example.test/netflix")]
    with pytest.raises(ToolError, match="not both"):
        h.call("tv_open", service="netflix", content_id="1", search_query="x")


def test_tv_open_before_c2_says_not_built(h):
    with pytest.raises(ToolError, match="C2"):
        h.call("tv_open", service="netflix", search_query="stranger things")
    assert h.gtv.calls == []


def test_pairing_flow(h):
    assert "Accept" in h.text("tv_pair", device="lg")
    with pytest.raises(ToolError, match="no code"):
        h.call("tv_pair", device="lg", code="ABC123")
    assert "code" in h.text("tv_pair", device="googletv")
    assert "paired" in h.text("tv_pair", device="googletv", code="ab12cd")


def test_discover(h):
    h.discovery.found = [Discovered("lg", "192.168.68.61", "LG OLED")]
    assert "192.168.68.61" in h.text("tv_discover")


def test_real_backend_stubs_report_their_task():
    lg, gtv = LgStub("192.168.68.61", "AA:BB:CC:DD:EE:FF"), GoogleTvStub("192.168.68.81")
    from tv_remote.devices import DiscoveryStub
    from tv_remote.config import LgConfig

    server = build_server(Config(LgConfig(lg.host, lg.mac), GoogleTvConfig(gtv.host)), lg, gtv, DiscoveryStub())
    with pytest.raises(ToolError, match="C3"):
        asyncio.run(server.call_tool("lg_power", {"state": "off"}))
    with pytest.raises(ToolError, match="C4"):
        asyncio.run(server.call_tool("tv_key", {"key": "OK"}))
