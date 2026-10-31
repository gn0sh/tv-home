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


class FakeTransport:
    def __init__(self, der):
        self.ssl = FakeSSL(der)

    def get_extra_info(self, name):
        return self.ssl if name == "ssl_object" else None


class FakeRemote:
    """Stands in for SealedKeyRemote: same methods the backend uses, scripted outcomes."""

    def __init__(self, world, vault, host):
        self.world, self.vault, self.host = world, vault, host
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
        err = self.world.get("connect_error")
        if err == "hang":
            await asyncio.sleep(60)
        if err:
            raise err
        self._transport = FakeTransport(self.world.get("api_cert", TV_CERT))

    def api_transport(self):
        return getattr(self, "_transport", None)

    async def async_start_pairing(self):
        err = self.world.get("pair_error")
        if err:
            raise err
        self._pair_transport = FakeTransport(self.world.get("pair_cert", TV_CERT))

    def pairing_transport(self):
        return getattr(self, "_pair_transport", None)

    async def async_finish_pairing(self, code):
        if code.lower() != self.world.get("code", "ab12cd"):
            raise InvalidAuth("bad code")
        if self.world.get("finish_error"):
            raise self.world["finish_error"]

    def send_key_command(self, code):
        if self.world.get("closed"):
            raise ConnectionClosed("gone")
        self.sent.append(("key", code))

    def send_launch_app_command(self, link):
        if self.world.get("closed"):
            raise ConnectionClosed("gone")
        self.sent.append(("launch", link))

    def add_current_app_updated_callback(self, cb):
        self.callbacks.append(cb)
        if self.world.get("late_app"):
            self.current_app = self.world["late_app"]
            asyncio.get_running_loop().call_soon(cb, self.current_app)

    def remove_current_app_updated_callback(self, cb):
        self.callbacks.remove(cb)

    def disconnect(self):
        self.world["disconnects"] = self.world.get("disconnects", 0) + 1


@pytest.fixture
def vault(tmp_path):
    return KeyVault(tmp_path, FakeProtector())


def make(vault, **world):
    return GoogleTvRemote(HOST, vault, lambda v, h: FakeRemote(world, v, h)), world


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
        assert b"PRIVATE KEY" not in f.read_bytes(), f
    sealed = (tmp_path / g.KEY_FILE).read_bytes()
    assert sealed.startswith(FakeProtector.TAG)
    assert b"BEGIN CERTIFICATE" in (tmp_path / g.CERT_FILE).read_bytes()


def test_ssl_context_loads_and_leaves_no_temp_key(vault, tmp_path):
    vault.create_identity()
    before = set(tmp_path.iterdir())
    ctx = vault.ssl_context()
    assert isinstance(ctx, ssl.SSLContext)
    assert set(tmp_path.iterdir()) == before


def test_temp_key_is_password_encrypted(vault, tmp_path, monkeypatch):
    vault.create_identity()
    seen = {}
    real = ssl.SSLContext.load_cert_chain

    def spy(self, certfile, keyfile=None, password=None):
        seen["data"] = open(keyfile, "rb").read()
        seen["password"] = password
        return real(self, certfile, keyfile, password)

    monkeypatch.setattr(ssl.SSLContext, "load_cert_chain", spy)
    vault.ssl_context()
    assert b"BEGIN ENCRYPTED PRIVATE KEY" in seen["data"] and len(seen["password"]) == 32


def test_unsealable_key_means_not_paired(tmp_path):
    KeyVault(tmp_path, FakeProtector()).create_identity()
    (tmp_path / g.KEY_FILE).write_bytes(b"garbage")
    with pytest.raises(NotPaired):
        KeyVault(tmp_path, FakeProtector()).ssl_context()


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
    assert b"PRIVATE KEY" not in (tmp_path / g.KEY_FILE).read_bytes()


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


@pytest.mark.parametrize("uri", ["market://details?id=x", "intent://x", "http://netflix.com/x", "https://a b"])
def test_open_link_only_sends_https(vault, uri):
    b, world = make(vault)
    pair(b)
    with pytest.raises(ValueError):
        run(b.open_link(uri))
    assert world["sent"] == []


@pytest.mark.parametrize("pkg", ["market://launch?id=x", "https://x.y", "Com.Evil", "noperiod", "a.b c"])
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


def test_dropped_connection_while_sending_reconnects_next_time(vault):
    b, world = make(vault)
    pair(b)
    run(b.send_key("OK"))
    world["closed"] = True
    with pytest.raises(DeviceUnreachable) as e:
        run(b.send_key("OK"))
    assert e.value.reason == "connection closed"
    world["closed"] = False
    n = len(world["remotes"])
    run(b.send_key("OK"))
    assert len(world["remotes"]) == n + 1


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
