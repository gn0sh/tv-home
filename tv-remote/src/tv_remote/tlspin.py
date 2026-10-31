"""TLS certificate pinning checked inside the handshake (findings #5, #6).

Both TVs use self-signed certificates, so normal verification is off and trust comes from a SHA-256 pin of the TV's
certificate. `SSLContext.sslobject_class` lets the check run as the handshake completes, so a wrong certificate fails
the connection before any protocol message (registration, client key, configure) is sent. asyncio and aiohttp both
create their TLS objects through the context, so the hook applies to both libraries.
"""

from __future__ import annotations

import hashlib
import ssl
from typing import Any


def fingerprint(der: bytes) -> str:
    return hashlib.sha256(der).hexdigest()


def peer_fingerprint(transport: Any) -> str | None:
    ssl_obj = transport.get_extra_info("ssl_object") if transport is not None else None
    der = ssl_obj.getpeercert(True) if ssl_obj is not None else None
    return fingerprint(der) if der else None


class PinMismatch(ssl.SSLCertVerificationError):
    pass


class _PinnedSSLObject(ssl.SSLObject):
    """Checks the TV's certificate as soon as the handshake completes, before any protocol message is sent."""

    def do_handshake(self) -> None:
        super().do_handshake()  # raises SSLWantReadError until the handshake is done
        ctx = self.context
        der = self.getpeercert(True)
        ctx.tv_seen_fingerprint = fingerprint(der) if der else None
        pin = getattr(ctx, "tv_pin", None)
        if pin is not None and ctx.tv_seen_fingerprint != pin:
            ctx.tv_pin_mismatch = True
            raise PinMismatch("TV certificate does not match the pinned fingerprint")


class TvSSLContext(ssl.SSLContext):
    tv_pin: str | None = None
    tv_pin_mismatch: bool = False
    tv_seen_fingerprint: str | None = None


def pinned_client_context(pin: str | None) -> TvSSLContext:
    """Client context for a self-signed TV. With `pin`, the handshake fails unless the TV presents that certificate.
    Without it (first pairing only), the certificate seen is recorded in `tv_seen_fingerprint` so it can be pinned."""
    ctx = TvSSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.sslobject_class = _PinnedSSLObject
    ctx.tv_pin = pin
    return ctx
