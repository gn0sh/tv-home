import asyncio
import json
import ssl

import pytest
from aiohttp import WSMsgType, web
from androidtvremote2.certificate_generator import generate_selfsigned_cert

from tv_remote import lg as lgmod
from tv_remote.devices import CertificateChanged, DeviceRefused, DeviceUnreachable, NotPaired
from tv_remote.lg import (
    ALLOWED_URIS,
    PERMISSIONS,
    LgRemote,
    LgVault,
    magic_packet,
    registration_message,
)
from tv_remote.secretstore import FakeProtector, SecretStoreError
from tv_remote.tlspin import fingerprint

HOST = "127.0.0.1"
MAC = "A8:23:FE:01:02:03"

# What each SSAP URI needs on a real TV (subset relevant to the tests).
NEEDS = {
    "system/turnOff": "CONTROL_POWER",
    "com.webos.service.tvpower/power/getPowerState": "READ_POWER_STATE",
    "audio/getVolume": "CONTROL_AUDIO",
    "audio/setVolume": "CONTROL_AUDIO",
    "audio/volumeUp": "CONTROL_AUDIO",
    "audio/volumeDown": "CONTROL_AUDIO",
    "audio/setMute": "CONTROL_AUDIO",
    "tv/switchInput": "CONTROL_INPUT_TV",
    "com.webos.applicationManager/getForegroundAppInfo": "READ_RUNNING_APPS",
    "system.notifications/createToast": "WRITE_NOTIFICATION_TOAST",
    "settings/getSystemSettings": "READ_SETTINGS",
    "com.webos.appInstallService/remove": "WRITE_SETTINGS",
}


def _cert(tmp_path, name):
    cert_pem, key_pem = generate_selfsigned_cert(name)
    d = tmp_path / f"tv-{name}"
    d.mkdir()
    (d / "c.pem").write_bytes(cert_pem)
    (d / "k.pem").write_bytes(key_pem)  # the fake TV's own throwaway key
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(d / "c.pem", d / "k.pem")
    return ctx, fingerprint(ssl.PEM_cert_to_DER_cert(cert_pem.decode()))


class FakeTV:
    """A webOS SSAP server over TLS that enforces the registered permission manifest."""

    def __init__(self, ssl_ctx):
        self.ssl_ctx = ssl_ctx
        self.keys = {}  # client-key -> permissions
        self.pair_answer = "accept"  # accept | decline | ignore
        self.state = "Active"
        self.volume, self.muted, self.app = 20, False, "com.webos.app.hdmi2"
        self.log = []  # every message received
        self.registrations = []
        self.connections = 0
        self.secure = []
        self.silent = False
        self.runner = None

    async def start(self):
        app = web.Application()
        app.router.add_get("/", self.handle)
        self.runner = web.AppRunner(app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, HOST, 0, ssl_context=self.ssl_ctx)
        await site.start()
        self.port = site._server.sockets[0].getsockname()[1]

    async def stop(self):
        await self.runner.cleanup()

    async def handle(self, request):
        self.connections += 1
        self.secure.append(request.secure)
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        perms = None
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                break
            m = json.loads(msg.data)
            self.log.append(m)
            if self.silent:
                continue
            t, mid = m.get("type"), m.get("id")
            if t == "hello":
                await ws.send_json({"type": "hello", "id": mid, "payload": {"deviceOS": "webOS"}})
            elif t == "register":
                p = m["payload"]
                self.registrations.append(p)
                key = p.get("client-key")
                if key in self.keys:
                    perms = self.keys[key]
                    await ws.send_json({"type": "registered", "id": mid, "payload": {"client-key": key}})
                    continue
                await ws.send_json({"type": "response", "id": mid, "payload": {"pairingType": "PROMPT"}})
                if self.pair_answer == "accept":
                    new = f"key-{len(self.keys) + 1}"
                    self.keys[new] = set(p["manifest"]["permissions"])
                    perms = self.keys[new]
                    await ws.send_json({"type": "registered", "id": mid, "payload": {"client-key": new}})
                elif self.pair_answer == "decline":
                    await ws.send_json({"type": "error", "id": mid, "error": "403 cancelled", "payload": {}})
            elif t == "request":
                uri = m["uri"].removeprefix("ssap://")
                if uri == "system/getSystemInfo":
                    await ws.send_json({"type": "response", "id": mid, "payload": {"returnValue": True}})
                    continue
                if perms is None or NEEDS.get(uri, "UNKNOWN") not in perms:
                    await ws.send_json({"type": "error", "id": mid, "error": "401 insufficient permissions",
                                        "payload": {}})
                    continue
                await ws.send_json({"type": "response", "id": mid, "payload": self.answer(uri, m["payload"])})
        return ws

    def answer(self, uri, payload):
        if uri.endswith("getPowerState"):
            return {"returnValue": True, "state": self.state}
        if uri == "audio/getVolume":
            return {"returnValue": True, "volumeStatus": {"volume": self.volume, "muteStatus": self.muted}}
        if uri == "audio/setVolume":
            self.volume = payload["volume"]
        if uri == "audio/volumeUp":
            self.volume += 1
        if uri == "audio/volumeDown":
            self.volume -= 1
        if uri == "audio/setMute":
            self.muted = payload["mute"]
        if uri == "tv/switchInput":
            self.app = "com.webos.app." + payload["inputId"].lower().replace("_", "")
        if uri.endswith("getForegroundAppInfo"):
            return {"returnValue": True, "appId": self.app}
        if uri == "system/turnOff":
            self.state = "Active Standby"
        return {"returnValue": True}


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


@pytest.fixture
def env(tmp_path):
    ctx, fp = _cert(tmp_path, "lg")
    vault = LgVault(tmp_path / "state", FakeProtector())
    return tmp_path, ctx, fp, vault


def scenario(env, body, **tv_kw):
    """Run `body(tv, remote, packets)` against a live fake TV."""
    tmp_path, ctx, fp, vault = env

    async def go():
        tv = FakeTV(ctx)
        for k, v in tv_kw.items():
            setattr(tv, k, v)
        await tv.start()
        packets = []
        remote = LgRemote(HOST, MAC, vault, port=tv.port, wol_sender=packets.append)
        try:
            return await body(tv, remote, packets)
        finally:
            await remote._drop()
            await tv.stop()

    return asyncio.run(go())


# --- manifest and transport -----------------------------------------------------------------------------------------


def test_manifest_is_exactly_the_eight_permissions():
    msg = registration_message(None)
    assert msg["payload"]["manifest"]["permissions"] == list(PERMISSIONS)
    assert set(PERMISSIONS) == {"LAUNCH", "CONTROL_POWER", "CONTROL_AUDIO", "CONTROL_INPUT_TV",
                                "READ_INPUT_DEVICE_LIST", "READ_RUNNING_APPS", "READ_POWER_STATE", "READ_APP_STATUS"}
    assert "client-key" not in msg["payload"] and registration_message("k")["payload"]["client-key"] == "k"
    from aiowebostv.handshake import REGISTRATION_PAYLOAD

    assert len(REGISTRATION_PAYLOAD["manifest"]["permissions"]) > 30  # library default untouched by our copy


def test_wss_3001_only():
    r = LgRemote("192.168.68.61", MAC, LgVault("/nonexistent", FakeProtector()))
    assert r.url == "wss://192.168.68.61:3001"
    import ast

    tree = ast.parse(open(lgmod.__file__).read())
    doc = ast.get_docstring(tree, clean=False)
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and node.value != doc:
            if isinstance(node.value, str):
                assert "ws://" not in node.value.replace("wss://", ""), node.value
            assert node.value != 3000


def test_allowed_uris_are_all_covered_by_the_manifest():
    for uri in ALLOWED_URIS:
        assert NEEDS[uri] in PERMISSIONS


# --- Wake-on-LAN ----------------------------------------------------------------------------------------------------


def test_magic_packet_bytes():
    p = magic_packet(MAC)
    assert len(p) == 102 and p[:6] == b"\xff" * 6
    assert p[6:] == bytes.fromhex("A823FE010203") * 16


def test_wol_sends_limited_broadcast_port_9(monkeypatch):
    sent = {}

    class FakeSock:
        def __init__(self, *a):
            sent["family"] = a

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def setsockopt(self, *a):
            sent["opt"] = a

        def sendto(self, data, addr):
            sent["data"], sent["addr"] = data, addr

    monkeypatch.setattr(lgmod.socket, "socket", FakeSock)
    lgmod.send_wol(magic_packet(MAC))
    assert sent["addr"] == ("255.255.255.255", 9) and len(sent["data"]) == 102
    assert sent["opt"][1:] == (lgmod.socket.SO_BROADCAST, 1)


def test_power_on_sends_wol_without_connecting(env):
    async def body(tv, remote, packets):
        await remote.power_on()
        return tv.connections, packets

    conns, packets = scenario(env, body)
    assert conns == 0 and packets == [magic_packet(MAC)]


# --- pairing state machine ------------------------------------------------------------------------------------------


def test_pairing_accept_seals_key_and_pins_cert(env):
    tmp_path, ctx, fp, vault = env

    async def body(tv, remote, packets):
        return await remote.pair(), tv.registrations

    msg, regs = scenario(env, body)
    assert msg == "LG TV paired."
    assert regs[0]["manifest"]["permissions"] == list(PERMISSIONS) and "client-key" not in regs[0]
    assert vault.pinned() == fp and vault.client_key() == "key-1"
    sealed = (tmp_path / "state" / lgmod.KEY_FILE).read_bytes()
    assert b"key-1" not in sealed and sealed.startswith(FakeProtector.TAG)


def test_pairing_declined(env):
    tmp_path, ctx, fp, vault = env

    async def body(tv, remote, packets):
        return await remote.pair()

    assert "declined" in scenario(env, body, pair_answer="decline")
    assert vault.client_key() is None and vault.pinned() is None


def test_pairing_prompt_not_answered_times_out(env, monkeypatch):
    monkeypatch.setattr(lgmod, "PAIR_TIMEOUT", 0.3)
    tmp_path, ctx, fp, vault = env

    async def body(tv, remote, packets):
        return await remote.pair()

    assert "in time" in scenario(env, body, pair_answer="ignore")
    assert vault.pinned() is None


def test_pairing_without_secure_storage_writes_nothing(env):
    tmp_path, ctx, fp, _ = env

    class NoStore:
        def protect(self, data):
            raise SecretStoreError("no DPAPI")

        def unprotect(self, blob):
            raise SecretStoreError("no DPAPI")

    vault = LgVault(tmp_path / "state2", NoStore())

    async def go():
        tv = FakeTV(ctx)
        await tv.start()
        r = LgRemote(HOST, MAC, vault, port=tv.port)
        try:
            with pytest.raises(NotImplementedError, match="Windows"):
                await r.pair()
        finally:
            await tv.stop()

    asyncio.run(go())
    assert not (tmp_path / "state2").exists()


def test_commands_before_pairing_say_not_paired_without_connecting(env):
    async def body(tv, remote, packets):
        with pytest.raises(NotPaired):
            await remote.set_mute(True)
        return tv.connections

    assert scenario(env, body) == 0


# --- commands against the fake TV -----------------------------------------------------------------------------------


def _paired_body(inner):
    async def body(tv, remote, packets):
        await remote.pair()
        tv.log.clear()
        return await inner(tv, remote, packets)

    return body


def test_commands_send_the_right_ssap_messages(env):
    async def inner(tv, remote, packets):
        await remote.set_volume(33)
        up = await remote.volume_step("up")
        await remote.set_mute(True)
        await remote.set_input("HDMI_3")
        st = await remote.status()
        return tv, up, st

    tv, up, st = scenario(env, _paired_body(inner))
    reqs = [(m["uri"], m["payload"]) for m in tv.log if m["type"] == "request" and "SystemInfo" not in m["uri"]]
    assert reqs[:6] == [
        ("ssap://audio/setVolume", {"volume": 33}),
        ("ssap://audio/volumeUp", {}),
        ("ssap://audio/getVolume", {}),
        ("ssap://audio/setMute", {"mute": True}),
        ("ssap://tv/switchInput", {"inputId": "HDMI_3"}),
        ("ssap://com.webos.service.tvpower/power/getPowerState", {}),
    ]
    assert up == 34
    assert (st.power, st.volume, st.muted, st.input, st.foreground_app) == ("on", 34, True, "HDMI_3",
                                                                            "com.webos.app.hdmi3")
    regs = [m for m in tv.log if m["type"] == "register"]
    assert regs and all(r["payload"]["client-key"] == "key-1" for r in regs)
    assert all(tv.secure)  # every connection was TLS


def test_power_off_then_status_off(env):
    async def inner(tv, remote, packets):
        await remote.power_off()
        return await remote.status()

    st = scenario(env, _paired_body(inner))
    assert st.power == "off" and st.volume is None


def test_out_of_manifest_call_is_refused_by_the_tv(env, monkeypatch):
    """Even if our local allowlist were bypassed, the TV refuses calls outside the 8-permission manifest (#5)."""
    monkeypatch.setattr(lgmod, "ALLOWED_URIS", ALLOWED_URIS | {"system.notifications/createToast",
                                                               "com.webos.appInstallService/remove"})

    async def inner(tv, remote, packets):
        out = []
        for uri in ("system.notifications/createToast", "com.webos.appInstallService/remove"):
            with pytest.raises(DeviceRefused) as e:
                await remote._request(uri, {"message": "hi"})
            out.append(str(e.value))
        return out

    for msg in scenario(env, _paired_body(inner)):
        assert "outside the 8 permissions" in msg and "401" not in msg


def test_uri_outside_allowlist_is_refused_before_sending(env):
    async def inner(tv, remote, packets):
        with pytest.raises(DeviceRefused, match="does not send"):
            await remote._request("system.notifications/createToast", {"message": "hi"})
        return tv.log

    assert scenario(env, _paired_body(inner)) == []


# --- pinning and stale links ----------------------------------------------------------------------------------------


def test_changed_certificate_refused_before_key_is_sent(env):
    tmp_path, ctx, fp, vault = env
    other_ctx, _ = _cert(tmp_path, "impostor")

    async def go():
        tv = FakeTV(ctx)
        await tv.start()
        await LgRemote(HOST, MAC, vault, port=tv.port).pair()
        await tv.stop()
        imp = FakeTV(other_ctx)
        imp.keys = dict(tv.keys)
        await imp.start()
        r = LgRemote(HOST, MAC, vault, port=imp.port)
        try:
            with pytest.raises(CertificateChanged) as e:
                await r.set_mute(True)
            return imp, str(e.value)
        finally:
            await imp.stop()

    imp, msg = asyncio.run(go())
    assert "certificate has changed" in msg and imp.log == []  # no hello, no client key reached it


def test_stale_key_shows_no_prompt_and_says_not_paired(env):
    tmp_path, ctx, fp, vault = env

    async def body(tv, remote, packets):
        await remote.pair()
        tv.keys.clear()  # TV forgot us (e.g. factory reset of the app list)
        tv.pair_answer = "ignore"
        with pytest.raises(NotPaired):
            await remote.set_mute(True)

    scenario(env, body)


def test_quiet_link_is_replaced_before_use(env):
    async def body(tv, remote, packets):
        clock = Clock()
        remote._clock = clock
        await remote.pair()
        await remote.set_mute(True)
        await remote.set_mute(False)
        same = tv.connections
        clock.t += lgmod.SILENCE_LIMIT + 1
        await remote.set_mute(True)
        return same, tv.connections, tv.muted

    same, after, muted = scenario(env, body)
    assert after == same + 1 and muted is True


def test_closed_link_reconnects(env):
    async def body(tv, remote, packets):
        await remote.pair()
        await remote.set_mute(True)
        await remote._link.ws.close()  # TV dropped the link (standby, blip)
        await remote.set_mute(False)
        return tv.muted

    assert scenario(env, body) is False


def test_half_open_link_reports_no_reply_and_recovers(env, monkeypatch):
    monkeypatch.setattr(lgmod, "REQUEST_TIMEOUT", 0.3)

    async def body(tv, remote, packets):
        await remote.pair()
        await remote.set_mute(True)
        tv.silent = True  # link still up, TV stopped answering
        with pytest.raises(DeviceUnreachable) as e:
            await remote.set_mute(False)
        tv.silent = False
        await remote.set_mute(False)
        return e.value, tv.muted

    exc, muted = scenario(env, body)
    assert exc.device == "lg" and exc.reason == "no reply" and exc.sent is True and muted is False


def test_unreachable_when_nothing_listens(env):
    tmp_path, ctx, fp, vault = env

    async def go():
        tv = FakeTV(ctx)
        await tv.start()
        port = tv.port
        await LgRemote(HOST, MAC, vault, port=port).pair()
        await tv.stop()
        r = LgRemote(HOST, MAC, vault, port=port)
        with pytest.raises(DeviceUnreachable) as e:
            await r.status()
        return e.value

    exc = asyncio.run(go())
    assert str(exc) == f"LG TV at {HOST} is unreachable (no answer)." and exc.sent is False


def test_tool_layer_message_when_reply_missing():
    from mcp.server.mcpserver.exceptions import ToolError

    from conftest import Harness

    h = Harness()

    async def no_reply(muted):
        raise DeviceUnreachable("lg", h.lg.host, "no reply", sent=True)

    h.lg.set_mute = no_reply
    with pytest.raises(ToolError, match="may or may not have taken effect"):
        h.call("lg_mute", muted=True)
