"""Vizio SmartCast backend (ADR 0007) against a fake SmartCast HTTPS server with a self-signed certificate."""

import asyncio
import json
import logging
import socket
import ssl

import pytest
from aiohttp import web
from androidtvremote2.certificate_generator import generate_selfsigned_cert

from tv_remote import vizio as vz
from tv_remote import vizio_pair
from tv_remote.devices import CertificateChanged, DeviceRefused, NotPaired
from tv_remote.lgpipe import handle
from tv_remote.secretstore import FakeProtector
from tv_remote.tlspin import fingerprint
from tv_remote.vizio import VizioRemote, VizioVault

HOST = "127.0.0.1"
PIN = "1357"
TOKEN = "Zx9-SECRET-TOKEN-4f2a"


def _cert(tmp_path, name):
    cert_pem, key_pem = generate_selfsigned_cert(name)
    d = tmp_path / f"tv-{name}"
    d.mkdir()
    (d / "c.pem").write_bytes(cert_pem)
    (d / "k.pem").write_bytes(key_pem)  # the fake TV's own throwaway key
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(d / "c.pem", d / "k.pem")
    return ctx, fingerprint(ssl.PEM_cert_to_DER_cert(cert_pem.decode()))


def ok(**extra):
    return web.json_response({"STATUS": {"RESULT": "SUCCESS", "DETAIL": "Success"}, **extra})


def fail(result):
    return web.json_response({"STATUS": {"RESULT": result, "DETAIL": result}})


class FakeVizio:
    """SmartCast over TLS: pairing with a PIN, AUTH-protected volume keys and the audio/volume setting."""

    def __init__(self, ssl_ctx, volume=20, port=0):
        self.ssl_ctx, self.volume, self.muted, self.port = ssl_ctx, volume, False, port
        self.requests, self.keys = [], []
        self.report_level, self.delay, self.step = True, 0.0, 1
        self.pair_started = None
        self.highest = volume

    async def start(self):
        app = web.Application()
        app.router.add_route("*", "/{tail:.*}", self.route)
        self.runner = web.AppRunner(app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, HOST, self.port, ssl_context=self.ssl_ctx)
        await site.start()
        self.port = site._server.sockets[0].getsockname()[1]

    async def stop(self):
        await self.runner.cleanup()

    def authed(self, request):
        return request.headers.get("AUTH") == TOKEN

    async def route(self, request):
        body = json.loads(await request.text() or "null")
        self.requests.append((request.method, request.path, dict(request.headers), body))
        if self.delay:
            await asyncio.sleep(self.delay)
        path, method = request.path, request.method
        if (method, path) == ("PUT", "/pairing/start"):
            self.pair_started = body
            return ok(ITEM={"CHALLENGE_TYPE": 1, "PAIRING_REQ_TOKEN": 424242})
        if (method, path) == ("PUT", "/pairing/pair"):
            if body.get("PAIRING_REQ_TOKEN") != 424242 or body.get("RESPONSE_VALUE") != PIN:
                return fail("PAIRING_DENIED")
            return ok(ITEM={"AUTH_TOKEN": TOKEN})
        if (method, path) == ("PUT", "/pairing/cancel"):
            return ok()
        if not self.authed(request):
            return fail("BLOCKED")
        if (method, path) == ("PUT", "/key_command/"):
            for k in body["KEYLIST"]:
                assert k["CODESET"] == 5
                self.keys.append(k["CODE"])
                if k["CODE"] == 1:
                    self.volume = min(100, self.volume + self.step)
                    self.highest = max(self.highest, self.volume)
                elif k["CODE"] == 0:
                    self.volume = max(0, self.volume - 1)
                elif k["CODE"] in (2, 3):
                    self.muted = k["CODE"] == 3
            return ok()
        if path == "/menu_native/dynamic/tv_settings/audio/volume":
            if method == "PUT":
                if body.get("REQUEST") != "MODIFY" or body.get("HASHVAL") != 77:
                    return fail("INVALID_PARAMETER")
                self.volume = body["VALUE"]
                return ok()
            item = {"HASHVAL": 77, "CNAME": "volume", "TYPE": "T_VALUE_ABS_V1", "NAME": "Volume"}
            if self.report_level:
                item["VALUE"] = self.volume
            return ok(ITEMS=[item])
        if path == "/menu_native/dynamic/tv_settings/audio/mute":
            return ok(ITEMS=[{"HASHVAL": 78, "VALUE": "On" if self.muted else "Off"}])
        return web.Response(status=404)


@pytest.fixture
def env(tmp_path):
    ctx, fp = _cert(tmp_path, "vizio")
    vault = VizioVault(tmp_path / "state", FakeProtector())
    return tmp_path, ctx, fp, vault


def run_with(env, body, *, paired=True, **tv_kw):
    tmp_path, ctx, fp, vault = env

    async def go():
        tv = FakeVizio(ctx)
        for k, v in tv_kw.items():
            setattr(tv, k, v)
        await tv.start()
        if paired:
            vault.save(TOKEN, tv.port, fp)
        remote = VizioRemote(HOST, vault, ports=(tv.port,))
        try:
            return await body(tv, remote)
        finally:
            await remote.close()
            await tv.stop()

    return asyncio.run(go())


def _free_port():
    s = socket.socket()
    s.bind((HOST, 0))
    port = s.getsockname()[1]
    s.close()
    return port


# --- pairing ----------------------------------------------------------------------------------------------------------

def test_pairing_seals_the_token_and_pins_port_and_cert(env):
    tmp_path, ctx, fp, vault = env

    async def body(tv, remote):
        assert "4-digit PIN" in await remote.start_pairing()
        assert tv.pair_started == {"DEVICE_ID": vz.DEVICE_ID, "DEVICE_NAME": vz.DEVICE_NAME}
        assert await remote.finish_pairing(PIN) == "Vizio paired."
        return tv.port

    port = run_with(env, body, paired=False)
    assert vault.token() == TOKEN and vault.pinned() == (port, fp)
    sealed = (vault.dir / vz.TOKEN_FILE).read_bytes()
    assert TOKEN.encode() not in sealed and sealed.startswith(FakeProtector.TAG)
    assert TOKEN not in (vault.dir / vz.PIN_FILE).read_text()


def test_wrong_pin_stores_nothing(env):
    tmp_path, ctx, fp, vault = env

    async def body(tv, remote):
        await remote.start_pairing()
        return await remote.finish_pairing("0000")

    assert "did not accept that PIN" in run_with(env, body, paired=False)
    assert not vault.paired() and vault.token() is None


def test_malformed_pin_is_not_sent(env):
    async def body(tv, remote):
        await remote.start_pairing()
        before = len(tv.requests)
        msg = await remote.finish_pairing("12a4")
        return msg, len(tv.requests) - before

    msg, sent = run_with(env, body, paired=False)
    assert "4 digits" in msg and sent == 0


def test_port_probe_tries_7345_then_9000(env):
    tmp_path, ctx, fp, vault = env

    async def go():
        tv = FakeVizio(ctx)
        await tv.start()
        remote = VizioRemote(HOST, vault, ports=(_free_port(), tv.port))
        try:
            await remote.start_pairing()
            assert await remote.finish_pairing(PIN) == "Vizio paired."
        finally:
            await remote.close()
            await tv.stop()
        return tv.port

    port = asyncio.run(go())
    assert vault.pinned() == (port, fp)
    assert vz.PORTS == (7345, 9000)


def test_pin_goes_only_to_the_tv_that_showed_it(env):
    """A different certificate between start and finish: the PIN is not sent and nothing is stored."""
    tmp_path, ctx, fp, vault = env
    ctx2, fp2 = _cert(tmp_path, "imposter")

    async def go():
        tv = FakeVizio(ctx)
        await tv.start()
        remote = VizioRemote(HOST, vault, ports=(tv.port,))
        await remote.start_pairing()
        port = tv.port
        await tv.stop()
        imposter = FakeVizio(ctx2, port=port)
        await imposter.start()
        try:
            with pytest.raises(CertificateChanged):
                await remote.finish_pairing(PIN)
            return imposter.requests
        finally:
            await remote.close()
            await imposter.stop()

    assert asyncio.run(go()) == []
    assert not vault.paired()


# --- certificate pin -------------------------------------------------------------------------------------------------

def test_changed_certificate_is_refused_before_the_token_is_sent(env):
    tmp_path, ctx, fp, vault = env
    ctx2, fp2 = _cert(tmp_path, "other")

    async def go():
        tv = FakeVizio(ctx2)  # a different certificate than the pinned one
        await tv.start()
        vault.save(TOKEN, tv.port, fp)
        remote = VizioRemote(HOST, vault, ports=(tv.port,))
        try:
            reply = await handle(remote, "up", name="Vizio", unknown_up="step")
            with pytest.raises(CertificateChanged):
                await remote.status()
            return reply, tv.requests
        finally:
            await remote.close()
            await tv.stop()

    reply, requests = asyncio.run(go())
    assert reply == "error the Vizio's certificate changed; pair it again"
    assert requests == []  # the TLS handshake failed: no request, no AUTH header


# --- volume through lgpipe.handle ------------------------------------------------------------------------------------

def vizio(remote, cmd):
    return handle(remote, cmd, name="Vizio", unknown_up="step")


def test_up_down_and_mute(env):
    async def body(tv, remote):
        out = [await vizio(remote, "up"), await vizio(remote, "down"), await vizio(remote, "mute"),
               await vizio(remote, "mute")]
        return out, tv.keys

    out, keys = run_with(env, body, volume=20)
    assert out == ["ok 21", "ok 20", "ok muted", "ok unmuted"]
    assert keys == [1, 0, 3, 2]


def test_cap_60_at_and_above(env):
    async def body(tv, remote):
        at = await vizio(remote, "up")
        tv.volume = 75
        above = await vizio(remote, "up")
        return at, above, tv.keys, tv.volume

    at, above, keys, volume = run_with(env, body, volume=60)
    assert at == "ok 60" and above == "ok 60" and keys == [] and volume == 60


def test_two_concurrent_ups_at_59_never_pass_60(env):
    async def body(tv, remote):
        tv.delay = 0.02
        replies = await asyncio.gather(vizio(remote, "up"), vizio(remote, "up"), vizio(remote, "up"))
        return replies, tv.highest, tv.volume

    replies, highest, volume = run_with(env, body, volume=59)
    assert highest <= 60 and volume == 60
    assert sorted(replies) == ["ok 60"] * 3


def test_step_that_overshoots_is_clamped(env):
    async def body(tv, remote):
        tv.step = 4
        return await vizio(remote, "up"), tv.volume

    assert run_with(env, body, volume=58) == ("ok 60", 60)


def test_unknown_level_takes_one_step_for_the_vizio_but_not_the_lg_rule(env):
    async def body(tv, remote):
        tv.report_level = False
        step = await vizio(remote, "up")
        refuse = await handle(remote, "up", name="Vizio")  # the LG rule: refuse
        return step, refuse, tv.keys

    step, refuse, keys = run_with(env, body, volume=30)
    assert step == "ok -" and keys == [1]
    assert refuse == "error the Vizio did not report its volume"


def test_not_paired(env):
    async def body(tv, remote):
        return await vizio(remote, "up"), tv.requests

    reply, requests = run_with(env, body, paired=False)
    assert reply == "error the Vizio is not paired" and requests == []


def test_unreachable(env):
    tmp_path, ctx, fp, vault = env
    vault.save(TOKEN, 7345, fp)
    remote = VizioRemote(HOST, vault, ports=(_free_port(),))

    async def go():
        try:
            return await vizio(remote, "up")
        finally:
            await remote.close()

    # the pinned port (7345) is used after pairing; nothing listens on 127.0.0.1:7345 here
    assert asyncio.run(go()) == "error the Vizio did not answer"


def test_only_allowlisted_paths(env):
    async def body(tv, remote):
        with pytest.raises(DeviceRefused):
            await remote._authed("PUT", "/state/device/power_mode")
        with pytest.raises(DeviceRefused):
            await remote._authed("PUT", "/app/launch")
        return tv.requests

    assert run_with(env, body) == []


def test_only_the_volume_codeset_is_sent():
    assert vz.CODESET_VOLUME == 5 and set(vz.KEYS.values()) <= {0, 1, 2, 3, 4}


def test_token_never_appears_in_output(env, caplog, capsys):
    """Replies, exception text, logs and printed output never carry the token."""
    tmp_path, ctx, fp, vault = env
    seen = []

    async def body(tv, remote):
        await remote.start_pairing()
        seen.append(await remote.finish_pairing(PIN))
        seen.append(await vizio(remote, "up"))
        tv.report_level = False
        seen.append(await vizio(remote, "up"))
        try:
            await remote._authed("GET", "/nope")
        except Exception as exc:  # noqa: BLE001
            seen.append(str(exc))
        return None

    with caplog.at_level(logging.DEBUG):
        run_with(env, body, paired=False)
    text = " ".join(seen) + caplog.text + capsys.readouterr().out
    assert TOKEN not in text and "SECRET" not in text


# --- the pairing command ---------------------------------------------------------------------------------------------

class FakePairRemote:
    def __init__(self, answer="Vizio paired."):
        self.answer, self.calls = answer, []

    async def start_pairing(self):
        self.calls.append("start")
        return "The Vizio should now show a 4-digit PIN (port 7345)."

    async def finish_pairing(self, pin):
        self.calls.append(("finish", pin))
        return self.answer

    async def cancel_pairing(self):
        self.calls.append("cancel")

    async def close(self):
        pass


def cli(tmp_path, monkeypatch, argv, answers, remote=None, found=()):
    monkeypatch.setenv("TV_REMOTE_CONFIG_DIR", str(tmp_path / "cfg"))
    answers = iter(answers)
    remote = remote or FakePairRemote()
    vault = VizioVault(tmp_path / "cfg", FakeProtector())
    code = vizio_pair.main(argv, ask=lambda prompt: next(answers), finder=lambda: list(found),
                           remote_factory=lambda host, v: remote, vault_factory=lambda folder: vault)
    return code, remote, vault


def test_cli_pairs_with_a_typed_ip_and_adds_it_to_config(tmp_path, monkeypatch, capsys):
    code, remote, vault = cli(tmp_path, monkeypatch, ["--host", "192.168.68.90"], ["1357"])
    out = capsys.readouterr().out
    assert code == 0 and remote.calls == ["start", ("finish", "1357")]
    assert "Vizio paired." in out
    cfg = (tmp_path / "cfg" / "config.toml").read_text()
    assert '[vizio]\nhost = "192.168.68.90"' in cfg


def test_cli_keeps_an_existing_config(tmp_path, monkeypatch, capsys):
    (tmp_path / "cfg").mkdir()
    (tmp_path / "cfg" / "config.toml").write_text('[lg]\nhost = "192.168.68.61"\nmac = "A8:23:FE:01:02:03"\n')
    code, remote, vault = cli(tmp_path, monkeypatch, [], ["y", "1357"], found=[("192.168.68.90", "Living Room")])
    cfg = (tmp_path / "cfg" / "config.toml").read_text()
    assert code == 0 and cfg.startswith('[lg]\nhost = "192.168.68.61"') and 'host = "192.168.68.90"' in cfg


def test_cli_refuses_a_public_address(tmp_path, monkeypatch, capsys):
    code, remote, vault = cli(tmp_path, monkeypatch, ["--host", "8.8.8.8"], [])
    assert code == 2 and remote.calls == []


def test_cli_wrong_pin_cancels(tmp_path, monkeypatch, capsys):
    remote = FakePairRemote("The Vizio did not accept that PIN. Run tv-remote-vizio-pair again for a new PIN.")
    code, remote, vault = cli(tmp_path, monkeypatch, ["--host", "192.168.68.90"], ["0000"], remote=remote)
    assert code == 1 and remote.calls[-1] == "cancel"
    assert not (tmp_path / "cfg" / "config.toml").exists()


def test_cli_forget(tmp_path, monkeypatch, capsys):
    (tmp_path / "cfg").mkdir()
    VizioVault(tmp_path / "cfg", FakeProtector()).save(TOKEN, 7345, "a" * 64)
    code, remote, vault = cli(tmp_path, monkeypatch, ["--forget"], [])
    out = capsys.readouterr().out
    assert code == 0 and not vault.paired() and "vizio-auth.sealed" in out and TOKEN not in out


def test_ssdp_parsing_and_vizio_check():
    pkt = b"HTTP/1.1 200 OK\r\nLOCATION: http://192.168.68.90:8008/ssdp/device-desc.xml\r\nST: x\r\n\r\n"
    assert vizio_pair.parse_ssdp(pkt) == "http://192.168.68.90:8008/ssdp/device-desc.xml"

    class Resp:
        def __init__(self, body):
            self.body = body

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self, n):
            return self.body[:n]

    class Opener:
        def __init__(self, body):
            self.body, self.urls = body, []

        def open(self, url, timeout):
            self.urls.append(url)
            return Resp(self.body)

    vizio_desc = b"<root><device><friendlyName>Living Room; \"TV\"</friendlyName>" \
                 b"<manufacturer>VIZIO</manufacturer></device></root>"
    other = b"<root><device><friendlyName>Den</friendlyName><manufacturer>Google Inc.</manufacturer></device></root>"
    loc = "http://192.168.68.90:8008/ssdp/device-desc.xml"
    assert vizio_pair.describe("192.168.68.90", loc, Opener(vizio_desc)) == "Living Room TV"
    assert vizio_pair.describe("192.168.68.90", loc, Opener(other)) is None
    # only the address that answered, only private, only http
    op = Opener(vizio_desc)
    assert vizio_pair.describe("192.168.68.91", loc, op) is None and op.urls == []
    assert vizio_pair.describe("8.8.8.8", "http://8.8.8.8/x", op) is None
    assert vizio_pair.describe("192.168.68.90", "https://192.168.68.90/x", op) is None


def test_ssdp_discover_with_a_fake_socket():
    class Sock:
        def __init__(self):
            self.sent, self.replies = [], [
                (b"HTTP/1.1 200 OK\r\nLOCATION: http://192.168.68.90:8008/d.xml\r\n\r\n", ("192.168.68.90", 1900)),
                (b"HTTP/1.1 200 OK\r\nLOCATION: http://8.8.8.8/d.xml\r\n\r\n", ("8.8.8.8", 1900)),
            ]

        def setsockopt(self, *a):
            pass

        def settimeout(self, t):
            pass

        def sendto(self, data, addr):
            self.sent.append((data, addr))

        def recvfrom(self, n):
            if self.replies:
                return self.replies.pop(0)
            raise socket.timeout()

        def close(self):
            pass

    sock = Sock()
    found = vizio_pair.discover(wait=0.3, sock_factory=lambda: sock,
                                describer=lambda ip, loc: "Living Room" if ip == "192.168.68.90" else "x")
    assert found == [("192.168.68.90", "Living Room")]
    assert sock.sent[0][1] == ("239.255.255.250", 1900) and b"M-SEARCH" in sock.sent[0][0]
