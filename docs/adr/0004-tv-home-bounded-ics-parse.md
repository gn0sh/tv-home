# ADR 0004: tv-home bounded ICS parse (re-plan of `ics-parse-cpu-memory-dos`)

Status: accepted (architect), 2026-10-31. Supersedes the ICS-library part of ADR 0001 (decision 3). Feature: tv-home
M1. Architecture: `docs/architecture/tv-home.md` section 3a. Tasks: `docs/tasks/tv-home-m1.md` T2b.
Trigger: the playbook loop limit. `ics-parse-cpu-memory-dos` came back a third time across review and security
(`docs/reviews/m1-core-t2-t3-reviews.md`, rounds 1 and 2).

## Context
Seven holes so far, each patched one at a time:

| # | Hole | Root cause class |
|---|---|---|
| H1 | biweekly's RRULE iterator (SECONDLY OOM, 1900 series dropped) | third-party code runs on untrusted input with no budget |
| H2 | biweekly evaluates VTIMEZONE RRULEs while parsing (335-byte OOM) | we interpret a component we never need |
| H3 | Bare CR line breaks slip past our VTIMEZONE strip and the VEVENT cap | two tokenizers that disagree (our prepass splits on LF, biweekly on CR too) |
| H4 | vCal 1.0 DAYLIGHT/TZ build a timezone (over 60 s) | we interpret properties we never need |
| H5 | Quadratic BYxxx candidate generation runs before `budget.take()` | input-sized lists reach loops; budget charged after the work |
| H6 | The deadline does not cover biweekly's text parse (9 MB: 1.5-2.2 s here, est. 8-22 s on a TV) | an uninterruptible third-party phase |
| H7 | A budget or deadline cut returns fewer events, which then replace the good cache | the result type cannot say "incomplete" |

Found while listing consumers for this re-plan (not yet reported, same key):
- H8 `ZoneResolver` calls `ZoneId.of` in try/catch for up to 64 suffixes of a 128-char TZID; every miss builds an
  exception. 30k VEVENTs x several date properties x 65 throws is tens of seconds, outside the budget.
- H9 `toRule` filters out-of-range BY values, so an all-invalid list (BYMONTH=13) becomes "no filter" (every month).
- H10 `IcsFetcher` keeps the body in an okio buffer and then copies it to a String (up to 2 bytes per char).
- H11 The host pin (`calendar.google.com`) is checked only at provisioning; the fetcher and source trust whatever
  the store holds.

H1-H4 and H6 live in biweekly's surface, which we cannot budget or interrupt. H3 is a parser differential, which
patching cannot close while two tokenizers exist. H5 and H8 are in our own code: loops whose length comes from the
input. H7 is a contract gap. Patching more checks into this structure keeps the attack surface the same size.

Threat model: the URL is pinned to `https://calendar.google.com/calendar/ical/.../private-<32 hex>/basic.ics`, so
the bytes come from Google's serializer. The realistic attacker sends Brian a calendar invite (or edits a shared
calendar) and controls the event fields Google passes through. Whether Google passes an arbitrary RRULE through is
unverified, so **the design must hold for any byte string up to the body cap**, as the reviewers' probes assume.

## Decision
**(d): option (b), a whitelist reader, plus budgets inside our own code from option (a), plus a typed outcome. Not
(c) for now.**

1. **Our own strict reader (`IcsReader`) replaces biweekly in the app.** biweekly moves to `testImplementation`
   only, as a differential oracle. One tokenizer, so no parser differential. Framing is strict, interpretation is a
   whitelist, content is tolerant:
   - *Strict framing (else `Rejected`, whole feed):* CRLF line ends only (a CR not followed by LF, or an LF not
     preceded by CR, rejects); an optional UTF-8 BOM; the first line `BEGIN:VCALENDAR`, exactly one VCALENDAR,
     nothing but CRLF after its END; `BEGIN`/`END` lines with no parameters and matching names; depth at most 4;
     `VERSION:2.0` before the first VEVENT; `CALSCALE`, if present, `GREGORIAN`.
   - *Whitelist interpretation:* only these are ever read. VCALENDAR: `VERSION`, `CALSCALE`. VEVENT: `UID`,
     `DTSTART`, `DTEND`, `DURATION`, `RRULE` (first only), `EXDATE`, `RDATE`, `RECURRENCE-ID`, `STATUS`, `SUMMARY`.
     Parameters: `TZID`, `VALUE`, `RANGE`. Everything else (VTIMEZONE, VALARM, X- and unknown components,
     `DAYLIGHT`, `TZ`, `DESCRIPTION`, `ATTENDEE`, X- properties, ...) is skipped as opaque lines: no buffering,
     no parsing, cost linear in bytes.
   - *Tolerant content:* a bad value skips that VEVENT (or, for one EXDATE/RDATE value, that value) and is counted.
     One odd invite never fails the feed.
2. **Every loop is bounded by construction.** Each loop in the parse pipeline is one of: (i) over input bytes,
   bounded by the 10 MiB body cap; (ii) over a fixed domain; (iii) over periods or instances, charging the
   `Budget` *before* its body with a static upper bound of that body's work. The RRULE parser turns every BYxxx
   list into a set over its RFC domain (BYMONTH 12, BYMONTHDAY 62, BYDAY 749 ordinal-weekday pairs, BYHOUR 24,
   BYMINUTE 60, BYSECOND 61, BYSETPOS 732), so a list cannot exceed its domain whatever the input says. Any
   out-of-range value, unknown part, repeated part or RRULE line over 1 KiB makes the rule unsupported (DTSTART
   only), which also closes H9. Each loop in `calendar/` carries a `// bound:` comment naming (i), (ii) or (iii);
   review checks it. No regex with nested quantifiers runs on input. Per-VEVENT setup is (iii) too (review
   2026-10-31, `ics-rule-setup-uncharged`): an RRULE's size (one step per BYxxx value, plus one) is charged before
   its rule is built, a rule that cannot reach the window is never built, the times of day are charged before they
   are built, and each EXDATE is charged before its key is made.
3. **One deadline covers the whole parse.** It starts at parse entry (before tokenizing) and is checked every
   64 KiB read and every 512 steps, with the interrupt flag. The reviewers suggested starting it *after* the text
   parse; that was a workaround for an uninterruptible biweekly phase. With one interruptible pipeline, a deadline
   over everything is the stronger bound.
4. **Typed outcome; only a complete parse is ever shown or cached.**
   `ParseOutcome = Complete(events, stats) | Exhausted(limit) | Rejected(reason)`. Only `Complete` becomes
   `FetchResult.Ok`. `Exhausted` becomes `Failed(PARSE_LIMIT)` (new enum value), `Rejected` becomes `Failed(PARSE)`.
   A new core `AgendaRefresher` is the only writer of the cache, and writes only on `Ok`. Deterministic per-item
   decisions (a malformed VEVENT skipped, an unsupported rule shown as DTSTART only, one series over its own budget
   skipped) stay inside `Complete` and are counted in `stats`. Parse-wide limits (deadline, global steps, VEVENT
   count, kept series) give `Exhausted`, because the answer is unknown. RECURRENCE-ID removals never depend on file
   order (delta review 2026-10-31, replacing the earlier `Exhausted(INSTANCES)`): a first, keys-only pass of the
   reader (UID and RECURRENCE-ID only, one step per key, under the same deadline and 50,000-VEVENT cap) collects
   every removal key, so originals are dropped before they reach the 500-instance cap and nothing kept is removed
   later. A VEVENT with a valid UID and RECURRENCE-ID removes its original even if the override is then skipped.
   One UID holds at most 48 focus instances per local day (48 today, 48 tomorrow), so one dense series cannot push
   real events out of today.
5. **Cancellation is never a result.** Every `catch (Throwable)` at a boundary calls `ensureActive()` before
   mapping, so a cancelled fetch or parse throws `CancellationException` and changes nothing (no cache write, no
   `lastFetch` update). Closes `cancel-returns-network-error`.
6. Smaller fixes from the consumer list: `ZoneResolver` uses set membership in `ZoneId.getAvailableZoneIds()`
   (no exceptions), tries at most the last 4 path suffixes, and memoises at most 256 TZIDs per parse (H8;
   beyond that it resolves uncached, which is now cheap). The fetcher returns
   `ByteArray`, and the reader decodes only kept lines (H10). `IcsCalendarSource` re-runs `validateCalendarUrl`
   before fetching, and the fetcher accepts a final response only from `calendar.google.com` or a `*.google.com`
   host (H11).

### How each hole is closed by construction
| Hole | Closed by |
|---|---|
| H1 | biweekly is not on the app's runtime classpath (lockfile check in T2b) |
| H2 | No VTIMEZONE interpreter exists; the component is opaque lines. TZIDs resolve from the device tz database |
| H3 | One tokenizer; a lone CR or LF rejects the feed |
| H4 | `VERSION` must be `2.0`; `DAYLIGHT`/`TZ` are not on the whitelist, so they are never read even under 2.0 |
| H5 | Domain-bounded rule sets plus a static per-period bound charged before the work; a test counts real helper iterations and asserts they never exceed the charge |
| H6 | The whole pipeline is ours and polls the deadline every 64 KiB and 512 steps |
| H7 | No code path from `Exhausted` to `Ok`; the refresher writes the cache only on `Ok` |
| H8-H11 | Item 6 above, item 2 for H9 |

## Alternatives considered
- **(a) Keep patching budgets inside the parser.** Cheapest per round, and each round's fix was correct. But four of
  the seven holes are in biweekly's surface (VTIMEZONE, vCal 1.0, its line splitting, its uninterruptible parse),
  which we cannot budget, and H3 cannot be closed while two tokenizers exist. Each round cost a full opus/high
  review cycle and found a new hole. Rejected; its good part (budgets) is kept, applied to code we own.
- **(b) alone, "reject anything else".** Rejecting the whole feed for any non-whitelisted property or component
  would let one invite with an unexpected property blank the calendar, which is the DoS we are closing. So unknown
  content is skipped, and only framing violations reject.
- **(b') vinnie (biweekly's tokenizer) plus our whitelist.** Keeps a maintained tokenizer, but vinnie splits lines
  on CR, LF and CRLF, decodes quoted-printable, buffers whole lines and does not poll our deadline. That brings
  back H3 and H6. Rejected.
- **ical4j.** It is the same class of general parser (VTIMEZONE evaluation, its own tz loading via
  `ZoneRulesProvider`, risky on Android, ADR 0001). Rejected.
- **(c) Process isolation on Android:** parse in an `android:process=":parse"` service, killed on a wall clock,
  with its own heap. Strongest containment: any parser bug is bounded by the OS. Costs: Binder's 1 MB transaction
  buffer means the 10 MiB body needs a pipe/`ParcelFileDescriptor`, or the child fetches itself (moving the secret
  read there); one more process (about 15-30 MB PSS, est.) on a low-memory TV; a cold process start per refresh
  (est. 0.2-0.5 s, unverified); service lifecycle code. None of it can run in our JVM sandbox, so it would be
  written blind until CI. And it does not fix H7, which is a contract gap. With (d), every loop is ours and
  bounded, so the residual risk (a bug in our own bounded code) does not justify that cost now. Kept as an
  escalation path (below).
- **Steelman, "do the simplest thing": switch M1 to the Google Calendar API (OAuth, `events.list` with
  `singleEvents=true`, `timeMin`/`timeMax`, `maxResults`).** Google expands recurrence server-side, so there is no
  RRULE, VTIMEZONE or ICS text at all, and results are real-time (no ICS lag). It removes this whole class of bug.
  Against it: Brian must create a Google Cloud OAuth client and consent (hard stop 5, new credential), the TV needs
  a device-flow screen and refresh-token storage, and the spec's default is ICS. `CalendarSource` keeps it open.
  This is the strongest alternative on the merits; it loses on needing Brian's credential work now, when (d) needs
  nothing from him.
- **Don't build M1.** Not open here: M0 is resolved and the spec keeps M1.

## Consequences
- We now hand-roll a parser for a strict subset, which the spec's technical constraints
  (`docs/specs/tv-home.md`, "Technical constraints") rule out. That line came from the spec panel's devil's advocate
  (`docs/reviews/panel-tv-home-spec.md`), not from Brian, and the goal is unchanged, so this is a team decision, not
  an ask. Correctness risk is covered by: biweekly as a test-scope differential oracle on every fixture and a seeded
  Google-shaped corpus, a seeded differential test of the expander against a brute-force RFC 5545 reference
  (review 2026-10-31: biweekly's iterator differs from RFC 5545 on about 5% of random rules, so it is the reader's
  oracle only), and a live check on a public Google feed in CI. T2b.5
  asks the product-manager to amend that spec line.
- Removing biweekly also drops vinnie and jackson-core from the APK.
- Two existing tests change expectation on purpose: 3,000 crafted COUNT series from year 1 now give
  `Exhausted(STEPS)` (the earlier cache is kept) instead of "one-offs survive"; the 100-year-span daily series is
  skipped by its series budget instead of returning 500 instances. `x.BEGIN:VTIMEZONE` and `BEGIN;X=1:VTIMEZONE`
  now reject the feed.
- Global step budget (manager decision 2026-10-31, security review #4): 5,000,000 steps per parse (was 1,000,000),
  about 0.3 s here, est. under 2.5 s on the TV, so it stays inside the 5 s deadline with margin.
- Residual risk, accepted: someone who gets about 250 crafted recurring events into Brian's calendar (each just
  under its 20k-step series budget) can exhaust the 5M global budget, so refreshes stop and the screen shows the
  last copy, then "Calendar data unreadable" the next day. That hurts availability only, never what is shown, and
  he removes the events.
- Limits are guesses until measured on his feed and TV (`docs/architecture/tv-home.md` section 1). `Limits` stays
  a constructor parameter so a measured change is one line.
- **Escalation if this re-plan fails twice** (loop limit): put the Google Calendar API source to Brian as a decision
  (it removes the class), and build (c) only if he declines.
- Recommend: no Brain Trust. This is reversible and internal; the alternatives are argued above. The re-review
  must be a fresh, independent security review (third return of the key), not a delta.
