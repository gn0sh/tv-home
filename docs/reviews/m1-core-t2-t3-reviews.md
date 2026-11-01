# Reviews of M1 core (T2+T3), diff 03d34f5..36f4abf (2026-10-31)

## Code review (opus/high): REQUEST CHANGES
Most serious: the 500-instance cap keeps instances in feed order, not by date, so yesterday's events can push out today's.
1. must-fix `instance-cap-starves-today` (IcsParser.kt:46,61,74-75,83). Collect windowed instances, sort them (today/tomorrow first, then by start), then cut to 500. Add a test asserting which instances are kept.
2. must-fix `floating-recurring-dst` (IcsParser.kt:147,153,184-185). Recurring floating events are 1 h off after a DST change. Iterate in UTC, then re-read in the device zone. Add a recurring floating fixture that crosses DST.
3. should-fix `one-bad-event-fails-feed` (IcsParser.kt:47-51,114). Catch per VEVENT. An Outlook-style TZID like "Eastern Standard Time" currently kills the whole feed.
4. should-fix `rrule-advance-cpu` (IcsParser.kt:172). SECONDLY/MINUTELY with BYSETPOS from old DTSTARTs takes seconds to minutes. Skip or reject these rules, or add a time budget.
5. should-fix `blocking-in-suspend` (IcsCalendarSource.kt:34-46, CalendarSource.kt:13). Either use coroutines withContext(IO) with a cancellable call, or make the API non-suspend so the blocking is explicit.
6. nit `recurrence-id-thisandfuture`: RANGE=THISANDFUTURE is ignored. 7. nit `unknown-tzid-as-utc`: should fall back to the device zone. 8. nit `test-assertions-weak`: cap tests check only the count, and the runaway test never exercises advanceTo.
Checked and sound: EXDATE, RECURRENCE-ID moves, cancellations, all-day in extreme zones, TZID DST, midnight crossing, the window and the Continues label, fetcher HTTPS/redirect/body cap/gzip, Throwable mapping, no URL or exception text escapes, Redactor and ProvisionValidator.

## Security review (opus/high): APPROVE WITH CHANGES
Most serious: `ics-parse-cpu-memory-dos`. The caps limit output, not CPU or memory. Probes:
- A 335-byte VTIMEZONE with SECONDLY from 1900 runs out of memory.
- SECONDLY;COUNT=2e9 from 1970 runs over 60 s.
- 20k SECONDLY events ending just before the window run over 60 s.
1. MEDIUM `ics-parse-cpu-memory-dos` (IcsParser.kt:38,145-149,169-174). Drop VTIMEZONE and resolve TZID via ZoneId.of, falling back to the device zone. Reject SECONDLY, MINUTELY and HOURLY rules, plus COUNT rules with a DTSTART far before the window. Add a parse-wide iteration budget, a wall-clock deadline and a cap on VEVENT count. Use the probes as fixtures with assertTimeoutPreemptively.
2. LOW `fetcher-customize-loosens-tls` (IcsFetcher.kt:36,39). Make `customize` internal or test-only.
3. LOW `redactor-over-redacts` (Redactor.kt:36-38). Use needles only for the private token, its hex and the user segment (raw and decoded).
4. LOW `pii-in-data-class-tostring` (EventModel.kt:15, CalendarSource.kt:8, Agenda.kt:13). Override toString to print counts only.
5. Functional `old-dtstart-recurrence-missing`. Series with DTSTART in 1900 return 0 instances in 2026 (birthdays would vanish), while the same series from 1990 works. advanceTo is the suspect.
Checked and holding: the regexes with trim, Throwable mapped to enums, the fixed fake secrets, text-only parse, HTTPS before I/O, no SSL redirect, the 10 MB cap after gzip, toString overrides on secret holders, locked dependencies.

# Round 2: fresh reviews of the fix d26fb56..b255e88

## Security (opus/high): BLOCK
`ics-parse-cpu-memory-dos` came back a third time, so the architect re-plans per the playbook.
1. HIGH: expander work outside the budget (RecurrenceExpander.kt:164-165, 211-227, 241-242, 259). candidateDays and byDayIn run before budget.take() and are quadratic. A 20 KB YEARLY rule with BYMONTHDAY x3000 and BYDAY=1MO x3000 runs for more than 60 s and ignores both the deadline and cancellation. BYDAY x1M runs out of memory. Fix: distinct and cap every BYxxx list, compute byDayIn once per period, and charge the budget inside those helpers.
2. HIGH: the VTIMEZONE strip can be bypassed with bare CR line breaks (IcsParser.kt:366-376). A 305-byte feed runs out of memory, and the same trick gets past the VEVENT cap. Fix: treat CR, LF and CRLF all as breaks, or reject a lone CR.
3. MEDIUM: vCal 1.0 DAYLIGHT/TZ properties build a timezone without any VTIMEZONE (IcsParser.kt:69), taking over 60 s. Fix: drop those properties or reject VERSION:1.0.
4. MEDIUM `ics-parse-deadline-excludes-biweekly`: the 2 s deadline starts before biweekly parses and is only checked during expansion. A 9 MB feed takes 1.5-2.2 s, likely 8-22 s on a TV. Fix: body cap about 2 MB, VEVENT cap about 5k, start the deadline after the parse, and report exhaustion distinctly.
5. LOW `redactor-over-redacts` residual: matching is case-sensitive, and webcal:// and double-encoded forms leak.
6. LOW `deps-no-checksum-verification`: already addressed in 4a29b6a (verification-metadata.xml, strict).
Holding under hostile input: max EXDATE lists, 600k RDATEs, a 9 MB line, 200k-deep nesting, 1M BYSETPOS, VTIMEZONE inside X- components, SECONDLY over CRLF.

## Code (opus/high): REQUEST CHANGES
1. must-fix `deadline-drops-recurring-silently` (IcsParser.kt:66-69,79-80,91; IcsCalendarSource.kt:57). This is the same root cause as security #4: a partial result replaces the last good one. Fix: start the deadline after the parse; on exhaustion return a partial marker or Failed(PARSE); test with a fake clock.
2. should-fix `cancel-returns-network-error` (IcsFetcher.kt:83-87). A cancelled fetch returns Error(NETWORK) instead of throwing. Fix: ensureActive() before mapping the error, and test fetch's own return value.
3. nit `unsupported-rule-vanishes`: the code and the doc disagree. nit `allday-until-utc-frame`. nit `one-bad-event-fails-feed` partly back: one bad EXDATE drops the whole series.
Checked: the expander matched dateutil on 6,000 random rules with 0 mismatches. Previously fixed keys stay fixed.
