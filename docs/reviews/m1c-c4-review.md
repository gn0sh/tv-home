# C4 review (aa46f7d..0c55634): code + security (opus/high): REQUEST CHANGES
156 tests. Key sealed before write, DPAPI ctypes correct, pin after pairing, fixed error reasons.
Most serious: HIGH `atv-stale-connection` googletv.py:205-208, 234-239: _connected only reset in _drop; androidtvremote2 0.3.2 silently drops sends on a closed link (base.py:85-87) and closes idle links after 16 s, so after standby/Wi-Fi blip/idle every command "succeeds" and sends nothing. Fake hides it. Fix: treat transport None/closing as disconnected and reconnect (re-checks pin); make fake drop silently.
MEDIUM `atv-unseal-dead-end` googletv.py:103-105,159-161,276: unseal failure makes tv_pair unrecoverable; regenerate identity in start_pairing.
LOW `atv-pin-port-assumption` (6467 vs 6466; add on-device check to C6), `atv-pin-after-start` (check right after TLS handshake), `atv-pairing-mitm-claim` (1/256 per try; fix docstring).
Nits: `atv-temp-key` (option: store encrypted PKCS#8 permanently, DPAPI-seal only its password), `dpapi-entropy-claim`, `pkg-regex-newline` (fullmatch; reject control chars in open_link).
