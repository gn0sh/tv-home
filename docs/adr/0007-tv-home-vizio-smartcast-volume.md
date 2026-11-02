# ADR 0007: Vizio volume over the local SmartCast API

Status: accepted (frontend-engineer), 2026-11-02. Pairing on the real TV is still to be done with Brian.
Code: `tv-remote/src/tv_remote/vizio.py`, `tv-remote/src/tv_remote/vizio_pair.py`, `pcremote.py`, `lgpipe.py`.
Tests: `tv-remote/tests/test_vizio.py` (fake SmartCast HTTPS server), `tv-remote/tests/test_pcremote.py`.

## Context
The Vizio ignores HDMI-CEC volume from the Google TV, so the Vizio mode of the PC remote (ADR 0006) could not change
the volume. Vizio SmartCast TVs expose a local HTTPS API that can press volume keys and read the level once a client
is paired with a PIN shown on the TV.

## Decision
- **Our own small client, no new dependency.** pyvizio (MIT) was read as the protocol reference only; it pulls in
  requests, click, zeroconf, jsonpickle, tabulate and xmltodict. We use aiohttp, already a dependency. `uv.lock` is
  unchanged.
- **Protocol, as pyvizio documents it:** HTTPS on port 7345, with 9000 tried too (older firmware). Pairing is
  `PUT /pairing/start` (the TV shows a 4-digit PIN), then `PUT /pairing/pair` with the PIN, which returns the auth
  token, sent afterwards in the `AUTH` header. Volume keys go through `PUT /key_command/` with codeset 5 (0 down,
  1 up, 2 mute off, 3 mute on); the level is read from `/menu_native/dynamic/tv_settings/audio/volume` and mute from
  `.../audio/mute`. Only these paths are allowed (`ALLOWED_PATHS`); nothing else can be requested.
- **Trust on first use.** The TV's certificate is self-signed. Pairing records its SHA-256 fingerprint and port in
  `vizio-tv-pin.json`; every later connection uses tlspin's pinned context, and a changed certificate is refused
  before any request is sent ("pair again"). The PIN is sent pinned to the fingerprint seen at `/pairing/start`, so
  another device cannot step in between.
- **Token sealed** with DPAPI into `%APPDATA%\tv-remote\vizio-auth.sealed`, as the LG key is. It is never printed or
  logged; a test checks no output contains it. `tv-remote-vizio-pair --forget` removes the token and the pin.
- **Same 60 cap as the LG.** `VizioRemote` has the LG backend's shape, so `lgpipe.handle` runs it under the same
  per-TV lock with read-before-step and clamp-back on overshoot (`name="Vizio"`). If the Vizio does not report its
  level, `unknown_up="step"` sends one step per tap instead of refusing; the page then stops Vol+ from repeating, and
  Python keeps a 400 ms backstop between such ups. At most 2 such ups in a row are sent (`UNKNOWN_UPS_MAX`), then up
  is refused until a level is read again (security finding vizio-unknown-level-uncapped). An unknown mute state sends
  the toggle key.
- **What is believed from the TV:** no redirect is followed (a 3xx is refused, so `AUTH` never goes elsewhere);
  bodies are read to EOF under 256 KB; VALUE and HASHVAL are used only from an item whose CNAME is `volume` or
  `mute`; the token is kept only if it matches `[A-Za-z0-9._-]{1,128}`; TV text is stripped before it is printed.
- **Finding the TV:** `[vizio] host` in config.toml, `--host` (what the setup guide recommends), or an SSDP M-SEARCH for DIAL devices, kept only when
  the device description (http, same private address, no proxy, 2 s, 64 KB) names VIZIO as the manufacturer. A
  successful pairing appends `[vizio]` to config.toml when it is missing and never rewrites an existing one.
  SSDP answers are unauthenticated (any LAN device can answer, or relay), so a found address is shown and Brian
  confirms it against the TV's own network screen, default no; redirects from the description URL are not followed.
- `--forget` deletes the token and pin on this PC (no DPAPI needed). It does not revoke the token on the TV; the guide
  says where to remove the PC on the Vizio.
- **Not paired:** the volume card says "Pair the Vizio: run tv-remote-vizio-pair"; Vol+ is not sent; Vol- and Mute
  keep the adb (HDMI-CEC) fallback.

## Risks and unknowns
- Unverified on Brian's TV. Request and reply shapes follow pyvizio 0.1.64; firmware may differ (port, mute codes,
  volume path). The fake server mirrors pyvizio, so a mismatch will only show up at pairing.
- **Stale read (cap-stale-read):** the cap reads the level, then steps. If the level changes in between by another
  route (the Vizio's own remote, or the TV still applying an earlier step), one step can land above 60. The level is
  read after each up and clamped back to 60 when it is over, so the overshoot lasts one round trip, at most a step or
  two. The LG has the same window (ADR 0003).
- A firmware update that changes the certificate makes the remote refuse the TV until it is paired again, by design.

## Alternatives
- pyvizio as a dependency: more code and dependencies than the four calls we need.
- IR blaster or CEC fixes: no hardware, and the TV ignores CEC volume.
