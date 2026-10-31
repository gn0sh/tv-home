# T2b reviews (0142aeb..d8cdb32)

## Security review (security-engineer, opus/high, 2026-10-31): REQUEST CHANGES (release BLOCK on #1)
Tests: run 1 failed on P14 perf (500 ms, under load); run 2 passed (332 ms).
Most serious: #1.
1. HIGH `instance-cap-removal-order` IcsParser.kt:285-296, 352-368. The 500-instance heap evicts as it adds; RECURRENCE-ID removals reapply only in result(). Repro: one real event today 15:00; one master DAILY;UNTIL=today;BYHOUR=0..14;BYMINUTE=0..59 (900 instances); 600 RECURRENCE-ID STATUS:CANCELLED overrides (~93 KB) -> Complete(0 events, truncated=true), cached over the good copy. Also one dense series alone pushes real events out of today. Fix: (a) truncated + removal in result() -> Exhausted(INSTANCES); (b) per-UID cap in the focus window (e.g. 48/day). Add as P-probe.
2. MEDIUM `uncharged-per-vevent-work` IcsReader.kt:243-249, IcsParser.kt:186-188, RecurrenceExpander.kt:145/267-280. Rule parse + expander construction (up to 1,000 LocalTime) uncharged even when unreachable. Repro: 35,412 VEVENTs DAILY;UNTIL=19700102;BYHOUR=0..23;BYMINUTE=0..40, 10.4 MB: 1,544 ms, 0 steps. Fix: ruleCanReach before construction, lazy times, charge proportional to rule size.
3. LOW `exdate-uncharged` IcsParser.kt:193. 320 VEVENTs x 2,000 TZID EXDATEs, >256 TZIDs: 639 ms, 0 steps. Fix: budget.take(exdates.size).
4. LOW `global-step-budget-calibration` IcsParser.kt:35. 1M steps = 26-56 ms here; consider 10M (architect).
5. LOW `fetch-redirect-hops-unpinned` IcsFetcher.kt:143-144/187. Intermediate cross-host https hops followed (secret not sent). Fix: check every hop or followRedirects(false).
6. LOW `p14-perf-flaky` HostilePerfTest.kt:28. Wall-clock assert flaky under load (devops).
Held up: framing (bare CR/LF, folds, grouped BEGIN, depth, line caps), streaming memory with fixed caps (~30 MiB peak), Stop/Reject as Throwables, Exhausted/Rejected never saved, ensureActive before mapping, no secret in toString, strict URL regex, https->http blocked, biweekly test-only, redactor no ReDoS.
Probe file: scratchpad SecProbeTest.kt (repros A-D).

## Code review (code-reviewer, opus/high, 2026-10-31): REQUEST CHANGES (1 must-fix)
Tests: 166/166, hostile 34/34.
Most serious: must-fix `ics-rule-setup-uncharged` (same root as security #2) IcsParser.kt:187-188 builds Rule + RecurrenceExpander before ruleCanReach (line 236), 0 steps; buildTimes() up to 1,000 LocalTime (RecurrenceExpander.kt:267-280), Rule init sorts ~250 BYDAY (99-107). 50,000 VEVENTs DTSTART 2030 + DAILY;BYHOUR=0..9;BYMINUTE=0..9;BYSECOND=0..9, 7.75 MB: Complete, steps=0, 1.05-1.27 s frozen clock. Breaks 3a Definition 1. Fix: decide reach from spec before building; charge |H|*|M|*|S|+|BYDAY| before construction; probe P33.
Should-fix:
1. `count-walk-skips-active-series` RecurrenceExpander.kt:446-460, 289. COUNT walk charged pessimistic perPeriodBound; DTSTART 2015-01-31 NY MONTHLY;BYMONTHDAY=31;COUNT=500 viewed 2026-03-31 -> Complete, 0 events, skippedSeries=1, silently cached. Same for BYDAY=MO..FR;BYSETPOS=-1;COUNT=300. Fix: charge real ticks, or count cycles arithmetically.
2. `expander-no-differential-test`: biweekly differs on 183/3,655 random rules, and ours was RFC-correct in every hand-checked case, so biweekly is not a valid oracle. Add brute-force RFC reference (enumerate days, filter) differential test.
Nits: `fetch-chunked-double-copy` IcsFetcher.kt:122-128 (~20 MiB peak unknown length); `zone-memo-key-size` ZoneResolver.kt:49-56 (raw 8 KiB keys, ~2 MB); `heap-evict-then-remove` IcsParser.kt:364-367 (= security #1).
Held up: Stop as Throwable, Exhausted/Rejected never saved, refresher locking, ensureActive, O(bytes) reader, Google cases (DST TZID, EXDATE, moved instance, all-day UNTIL, cancelled all-day override, WKST=SU INTERVAL=2, multi-day).

## Manager decisions
- `ics-parse-cpu-memory-dos` family: re-plan failure 1 of 2 (uncharged setup work). Cheap fixes, not a new architecture. A second failure after this fix escalates per playbook.
- Step budget (#4): raise maxSteps from 1M to 5M (about 0.3 s here, est. under 2.5 s on the TV), so it stays inside the 5 s deadline with margin. Reversible constant; architect may revise.
- P14 perf flake (#6): give the wall-clock assert a 2x margin and run hostileTest after test, not in parallel.

## Delta round (f9d21dc)
Code review: APPROVE WITH NITS. Closed: ics-rule-setup-uncharged, count-walk-skips-active-series (MONTHLY/YEARLY; COUNT boundaries exact vs brute force), expander-no-differential-test. Remaining walked shape (DAILY/WEEKLY + BYMONTH/BYMONTHDAY + COUNT) accepted. New should-fix `instances-exhausted-overbroad`: a per-UID cut makes any late RECURRENCE-ID turn the whole feed Exhausted(INSTANCES).
Security: APPROVE WITH CHANGES. Probes A-C fixed, D no longer exhausts, G 6,000 COUNT checks 0 mismatches.
1. MEDIUM `instance-cap-exhausted-one-invite` (same as code review's): one ~560-byte invite (DAILY;BYHOUR=0..23;BYMINUTE=0,20,40 + one moved instance) -> Exhausted(INSTANCES); every-15-min series with one moved instance also trips it. Availability only.
2. LOW `focus-cap-per-range-not-per-day`: cap is 48 x focus days per UID over the range, not per day; 8 UIDs x 84 before 15:00 push a real event out (visible, Complete).
3. INFO: 5M steps ~0.6 s frozen clock, est. 3-5 s on TV; don't raise further without TV measurement.
Probe files: m1-t2b-secprobes.kt.txt (A-D), m1-t2b-secprobes2.kt.txt (E-H).

Manager decision: take security's by-construction fix. A first reader-only pass collects RECURRENCE-ID keys (bounded, charged), so removals are applied at add time, never depend on file order, and ParseLimit.INSTANCES is removed. Per-UID cap becomes per (UID, local day). Delta re-reviews after.
