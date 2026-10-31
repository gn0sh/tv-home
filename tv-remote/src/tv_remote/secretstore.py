"""Encryption at rest for pairing secrets (findings #5, #6).

On Windows secrets are sealed with DPAPI for the current user (CryptProtectData / CryptUnprotectData via ctypes).
There is no plaintext fallback: on other platforms the real server refuses to store keys, and tests inject a fake.
"""

from __future__ import annotations

import sys
from typing import Protocol

# Fixed, public entropy: it only keeps other apps' DPAPI blobs and ours apart. It is not a secret, so any program
# running as Brian can still unseal this. DPAPI protects against other users and offline copies of the disk.
_ENTROPY = b"tv-remote pairing secret v1"


class SecretStoreError(Exception):
    pass


class Protector(Protocol):
    def protect(self, data: bytes) -> bytes: ...
    def unprotect(self, blob: bytes) -> bytes: ...


class DpapiProtector:
    """Windows DPAPI, current-user scope, no UI."""

    CRYPTPROTECT_UI_FORBIDDEN = 0x01

    def __init__(self) -> None:
        if sys.platform != "win32":
            raise SecretStoreError("DPAPI is only available on Windows.")
        import ctypes
        from ctypes import wintypes

        class DATA_BLOB(ctypes.Structure):
            _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

        self._ctypes, self._blob_t = ctypes, DATA_BLOB
        self._crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        p = ctypes.POINTER(DATA_BLOB)
        for fn in (self._crypt32.CryptProtectData, self._crypt32.CryptUnprotectData):
            fn.restype = wintypes.BOOL
        self._crypt32.CryptProtectData.argtypes = [
            p, wintypes.LPCWSTR, p, ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, p]
        self._crypt32.CryptUnprotectData.argtypes = [
            p, ctypes.POINTER(wintypes.LPWSTR), p, ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, p]
        self._kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        self._kernel32.LocalFree.restype = ctypes.c_void_p

    def _in(self, data: bytes):
        ct = self._ctypes
        buf = ct.create_string_buffer(data, len(data))
        return self._blob_t(len(data), ct.cast(buf, ct.POINTER(ct.c_char))), buf

    def _out(self, blob) -> bytes:
        try:
            return self._ctypes.string_at(blob.pbData, blob.cbData)
        finally:
            self._kernel32.LocalFree(self._ctypes.cast(blob.pbData, self._ctypes.c_void_p))

    def protect(self, data: bytes) -> bytes:
        ct = self._ctypes
        inp, _b1 = self._in(data)
        ent, _b2 = self._in(_ENTROPY)
        out = self._blob_t()
        ok = self._crypt32.CryptProtectData(
            ct.byref(inp), "tv-remote", ct.byref(ent), None, None, self.CRYPTPROTECT_UI_FORBIDDEN, ct.byref(out))
        if not ok:
            raise SecretStoreError(f"CryptProtectData failed (Windows error {ct.get_last_error()}).")
        return self._out(out)

    def unprotect(self, blob: bytes) -> bytes:
        ct = self._ctypes
        inp, _b1 = self._in(blob)
        ent, _b2 = self._in(_ENTROPY)
        out = self._blob_t()
        ok = self._crypt32.CryptUnprotectData(
            ct.byref(inp), None, ct.byref(ent), None, None, self.CRYPTPROTECT_UI_FORBIDDEN, ct.byref(out))
        if not ok:
            raise SecretStoreError(
                f"CryptUnprotectData failed (Windows error {ct.get_last_error()}); pair the TV again.")
        return self._out(out)


def default_protector() -> Protector:
    if sys.platform == "win32":
        return DpapiProtector()
    raise SecretStoreError("No secure key storage on this platform; tv-remote pairing runs on Windows only.")


class FakeProtector:
    """Tests only: reversible, tagged, and visibly not plaintext. Never used by the real server."""

    TAG = b"FAKE-SEALED:"

    def protect(self, data: bytes) -> bytes:
        return self.TAG + bytes(b ^ 0x5A for b in data)

    def unprotect(self, blob: bytes) -> bytes:
        if not blob.startswith(self.TAG):
            raise SecretStoreError("not a sealed blob")
        return bytes(b ^ 0x5A for b in blob[len(self.TAG):])
