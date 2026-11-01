import asyncio
import ssl

import pytest
from androidtvremote2 import CannotConnect, ConnectionClosed, InvalidAuth
from androidtvremote2.certificate_generator import generate_selfsigned_cert
from androidtvremote2.remotemessage_pb2 import RemoteKeyCode

from tv_remote import googletv as g
from tv_remote.devices import CertificateChanged, DeviceUnreachable, NotPaired
from tv_remote.googletv import KEYCODES, GoogleTvRemote, KeyVault, SealedKeyRemote, fingerprint, peer_fingerprint
from tv_remote.secretstore import FakeProtector, SecretStoreError, default_protector

HOST = "192.168.68.81"
TV_CERT = b"\x30\x82fake-tv-certificate-der"
OTHER_CERT = b"\x30\x82another-certificate"


class FakeSSL:
    def __init__(self, der):
        self.der = der

    def getpeercert(self, binary=False):
        assert binary
        return self.der


IDLE_CLOSE = 16.0  # androidtvremote2 / the TV drop a link that has been idle this long


class FakeTransport:
    def __init__(self, der, world):
        self.ssl, self.world = FakeSSL(der), world
        self.closed = False
        self.last = world["clock"]

    def get_extra_info(self, name):
        return self.ssl if name == "ssl_object" else None

    def is_closing(self):
        if not self.closed and self.world["clock"] - self.last > IDLE_CLOSE:
            self.closed = True
        return self.closed

    def write(self, item):
        # Like androidtvremote2 0.3.2 base.py _send_message: a closed link drops the message without an error.
        if self.is_closing():
            self.world.setdefault("dropped", []).append(item)
            return
        self.last = self.world["clock"]
        self.world["sent"].append(item)


class FakeRemote:
    """Stands in for SealedKeyRemote: same methods the backend uses, scripted outcomes."""

    def __init__(self, world, vault, host, pin):
        self.world, self.vault, self.host, self.pin = world, vault, host, pin
        self.mismatch = False
        self._transport = None
        self.current_app = world.get("app")
        self.callbacks = []
        world.setdefault("remotes", []).append(self)
        self.sent = world.setdefault("sent", [])

    async def async_generate_cert_if_missing(self):
        if not self.vault.has_identity():
            self.vault.create_identity()
            return True
        return False

    async def async_connect(self):
        self.vault.ssl_context(self.pin)  # like SealedKeyRemote: unseal and load the key first
        err = self.world.get("connect_error")
        if err == "hang":
            await asyncio.sleep(60)
        if err:
            raise err
        der = self.world.get("api_cert", TV_CERT)
        # The real handshake hook refuses a wrong certificate before any message, surfacing as InvalidAuth.
        if self.pin is not None and fingerprint(der) != self.pin and not self.world.get("skip_hook"):
            self.mismatch = True
            raise InvalidAuth("Need to pair")
        self._transport = FakeTransport(der, self.world)
        self.connected_at = self.world["clock"]
        self.world.setdefault("connects", 0)
        self.world["connects"] += 1

    def pin_mismatch(self):
        return self.mismatch

    def last_rx(self):
        # A healthy TV pings every 5 s, so "now"; a half-open link goes silent after the connect.
        if self.world.get("tv_silent"):
            return self.connected_at
        return self.world["clock"]

    def api_transport(self):
        return self._transport

    async def async_start_pairing(self):
        err = self.world.get("pair_error")
        if err:
            raise err
        self._pair_transport = FakeTransport(self.world.get("pair_cert", TV_CERT), self.world)

    def pairing_transport(self):
        return getattr(self, "_pair_transport", None)

    async def async_finish_pairing(self, code):
        if code.lower() != self.world.get("code", "ab12cd"):
            raise InvalidAuth("bad code")
        if self.world.get("finish_error"):
            raise self.world["finish_error"]

    def send_key_command(self, code):
        if self._transport is None:
            raise ConnectionClosed("gone")
        self._transport.write(("key", code))

    def send_launch_app_command(self, link):
        if self._transport is None:
            raise ConnectionClosed("gone")
        self._transport.write(("launch", link))

    def add_current_app_updated_callback(self, cb):
        self.callbacks.append(cb)
        if self.world.get("late_app"):
            self.current_app = self.world["late_app"]
            asyncio.get_running_loop().call_soon(cb, self.current_app)

    def remove_current_app_updated_callback(self, cb):
        self.callbacks.remove(cb)

    def disconnect(self):
        self.world["disconnects"] = self.world.get("disconnects", 0) + 1
        if self._transport is not None:
            self._transport.closed = True
        self._transport = None


@pytest.fixture
def vault(tmp_path):
    return KeyVault(tmp_path, FakeProtector())


def make(vault, **world):
    world.setdefault("clock", 0.0)
    world.setdefault("sent", [])
    return GoogleTvRemote(HOST, vault, lambda v, h, pin: FakeRemote(world, v, h, pin),
                          clock=lambda: world["clock"]), world


def run(coro):
    return asyncio.run(coro)


def pair(backend, code="ab12cd"):
    async def go():
        await backend.start_pairing()
        return await backend.finish_pairing(code)

    return run(go())


# --- key storage ----------------------------------------------------------------------------------------------------


def test_identity_key_is_never_on_disk_unencrypted(vault, tmp_path):
    vault.create_identity()
    for f in tmp_path.iterdir():
        data = f.read_bytes()
        assert b"BEGIN PRIVATE KEY" not in data and b"BEGIN RSA PRIVATE KEY" not in data, f
    assert b"BEGIN ENCRYPTED PRIVATE KEY" in (tmp_path / g.KEY_FILE).read_bytes()
    assert (tmp_path / g.KEY_PW_FILE).read_bytes().startswith(FakeProtector.TAG)
    assert b"BEGIN CERTIFICATE" in (tmp_path / g.CERT_FILE).read_bytes()


def test_ssl_context_loads_without_temp_files(vault, tmp_path):
    vault.create_identity()
    before = set(tmp_path.iterdir())
    assert isinstance(vault.ssl_context(), ssl.SSLContext)
    assert set(tmp_path.iterdir()) == before


def test_protector_failure_writes_nothing(tmp_path):
    class NoStore:
        def protect(self, data):
            raise SecretStoreError("no DPAPI")

    with pytest.raises(SecretStoreError):
        KeyVault(tmp_path, NoStore()).create_identity()
    assert list(tmp_path.iterdir()) == []


def test_unsealable_key_means_not_paired(tmp_path):
    KeyVault(tmp_path, FakeProtector()).create_identity()
    (tmp_path / g.KEY_PW_FILE).write_bytes(b"garbage")
    v = KeyVault(tmp_path, FakeProtector())
    with pytest.raises(NotPaired):
        v.ssl_context()
    assert v.identity_usable() is False


def test_no_plaintext_fallback_off_windows(monkeypatch):
    monkeypatch.setattr("sys.platform", "linux")
    with pytest.raises(SecretStoreError):
        default_protector()


def test_sealed_remote_uses_vault_context_and_generates_sealed_key(vault, tmp_path):
    async def go():
        r = SealedKeyRemote(vault, HOST)
        assert await r.async_generate_cert_if_missing() is True
        assert await r.async_generate_cert_if_missing() is False
        return await r._create_ssl_context()

    assert isinstance(run(go()), ssl.SSLContext)
    assert b"BEGIN ENCRYPTED PRIVATE KEY" in (tmp_path / g.KEY_FILE).read_bytes()


def test_peer_fingerprint_over_real_tls(vault, tmp_path):
    """Client context from the vault really handshakes, and the pin is the SHA-256 of the server's DER cert."""
    vault.create_identity()
    cert_pem, key_pem = generate_selfsigned_cert("fake-tv")
    srv_dir = tmp_path / "srv"
    srv_dir.mkdir()
    (srv_dir / "c.pem").write_bytes(cert_pem)
    (srv_dir / "k.pem").write_bytes(key_pem)  # test server's own throwaway key, not a client secret
    sctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    sctx.load_cert_chain(srv_dir / "c.pem", srv_dir / "k.pem")
    expected = fingerprint(ssl.PEM_cert_to_DER_cert(cert_pem.decode()))

    async def go():
        server = await asyncio.start_server(lambda r, w: w.close(), "127.0.0.1", 0, ssl=sctx)
        port = server.sockets[0].getsockname()[1]
        _, writer = await asyncio.open_connection("127.0.0.1", port, ssl=vault.ssl_context())
        fp = peer_fingerprint(writer.transport)
        writer.close()
        server.close()
        await server.wait_closed()
        return fp

    assert run(go()) == expected


def _tls_server(tmp_path):
    cert_pem, key_pem = generate_selfsigned_cert("fake-tv")
    d = tmp_path / "srv2"
    d.mkdir()
    (d / "c.pem").write_bytes(cert_pem)
    (d / "k.pem").write_bytes(key_pem)
    sctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    sctx.load_cert_chain(d / "c.pem", d / "k.pem")
    return sctx, fingerprint(ssl.PEM_cert_to_DER_cert(cert_pem.decode()))


@pytest.mark.parametrize("right_pin", [True, False])
def test_pin_is_checked_inside_the_handshake_before_any_data(vault, tmp_path, right_pin):
    vault.create_identity()
    sctx, fp = _tls_server(tmp_path)
    received = []

    async def handle(reader, writer):
        try:
            received.append(await reader.read(100))
        except (ConnectionError, ssl.SSLError):
            pass
        writer.close()

    class Proto(asyncio.Protocol):
        def connection_made(self, transport):
            received.append("connection_made")
            transport.write(b"configure")  # what the remote protocol would send first

    async def go():
        server = await asyncio.start_server(handle, "127.0.0.1", 0, ssl=sctx)
        port = server.sockets[0].getsockname()[1]
        ctx = vault.ssl_context(pin=fp if right_pin else "0" * 64)
        try:
            transport, _ = await asyncio.get_running_loop().create_connection(Proto, "127.0.0.1", port, ssl=ctx)
            await asyncio.sleep(0.1)
            transport.close()
            return ctx, None
        except ssl.SSLError as exc:
            await asyncio.sleep(0.1)
            return ctx, exc
        finally:
            server.close()
            await server.wait_closed()

    ctx, exc = run(go())
    if right_pin:
        assert exc is None and "connection_made" in received and b"configure" in received
    else:
        assert isinstance(exc, g.PinMismatch) and ctx.tv_pin_mismatch
        assert "connection_made" not in received and b"configure" not in received


# --- pairing and pinning --------------------------------------------------------------------------------------------


def test_pairing_pins_the_certificate_seen_while_pairing(vault):
    b, world = make(vault)
    msg = run(b.start_pairing())
    assert "code" in msg and vault.pinned() is None  # nothing pinned until the code is accepted
    assert run(b.finish_pairing("AB12CD")) == "Google TV paired."
    assert vault.pinned() == fingerprint(TV_CERT)


def test_wrong_code_pins_nothing_and_can_retry(vault):
    b, _ = make(vault)
    run(b.start_pairing())
    assert "did not match" in run(b.finish_pairing("000000"))
    assert vault.pinned() is None
    assert run(b.finish_pairing("ab12cd")) == "Google TV paired."


def test_finish_without_start_is_not_paired(vault):
    b, _ = make(vault)
    with pytest.raises(NotPaired):
        run(b.finish_pairing("ab12cd"))


def test_commands_before_pairing_say_not_paired(vault):
    b, world = make(vault)
    with pytest.raises(NotPaired):
        run(b.send_key("OK"))
    assert "remotes" not in world  # no connection attempted


def test_post_handshake_check_still_refuses_if_hook_bypassed(vault):
    b, world = make(vault, skip_hook=True)
    pair(b)
    world["api_cert"] = OTHER_CERT
    with pytest.raises(CertificateChanged):
        run(b.send_key("OK"))
    assert world["sent"] == []


def test_changed_certificate_is_refused_and_nothing_sent(vault):
    b, world = make(vault)
    pair(b)
    world["api_cert"] = OTHER_CERT
    with pytest.raises(CertificateChanged) as e:
        run(b.send_key("OK"))
    assert "certificate has changed" in str(e.value) and "tv_pair" in str(e.value)
    assert world["sent"] == []
    with pytest.raises(CertificateChanged):  # still refused on the next try
        run(b.launch_package("com.netflix.ninja"))


def test_repairing_replaces_the_pin(vault):
    b, world = make(vault)
    pair(b)
    world["pair_cert"] = world["api_cert"] = OTHER_CERT
    pair(b)
    assert vault.pinned() == fingerprint(OTHER_CERT)
    run(b.send_key("HOME"))
    assert world["sent"] == [("key", "KEYCODE_HOME")]


def test_corrupt_pin_file_means_not_paired(vault, tmp_path):
    b, _ = make(vault)
    pair(b)
    (tmp_path / g.PIN_FILE).write_text("not-a-hash")
    with pytest.raises(NotPaired):
        run(b.send_key("OK"))


def test_pairing_off_windows_is_refused_cleanly(tmp_path):
    class NoStore:
        def protect(self, data):
            raise SecretStoreError("no DPAPI")

        def unprotect(self, blob):
            raise SecretStoreError("no DPAPI")

    b, _ = make(KeyVault(tmp_path, NoStore()))
    with pytest.raises(NotImplementedError, match="Windows"):
        run(b.start_pairing())
    assert list(tmp_path.iterdir()) == []


# --- commands -------------------------------------------------------------------------------------------------------


def test_every_key_maps_to_a_real_keycode():
    from typing import get_args

    from tv_remote.devices import Key

    assert set(KEYCODES) == set(get_args(Key))
    for code in KEYCODES.values():
        RemoteKeyCode.Value(code)  # raises if the protocol has no such key


def test_keys_links_and_apps(vault):
    b, world = make(vault, app="com.netflix.ninja")
    pair(b)

    async def go():
        await b.send_key("OK")
        await b.send_key("PLAY_PAUSE")
        await b.open_link("https://www.netflix.com/title/80057281")
        await b.launch_package("com.google.android.youtube.tv")
        return await b.current_app()

    assert run(go()) == "com.netflix.ninja"
    assert world["sent"] == [
        ("key", "KEYCODE_DPAD_CENTER"),
        ("key", "KEYCODE_MEDIA_PLAY_PAUSE"),
        ("launch", "https://www.netflix.com/title/80057281"),
        ("launch", "com.google.android.youtube.tv"),
    ]
    assert len(world["remotes"]) == 2  # one for pairing, then one API connection reused for all four commands


def test_current_app_waits_for_first_update(vault):
    b, world = make(vault, late_app="com.hulu.livingroomplus")
    pair(b)
    assert run(b.current_app()) == "com.hulu.livingroomplus"


@pytest.mark.parametrize("uri", ["market://details?id=x", "intent://x", "http://netflix.com/x", "https://a b",
                                 "https://x.test/a\x00", "https://x.test/\u202e", "https://x.test/\x85"])
def test_open_link_only_sends_https(vault, uri):
    b, world = make(vault)
    pair(b)
    with pytest.raises(ValueError):
        run(b.open_link(uri))
    assert world["sent"] == []


@pytest.mark.parametrize("pkg", ["market://launch?id=x", "https://x.y", "Com.Evil", "noperiod", "a.b c", "com.netflix.ninja\n"])
def test_launch_package_refuses_non_packages(vault, pkg):
    b, world = make(vault)
    pair(b)
    with pytest.raises(ValueError):
        run(b.launch_package(pkg))
    assert world["sent"] == []


# --- unreachable ----------------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "err,reason",
    [(CannotConnect("Couldn't connect to 192.168.68.81:6466 secret detail"), "no answer"),
     (OSError("errno 113 No route to host"), "no answer"),
     (ConnectionClosed("Connection closed detail"), "connection closed")],
)
def test_connect_failures_name_the_device_with_fixed_reason(vault, err, reason):
    b, world = make(vault)
    pair(b)
    world["connect_error"] = err
    with pytest.raises(DeviceUnreachable) as e:
        run(b.send_key("OK"))
    assert e.value.device == "googletv" and e.value.reason == reason
    assert str(e.value) == f"Google TV at {HOST} is unreachable ({reason})."


def test_connect_timeout(vault, monkeypatch):
    monkeypatch.setattr(g, "CONNECT_TIMEOUT", 0.05)
    b, world = make(vault)
    pair(b)
    world["connect_error"] = "hang"
    with pytest.raises(DeviceUnreachable) as e:
        run(b.send_key("OK"))
    assert e.value.reason == "timed out"


def test_invalid_auth_on_connect_means_not_paired(vault):
    b, world = make(vault)
    pair(b)
    world["connect_error"] = InvalidAuth("Need to pair again")
    with pytest.raises(NotPaired):
        run(b.send_key("OK"))


def _connected(vault, **kw):
    b, world = make(vault, **kw)
    pair(b)
    run(b.send_key("OK"))
    assert world["connects"] == 1
    return b, world


def _tv_closes_link(world):
    world["remotes"][-1].api_transport().closed = True


def test_standby_closed_link_reconnects_and_really_sends(vault):
    b, world = _connected(vault)
    _tv_closes_link(world)  # TV went to standby and closed the link
    run(b.send_key("HOME"))
    assert world["connects"] == 2 and world["sent"][-1] == ("key", "KEYCODE_HOME")
    assert "dropped" not in world


def test_wifi_blip_then_tv_back_reconnects(vault):
    b, world = _connected(vault)
    _tv_closes_link(world)
    world["connect_error"] = OSError("no route")
    with pytest.raises(DeviceUnreachable) as e:
        run(b.send_key("OK"))
    assert e.value.reason == "no answer"
    world["connect_error"] = None
    run(b.launch_package("com.netflix.ninja"))
    assert world["sent"][-1] == ("launch", "com.netflix.ninja") and "dropped" not in world


def test_idle_link_is_replaced_not_silently_dropped(vault):
    b, world = _connected(vault)
    world["clock"] += IDLE_CLOSE + 4
    run(b.send_key("BACK"))
    assert world["connects"] == 2 and world["sent"][-1] == ("key", "KEYCODE_BACK")
    assert "dropped" not in world


def test_half_open_link_silent_for_7s_is_replaced(vault):
    b, world = _connected(vault)
    world["tv_silent"] = True  # Wi-Fi blip: transport still looks open, but no pings arrive
    world["clock"] += 8  # well inside the 16 s idle close
    run(b.send_key("UP"))
    assert world["connects"] == 2 and world["sent"][-1] == ("key", "KEYCODE_DPAD_UP")


def test_link_with_recent_pings_is_reused(vault):
    b, world = _connected(vault)
    world["clock"] += 6
    run(b.send_key("UP"))
    assert world["connects"] == 1


def test_start_pairing_clears_a_stale_pending_pin(vault):
    b, world = make(vault)
    run(b.start_pairing())  # abandoned: Brian never typed the code
    world["pair_error"] = CannotConnect("x")
    with pytest.raises(DeviceUnreachable):
        run(b.start_pairing())
    with pytest.raises(NotPaired):
        run(b.finish_pairing("ab12cd"))
    assert vault.pinned() is None


def test_missing_key_file_is_not_paired(vault, tmp_path):
    vault.create_identity()
    (tmp_path / g.KEY_FILE).unlink()
    (tmp_path / g.KEY_FILE).mkdir()  # unreadable as a file: OSError on load
    with pytest.raises(NotPaired):
        vault.ssl_context()


def test_reconnect_rechecks_the_pin(vault):
    b, world = _connected(vault)
    _tv_closes_link(world)
    world["api_cert"] = OTHER_CERT
    with pytest.raises(CertificateChanged):
        run(b.send_key("OK"))
    assert world["sent"] == [("key", "KEYCODE_DPAD_CENTER")]


def test_send_raising_connection_closed_reconnects_next_time(vault):
    b, world = _connected(vault)
    world["remotes"][-1]._transport = None  # protocol gone
    run(b.send_key("OK"))
    assert world["connects"] == 2


def test_start_pairing_recovers_from_unsealable_key(vault, tmp_path):
    b, world = make(vault)
    pair(b)
    (tmp_path / g.KEY_PW_FILE).write_bytes(b"garbage")  # e.g. Windows profile restored, DPAPI key lost
    with pytest.raises(NotPaired):
        run(b.send_key("OK"))
    old_cert = (tmp_path / g.CERT_FILE).read_bytes()
    run(b.start_pairing())
    assert vault.pinned() is None and (tmp_path / g.CERT_FILE).read_bytes() != old_cert
    assert vault.identity_usable()
    assert run(b.finish_pairing("ab12cd")) == "Google TV paired."
    run(b.send_key("OK"))
    assert world["sent"][-1] == ("key", "KEYCODE_DPAD_CENTER")


@pytest.mark.parametrize("err,reason", [(CannotConnect("x"), "no answer"), (ConnectionClosed("x"), "connection closed")])
def test_pairing_unreachable(vault, err, reason):
    b, _ = make(vault, pair_error=err)
    with pytest.raises(DeviceUnreachable) as e:
        run(b.start_pairing())
    assert e.value.reason == reason


def test_tool_layer_reports_certificate_change(vault):
    """End to end through the MCP tool: the refusal reaches Claude as a clear message."""
    from mcp.server.mcpserver.exceptions import ToolError

    from tv_remote.config import Config, GoogleTvConfig
    from tv_remote.devices import FakeDiscovery
    from tv_remote.server import build_server

    b, world = make(vault)
    pair(b)
    world["api_cert"] = OTHER_CERT
    server = build_server(Config(None, GoogleTvConfig(HOST)), None, b, FakeDiscovery())
    with pytest.raises(ToolError, match="certificate has changed"):
        run(server.call_tool("tv_key", {"key": "OK"}))
