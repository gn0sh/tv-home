# Design: tv-home M1 agenda app

Spec: `docs/specs/tv-home.md` (M1). Status: draft 2026-10-31. Audience: Brian only, on his TV, from a couch. Built by `frontend-engineer`; no production code here.
Satisfies AC 3 (refresh/cache), 4 (states), 5 (10-foot, D-pad), 6 (nothing typed on TV), 8 (cache shown first).

## Principles
- Glance, don't operate. One screen, no menus, no settings, no text input. Launches straight to the list.
- Launcher-agnostic: a normal Leanback app (LEANBACK_LAUNCHER intent), so Projectivy or any launcher can show it as a tile.
- Units: Android TV 1080p is 1280x720 dp (1 dp = 1.5 px at 1920x1080). Sizes below are sp/dp; px in brackets.

## Layout (1920x1080, ASCII; margins not to scale)
```
|<-- 64dp (96px) safe margin                                     -->|
 +----------------------------------------------------------------+
 | (27dp/40px top margin)                                         |
 |  Today  Sat 31 Oct                          Updated 4 min ago  |  header 36sp / 22sp
 |  ------------------------------------------------------------  |
 |  [ 9:00 AM   Dentist                                         ] |  <- focused row
 |    11:30 AM  Lunch with Sam                                    |
 |    All day   Mum's birthday                                    |
 |  Tomorrow  Sun 1 Nov                                           |  day heading 30sp
 |    All day   Daylight saving ends                              |
 |    10:00 AM  Run club                                          |
 |    ...                                                    ^ v  |  scroll cue
 |  (27dp/40px bottom margin)                                     |
 +----------------------------------------------------------------+
```
- Safe area: 5% overscan margin = 96 px left/right (64 dp), 54 px top/bottom (36 dp). Nothing, including focus ring and scroll cue, is drawn outside it. Background fills edge to edge.
- Single column, left-aligned, max content width 1100 dp so lines stay short. Right ~20% of the screen is empty on purpose (room for a launcher overlay).
- Time column fixed 120 dp wide, right-aligned (so "9:00 AM" and "11:30 AM" line up); title column follows, one line, ellipsised at the end. Times use the TV's 12h/24h setting.
- Rows 64 dp (96 px) high, 8 dp gap: about 8 rows fit under the header. Day headings are not focusable.
- "Today" and "Tomorrow" are always shown. If a day is empty, its heading shows "Nothing scheduled" in place of rows.

## Type and colour
| Element | Size | Weight | Colour on #101418 | Ratio |
|---|---|---|---|---|
| Screen/day heading | 36 / 30 sp | Medium | #F2F4F7 | about 16:1 |
| Event time and title | 28 sp | Regular, time Medium | #F2F4F7 | about 16:1 |
| Secondary (date, "All day") | 24 sp | Regular | #B8C0CC | about 9:1 |
| Fresh/stale age text | 22 sp (floor) | Regular / Bold | #B8C0CC / #FFC247 | 9:1 / about 11:1 |
| Focused row | fill #243044, 4 dp ring #8AB4F8 | | text stays #F2F4F7 on #243044 | about 11:1 |
- Dark theme only (TV in a dim room, no burn-in-bright white). Every text pair is at least 4.5:1; the large (28 sp+) text is well above 7:1. No text under 22 sp. The engineer must verify the ratios in a unit test or checklist (token table above is the source).
- Roboto/system font; no custom font to ship. Never rely on colour alone: stale also has the word "Updated" and a bold weight.

## D-pad and Back
- Initial focus: the first row, or the row of the next upcoming event today if it exists (so a long day opens near "now"). Nothing to focus (empty/unavailable): focus stays on the screen root, no dead end.
- Up/Down: move focus one row; the list scrolls to keep the focused row fully inside the safe area, with one row of look-ahead above and below. Focus skips day headings (the heading scrolls into view with its first row). Holding the key repeats; no wrap at the ends (a short ring-bump is enough).
- Left/Right: ignored, no trap and no hidden controls. OK/Enter on a row: no action in M1 (rows are read-only).
- Scroll cue: small up/down chevrons (24 dp, #B8C0CC) at the right edge of the list, shown only when more rows exist off screen.
- Back: exits the app to the launcher in one press from any state; no confirm dialog. Home is handled by the system.
- Auto-refresh (every 15 min while visible) never moves focus or scroll position; the list only changes when its content did.

## States (exact copy; sentence case, no jargon, no exclamation marks)
Header right side shows the age line in every state that has data.
| State | Main area | Header right |
|---|---|---|
| ok (cache or fetch under 60 min old) | The list | "Updated 4 min ago" (grey); "Updated just now" under 1 min |
| ok, still fetching | The list | unchanged; no spinner (cache is instant) |
| stale (fetch failed, cache 60 min or older) | The list | "Updated 3 hours ago" in amber, bold, with a 22 sp warning triangle; plus footer line "Can't reach calendar. Showing the last copy." |
| unavailable (no cache, fetch failed) | Centered: "Can't reach calendar" and below it "Check the TV's Wi-Fi. It will try again soon." | none |
| unreadable (no cache, feed arrived but unusable) | Centered: "Calendar data unreadable" and below it "It will try again soon." | none |
| not set up (no calendar link on device) | Centered: "Calendar not set up" and below it "Set it up from your PC: see the setup steps in the README." | none |
| nothing scheduled (ok, both days empty) | Both headings kept, each with "Nothing scheduled" | age line as ok |
| stale and both days empty | Same body as nothing scheduled (the type carries `nothingScheduled = true`) | stale age line; footer too when emphasised |
- Age wording: under 1 min "just now"; 1-59 "N min ago"; 1-23 hours "N hours ago" ("1 hour ago"); then "N days ago". Amber/bold applies from exactly 60:00 old (60 min or older, measured in seconds; matches AC 4). Age is computed from the last successful fetch, not app launch.
- A fetch failure with a cache younger than 60 min shows plain ok (no alarm); retry is silent every 15 min.
- The calendar link is never typed or shown on the TV (AC 6), including in error text. "Not set up" gives a pointer, not the link. Setup happens from Brian's PC (adb push or the README steps the architect defines); the design needs one fixed pointer, so the engineer should use the README title the technical-writer sets.
- Calendar event text comes from outside: render as plain text only, no markup, one line, ellipsis. Empty title shows "(No title)".

## Accessibility
- TalkBack: each row has one contentDescription read as a sentence: "9:00 AM, Dentist" or "All day, Mum's birthday". Day headings are marked as headings ("Today, Saturday 31 October"). The age line is a live region with polite announcement only when it changes state (fresh to stale), not every minute.
- Focus order is top to bottom, matches visual order; focus ring is at least 4 dp, 3:1 against its neighbours and not colour-only (the row also gets the fill).
- Respect system font scale and "remove animations"; scrolling animation is 150 ms or less, none when animations are off. No flashing, no auto-advancing content.
- Contrast at least 4.5:1 for all text (WCAG 2.2 AA), at least 3:1 for ring and chevrons.

## Launcher banner and icon
- Banner 320x180 px (xhdpi 1:1, provide as drawable): dark #101418 background, a flat calendar page glyph on the left with today's day number (static "31" is fine, no live update), the word "Agenda" in 28 sp equivalent on the right. Text and glyph stay inside the central 90% so Projectivy-style tiles crop safely. App label: "Agenda".
- Round/square app icon (512x512 source): the same calendar glyph on #101418 with an #8AB4F8 header strip. Adaptive icon: glyph in the 66% safe zone.

## Mockup / open questions
- No HTML mockup: the layout is one list; the wireframe and token tables are enough. Recommend Focus Group: skip (primary user is the CEO).
- Q1: 12h or 24h clock? Recommended default: follow the TV system setting.
- Q2: Should OK on a row ever open the event? Default: no, M1 is read-only (cut scope).
- Q3: Where does "set it up" point? Default: the repo README section "Set up the calendar link from your PC" (technical-writer to write; wording above is final copy).
- Proposed spec change: none. Note AC 4 says "emphasised" for stale; this design defines it as amber, bold, warning icon, plus the footer line.
