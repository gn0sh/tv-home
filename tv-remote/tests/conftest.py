import asyncio

import pytest

from tv_remote.config import Config, GoogleTvConfig, LgConfig
from tv_remote.devices import FakeDiscovery, FakeGoogleTv, FakeLg
from tv_remote.server import build_server


class Harness:
    def __init__(self, links=None):
        self.lg = FakeLg()
        self.gtv = FakeGoogleTv()
        self.discovery = FakeDiscovery()
        cfg = Config(LgConfig(self.lg.host, "AA:BB:CC:DD:EE:FF"), GoogleTvConfig(self.gtv.host))
        kwargs = {"link_builder": links} if links else {}
        self.server = build_server(cfg, self.lg, self.gtv, self.discovery, **kwargs)

    def call(self, name, **args):
        return asyncio.run(self.server.call_tool(name, args))

    def text(self, name, **args):
        res = self.call(name, **args)
        return " ".join(c.text for c in res.content if getattr(c, "text", None))

    def tools(self):
        return {t.name: t for t in asyncio.run(self.server.list_tools())}


@pytest.fixture
def h():
    return Harness()
