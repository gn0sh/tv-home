# LG volume: options (M1c follow-up)

Problem: Claude cannot change the LG OLED BX's volume. HDMI-CEC POWER through the Google TV works, but the BX ignores
CEC volume/mute (tested 2026-11-01, see `tv-home.md` section 8, `tv_key` row). The built `lg_*` tools (C3) use wss on
3001 only, and the BX refused 3001 on 2026-10-31, so they stay unconfigured. No secrets were handled and the TV was not
contacted for this note.

## Facts
| Fact | Status |
|---|---|
| BX runs webOS 5 (2020); refused wss 3001 on 2026-10-31 | Verified by the team (status/arch doc). How it refused (connection refused vs TLS error) is not recorded |
| SSAP has two ports: ws 3000 (plaintext) and wss 3001 (TLS) | Verified [1][2][3] |
| LG firmware from about 2023 blocks ws 3000 and needs wss 3001 on some models; LG has not published which | Verified as reports, not by LG [1][2][3] |
| The 3001 certificate is LG's private PKI (leaf "LGE TV SSG", intermediate "LGE SSG Intermediate CA", root not sent), so it fails normal validation; pinning or TOFU is the usual answer | Verified [2][4] |
| LG's own forum says only Connect SDK is supported for external control | Verified [5] |
| Pairing: the first `register` shows an accept prompt on the TV; the TV then issues a client key that is reused on every later connection with no expiry | Verified by C3 design and libraries [1] |
| Broadlink RM4 mini costs about US$26, 2.4 GHz Wi-Fi, needs line of sight; set up through BroadLink's cloud app by default, local control via python-broadlink/Home Assistant | Verified [6] |
| A firmware update for the BX may add or change 3001 support | Inference (the ws-to-wss shift came by firmware [2]); unverified for the BX |
| The BX accepts ws 3000 today | Unverified; likely on pre-2023 firmware [2], and the first thing (a2) must check |
| A setting such as "LG Connect Apps" / mobile connection being off could explain the refusal | Inference, unverified |

## Options
| | (a1) SSAP wss 3001 | (a2) SSAP ws 3000 | (b) IR blaster | (c) eARC / optical audio device | (d) Do nothing |
|---|---|---|---|---|---|
| What Brian does | Check for a BX software update and the connect-apps setting, then accept one pairing prompt at the TV | Same pairing prompt; no update needed if 3000 is open | Buy and place a blaster in sight of the TV; learn or pick LG IR codes; BroadLink app/account for setup unless flashed | Buy a soundbar/AVR on the BX's eARC port (HDMI 2), or optical to a Wi-Fi speaker | Use the LG remote for volume |
| Cost | $0 | $0 | ~$26 Broadlink [6]; ~$10 ESP32 + IR LED (estimate, needs flashing) | $100-500+ (estimate) | $0 |
| Effort for us | Small: C3 is built and tested | Small: allow port 3000 in `lg.py`, new manifest | Medium: new driver, IR code set, a second LAN device to pin | Small if eARC (CEC volume then goes to the audio device, inference); medium for a Wi-Fi speaker API | None |
| LAN sniffing | Encrypted; pinned cert (TOFU at pairing) | **Client key and every command in cleartext**; anyone on the LAN who sniffs or ARP-spoofs gets the key | Broadlink LAN protocol uses a fixed default AES key in python-broadlink (inference, check); commands are replayable | eARC/CEC: none on the LAN. Wi-Fi speakers often have unauthenticated LAN APIs | None |
| Key theft (from Brian's PC) | Key is DPAPI-sealed; theft needs his Windows session | Same at rest, but theft is moot: it is on the wire | No long-lived TV secret; blaster may hold cloud creds | None | None |
| What an attacker gains | With the trimmed 8-permission key: power off, volume, input, app launch, read state. No settings, toasts or input injection, if the TV enforces the manifest | Same as a1, from anywhere on the LAN, replayable for as long as the key lives (no known revoke short of reset, unverified) | Any IR button: volume, power, input, menus (IR has no permissions at all). A cloud-linked blaster widens this to the BroadLink account | Nothing new | Nothing |
| Hard stops | none | none (reverses security finding `#5`, needs security sign-off) | **Spend money; account creation** | **Spend money** | none |

## Recommendation
1. **Free check first (a1).** Ask Brian to run the BX's software update and confirm the connect-apps setting is on,
   then retry `tv_pair` on 3001. If it now works, nothing else is built: the secure path already exists.
2. **If 3001 is still refused: (a2) ws 3000, scoped to volume only.** Register a *separate* client identity whose
   manifest is only `CONTROL_AUDIO` (volume, mute) and `READ_POWER_STATE`. Expose only `lg_volume` and `lg_mute` over
   3000; `lg_power`, `lg_input` and app launch stay wss-only and stay off. Then a sniffed key buys an attacker exactly
   what any IR remote in the room has: the volume. Log every 3000 connection; keep the 60 cap.
3. **Skip (b) and (c) unless Brian wants them for other reasons**: both cost money (hard stop 3), (b) adds a cloud
   account and a less constrained control surface than (a2), (c) is a hardware purchase for a software problem.
4. (d) stays the default until Brian answers.

Ask for Brian (default if no answer: do nothing, volume stays on the LG remote):
"Can Claude change the LG's volume over the home network without encryption, limited to volume and mute only? Reply
**yes** (recommended, after the free update check) or **no**." Before asking, he runs: LG remote > Settings > All
Settings > Support > Software Update > Check for updates (menu path is an inference for webOS 5; confirm on screen).

## Most serious problem with this recommendation
The volume-only scope rests on **the BX enforcing the SSAP permission manifest**, which C3 planned to test on the real
TV (C6) but never could. If webOS 5 ignores the manifest or grants by app rather than by permission, a key sniffed off
port 3000 is a permanent, replayable remote for the TV (power, input, launch, maybe input events), with no known way
to revoke it short of a reset. Second: (a2) reverses a recorded security decision (`#5`, ADR 0003) and opens a
plaintext path that later code could widen by accident.

## Security-engineer review must cover, before building (a2)
- Proof on Brian's BX, over wss if possible or in a lab-equivalent, that a call outside the 2-permission manifest
  fails (e.g. `ssap://system/turnOff`, input socket, toast). No proof, no build.
- The two identities cannot be confused: the 3000 key is stored and sealed separately, and code that sends on 3000
  can only load that key (a test that the 8-permission key is never sent on 3000).
- How to revoke a client key on webOS 5 (settings menu, re-pair, reset), recorded in the README.
- A response-tampering check: on 3000 the TV's replies are unauthenticated, so nothing read back over 3000 may drive
  a decision beyond the volume cap.
- ADR 0003 and finding `#5` amended with the reason, rather than silently diverging.
- The pairing prompt: a LAN attacker can also trigger prompts on 3000; Brian should accept only when he started one.

## Sources
1. hobbyquaker/lgtv2 docs: https://docsearch.algolia.com/mcp/docs/repo/hobbyquaker/lgtv2
2. aiopylgtv issue #65 (ws 3000 blocked by firmware, 3001 cert chain): https://github.com/bendavid/aiopylgtv/issues/65
3. Homey forum, LG webOS integration (Feb 2023): https://community.homey.app/t/lg-webos-better-integration/15007/445?page=23
4. ADR 0003 and `docs/reviews/m1c-c3-review.md` (TOFU pin on 3001)
5. LG webOS developer forum: https://forum.webostv.developer.lge.com/t/connecting-mobile-apps-to-lg-webos-tv/3082
6. Broadlink RM4 mini spec and price: https://smarthomecompared.com/ir-remote-controls/broadlink-rm4-mini
