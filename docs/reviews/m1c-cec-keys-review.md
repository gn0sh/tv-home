# Review: tv-remote CEC power and volume keys (11a0a55, fixes a67d7ca)

Code review, 2026-11-01 (opus, medium).

Round 1 on 11a0a55: REQUEST CHANGES. 203 tests passed.
- `cec-volume-unbounded` (medium): the 3-press limit was per call only, so repeated calls had no ceiling.
- `instructions-volume-cap-stale` (medium): INSTRUCTIONS still claimed a 60 cap that the CEC path doesn't enforce.
- `cec-power-toggle` (low): POWER is a standby toggle, not a fixed on or off.
- `cec-volume-routing` (info): volume keys reach the LG only when the Google TV's CEC volume control is on.

Delta on a67d7ca: APPROVE. 204 tests passed. All four findings fixed: 10 VOLUME_UP presses per rolling minute across
calls, plus updated INSTRUCTIONS, docstring, architecture row and README.
Accepted, low:
- Presses count against the limit even when a send fails, which errs safe.
- The rate limit only slows a looping caller; the real guard is the INSTRUCTIONS text. Accepted for an internal tool.
