# C4 review (aa46f7d..0c55634): code + security (opus/high): REQUEST CHANGES
156 tests. Key sealed before write, DPAPI ctypes correct, pin after pairing, fixed error reasons.
Most serious: HIGH `atv-stale-connection` googletv.py:205-208, 234-239: _connected only reset in _drop; androidtvremote2 0.3.2 silently drops sends on a closed link (base.py:85-87) and closes idle links after 16 s, so after standby/Wi-Fi blip/idle every command "succeeds" and sends nothing. Fake hides it. Fix: treat transport None/closing as disconnected and reconnect (re-checks pin); make fake drop silently.
MEDIUM `atv-unseal-dead-end` googletv.py:103-105,159-161,276: unseal failure makes tv_pair unrecoverable; regenerate identity in start_pairing.
LOW `atv-pin-port-assumption` (6467 vs 6466; add on-device check to C6), `atv-pin-after-start` (check right after TLS handshake), `atv-pairing-mitm-claim` (1/256 per try; fix docstring).
Nits: `atv-temp-key` (option: store encrypted PKCS#8 permanently, DPAPI-seal only its password), `dpapi-entropy-claim`, `pkg-regex-newline` (fullmatch; reject control chars in open_link).

## Fix review (fresh, opus/high, 3769157..812bc9d): APPROVE WITH NITS
168 tests. Key never plaintext on disk (PBES2/AES-256, DPAPI-sealed 32-byte password); in-handshake pin check verified on Python 3.11-3.13 (0 bytes sent on mismatch); reconnect re-pins, no storm; unseal re-pair works.
Nits carried into C3: `atv-half-open-window` googletv.py:257,293-296 (reconnect if no TV ping for ~7 s; C6 blip test sends within 16 s), `atv-blocking-loop` (run_in_executor), `atv-key-load-oserror` (label), `atv-stale-pending-pin` (clear at start_pairing).
C4 passes review.
