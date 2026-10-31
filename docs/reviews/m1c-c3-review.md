# C3 review (4901e7c..a98fc6d): code + security (opus/high): APPROVE WITH NITS
194 tests. Own SSAP client justified (aiowebostv tries ws:3000 with ssl=False and needs ~37 permissions). wss 3001 only; exactly 8 permissions; TLS pin fails closed; TOFU pairing documented and acceptable; key sealed; aiohttp hashed; WoL correct; C4 nits correct.
Most serious: MEDIUM `lg-hello-id-match` lg.py:245: hello (and get_sys_info) reply matched by id; aiowebostv matches by type; TV likely omits id, so every connect would time out. Fake adds the id. Fix before C5.
LOW `lg-close-wait` (ws_close timeout 1 s), `lg-silence-doc` (7 s = no commands; doc or raise).
Nits: `dep-direct-aiohttp` (declare aiohttp==3.14.5), `lg-manifest-test` (assert full payload), `lg-step-readback-msg` (lg.py:325-326).

## Delta (4eb785d): APPROVE. All six closed. C6 must verify whether the LG pings idle links. C3 passes review.
