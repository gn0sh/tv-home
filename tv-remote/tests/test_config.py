import pytest

from tv_remote import config as cfgmod
from tv_remote.config import ConfigError, check_mac, check_private_host, load_config, parse_config

GOOD = '[lg]\nhost = "192.168.68.61"\nmac = "aa-bb-cc-dd-ee-ff"\n\n[googletv]\nhost = "192.168.68.81"\n'


def test_loads_from_env_override_dir(tmp_path, monkeypatch):
    (tmp_path / "config.toml").write_text(GOOD)
    monkeypatch.setattr(cfgmod.sys, "platform", "linux")
    monkeypatch.setenv(cfgmod.DIR_OVERRIDE_ENV, str(tmp_path))
    c = load_config()
    assert c.lg.host == "192.168.68.61" and c.lg.mac == "AA:BB:CC:DD:EE:FF"
    assert c.googletv.host == "192.168.68.81"


def test_windows_uses_appdata(tmp_path, monkeypatch):
    monkeypatch.setattr(cfgmod.sys, "platform", "win32")
    monkeypatch.setenv("APPDATA", str(tmp_path))
    monkeypatch.setenv(cfgmod.DIR_OVERRIDE_ENV, "/elsewhere")  # ignored on Windows
    assert cfgmod.config_dir() == tmp_path / "tv-remote"


def test_non_windows_without_override_errors(monkeypatch):
    monkeypatch.setattr(cfgmod.sys, "platform", "linux")
    monkeypatch.delenv(cfgmod.DIR_OVERRIDE_ENV, raising=False)
    with pytest.raises(ConfigError, match=cfgmod.DIR_OVERRIDE_ENV):
        cfgmod.config_dir()


def test_missing_file(tmp_path):
    with pytest.raises(ConfigError, match="No config file"):
        load_config(tmp_path / "config.toml")


def test_bad_toml(tmp_path):
    (tmp_path / "config.toml").write_text("[lg\n")
    with pytest.raises(ConfigError, match="not valid TOML"):
        load_config(tmp_path / "config.toml")


@pytest.mark.parametrize("host", ["10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.68.61"])
def test_private_hosts_ok(host):
    assert check_private_host(host, "x") == host


@pytest.mark.parametrize("host,bare", [("10.0.5.0/16", "10.0.5.0"), ("192.168.68.61/24", "192.168.68.61"),
                                       ("172.20.1.255/16", "172.20.1.255")])
def test_prefix_given_in_config(host, bare):
    assert check_private_host(host, "x") == bare


@pytest.mark.parametrize(
    "host",
    ["8.8.8.8", "172.32.0.1", "127.0.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "tv.local",
     "192.168.68.61.evil.com", "::1", "fe80::1", "", 3232252989,
     "192.168.68.0", "192.168.68.255", "10.0.0.0/8", "10.255.255.255/8", "172.16.0.0/16", "10.1.2.3/7",
     "192.168.1.5/31", "192.168.1.5/abc", "8.8.8.8/24"],
)
def test_non_private_hosts_refused(host):
    with pytest.raises(ConfigError):
        check_private_host(host, "x")


@pytest.mark.parametrize("mac", ["AA:BB:CC:DD:EE:FF", "a8:23:fe:01:02:03", "A8-23-FE-01-02-03"])
def test_mac_ok(mac):
    assert check_mac(mac) == mac.upper().replace("-", ":")


@pytest.mark.parametrize(
    "mac", ["AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:FF:00", "AA:BB-CC:DD:EE:FF", "GG:BB:CC:DD:EE:FF",
            "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "01:00:5E:00:00:01", "AABBCCDDEEFF", None],
)
def test_mac_refused(mac):
    with pytest.raises(ConfigError):
        check_mac(mac)


@pytest.mark.parametrize(
    "data,msg",
    [
        ({}, "neither"),
        ({"lg": {"host": "192.168.1.2"}}, "both host and mac"),
        ({"googletv": {}}, "needs host"),
        ({"lg": {"host": "192.168.1.2", "mac": "AA:BB:CC:DD:EE:FF", "key": "x"}}, "Unknown key"),
        ({"roku": {"host": "192.168.1.3"}}, "Unknown section"),
        ({"googletv": "192.168.1.3"}, "must be a table"),
        ({"googletv": {"host": "1.1.1.1"}}, "RFC 1918"),
    ],
)
def test_parse_errors(data, msg):
    with pytest.raises(ConfigError, match=msg):
        parse_config(data)


def test_single_device_ok():
    c = parse_config({"googletv": {"host": "192.168.68.81"}})
    assert c.lg is None and c.googletv.host == "192.168.68.81"
