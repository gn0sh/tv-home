# Panel on the tv-home spec (2026-10-31)

Three members, independent first round, sonnet/medium: devil's advocate, Brian's seat, Android TV platform and security engineer.
Spec reviewed: docs/specs/tv-home.md at 4104108. Verdicts: Go with changes (all three). Not unanimous Go, so no pre-mortem round.

## Findings
**Devil's advocate (strongest case against).** The spec never weighed adopting an existing free launcher (Projectivy, FLauncher, Wolf). Those already do the app row, favorites order and home handling, so building M1 and M3 reinvents a solved problem. The only real gap is the calendar. Steelmanned alternative: Brian installs Projectivy (about 30 min, no code), and the team builds only a small agenda app if he still wants the calendar on the home screen. Also said: use an ICS library, not a hand-rolled parser; cut the error states to ok, stale and unavailable; drop the cold-start and screenshot gates. Confidence medium on the launcher capabilities (unverified).

**Brian's seat.** The likeliest reason he gives up is sideloading: Developer options, wireless debugging, adb on Windows. That cost comes before he sees any value, and the spec never prices it. Typing a roughly 100-character calendar URL with a remote is miserable, so the spec needs a transfer method. Add a table of what Brian does in each milestone, with minutes. Use a lighter success signal: "keep it" after a week.

**Platform engineer.** The most serious finding is that an accessibility service cannot catch Home. It can only redirect after the stock home appears, which shows a flash of it, depends on the launcher's internal names, and doesn't cover boot. The real path to being the default home is adb (`cmd package set-home-activity`, else `pm disable-user` on the stock launcher), which has side effects, may be undone by OTA updates, and needs a PC to roll back. Other findings:
- Google's ICS feeds can lag by hours, which undercuts "Dentist 3pm". OAuth (calendar.readonly) is real-time.
- Use `<queries>` with LEANBACK_LAUNCHER, not QUERY_ALL_PACKAGES.
- Set allowBackup=false and use non-debuggable builds. Scrub the URL from exceptions.
- Use a stable signing key kept in Actions secrets.
- minSdk 31, target 34.
- Several acceptance criteria need a measurement method.

## Who has the stronger argument
The devil's advocate. All three found that M3 (being the real home screen) is the riskiest part. That is exactly what free launchers already solve, and Brian was already pointed to Projectivy. Building our own launcher first would spend the most effort on the part we're least able to do well. Minority view kept: the platform engineer thinks M1 is feasible and worthwhile if the launcher route fails.

## Decisions this changed
| Finding | Decision changed | Scope | Owner |
|---|---|---|---|
| Adopt existing launcher before building one | Yes: new M0, Brian tries Projectivy from the Play Store (no sideload) and reports gaps; custom launcher only if a gap he cares about remains | Cuts | product-manager |
| Calendar is the only real gap | Yes: the build starts with a standalone agenda app/tile usable from any launcher | Cuts | product-manager, software-architect |
| Accessibility can't catch Home | Yes: M3 rewritten as conditional; adb set-home-activity spike, accessibility redirect only as fallback | Cuts | product-manager |
| ICS lag of hours vs OAuth | Yes: calendar source becomes an open decision for Brian at calendar time (ICS simple but can lag; OAuth real-time but needs a Google Cloud OAuth client) | Neutral | product-manager |
| URL entry with a remote is impractical | Yes: entry via adb push or a QR/phone step, never typed on the TV | Adds | software-architect |
| Sideloading cost for Brian | Yes: table of Brian's steps and minutes; sideload asked once, as its own hard stop | Adds | product-manager |
| Use ICS/HTTP libraries, scrub logs, allowBackup=false, stable signing key, minSdk 31 | Yes | Neutral | software-architect |
| Drop cold-start and screenshot gates | Partly: keep with a measurement method only where it protects something | Cuts | product-manager |
| Lighter success signal | Yes: "keep it" after one week | Cuts | product-manager |
