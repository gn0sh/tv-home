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
