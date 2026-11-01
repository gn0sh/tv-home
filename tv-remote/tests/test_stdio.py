"""The server speaks MCP over stdio and opens no listening socket (AC6)."""

import ast
import pathlib

SRC = pathlib.Path(__file__).resolve().parents[1] / "src" / "tv_remote"


def test_only_stdio_transport_is_used():
    text = (SRC / "server.py").read_text()
    assert "run_stdio_async" in text
    for banned in ("run_sse_async", "run_streamable_http", "streamable-http", '"sse"', "uvicorn"):
        assert banned not in text


def test_no_socket_listen_or_bind_in_our_code():
    for f in SRC.glob("*.py"):
        tree = ast.parse(f.read_text())
        for node in ast.walk(tree):
            if isinstance(node, ast.Attribute):
                assert node.attr not in {"bind", "listen", "start_server", "create_server"}, f
