# Game-day scoreboard (design + build spec)

Approved by Brian 2026-11-13 9:01 PM CT ("love it. make it happen."). Primary user: Brian and Jen (confirmed).
Reference frames and the HTML mockup with exact geometry: `docs/design/game-day-scoreboard/` (`mockup.html` takes
`?s=pre|focus|popup|live` and `&t=osu`; its tile images and fonts are not committed, the PNGs are the truth).

## What changes

On the day of a followed team's game, the hero's right side stops being a text card and becomes the playing surface
seen from above: a hockey rink (venue ARENA) or a football field (venue STADIUM), with both teams' abbreviations painted
on it. Claude's pick sits under it as a ticket stub. OK on the stub opens the pick as a full ticket.

Kept from PR #45 and #46, unchanged in behaviour:
- The stub's words are exactly `Picks.text` ("Claude's pick: NSH (+1.5)"). Nothing else about the pick is on the home screen.
- Up from the apps row reaches the pick; OK opens the full analysis; Back closes it. OK is never a no-op.
- The pick target is a leaf with no focusable parent (problem key `home-back-twice`); the dialog is its own window.
- The Watch pill's rules (PR #46: it appears one hour before game time) are not touched.

## 1. Data (core)

- `TeamConfig.abbr`: "NSH", "OSU".
- `GameInfo.opponentAbbr: String?`: NHL `abbrev`, ESPN `abbreviation`. Accept only 2 to 4 characters of `A-Z0-9` after
  upper-casing; anything else is null. Carry it through the `PanelCaches` DTO with a null default so old caches still parse.
- `ContextCard.Game.board: Scoreboard?` (new pure model, built in `contextCard`), non-null only for **today's** game
  (upcoming, live or final) when `opponentAbbr` is known. Null keeps today's text card exactly as it is now (and the
  "Next game" card is always text).

```
Scoreboard(surface: RINK | FIELD, own: "NSH", opp: "VGK",
           center: Time("9:00", "PM") | Score(own = 2, opp = 1),   // 24h clock: Time("21:00", null)
           meta: String, live: Boolean)
```
- Followed team is always on the left, in its colour; opponent right.
- `meta`, upcoming: `"Away · in 1h 59m"` or `"Home · in 3h 29m"` (reuse `GameDay`'s countdown). Live: the period label
  (`"2nd"`, `"Q3 8:12"`; "live" when unknown). Final: `"Final"` or `"Final (OT)"`.
- The network is appended (`" · ESPN+"`, or the existing "TV: not announced yet" text) only when there is **no** Watch
  pill for the game, because the pill already names it. Never for a final.
- Live with no scores yet: `center` stays `Time`.
- `ContextCard.Game.line` stays filled as now: it is the card's content description.

Ticket text, pure functions with table tests (`TicketText` or inside `Picks`):
- `title`: `"Predators at Golden Knights · Tonight, 9:00 PM"` (team name, vs/at, opponent name, "Tonight" from 5 PM local
  else "Today").
- `big`: the pick (`"NSH"`), `line`: the line with the pick prefix stripped (`"+1.5"`, or the score when there is no line),
  `short`: `Pick.short` or null.
- `body`: `analysis` or else `reason`. If it contains `"Pretend stakes:"`, everything from the last occurrence is `stakes`
  (the footer) and is cut from the body. The body is split into two paragraphs at the sentence end (`[.!?] ` followed
  by a capital) nearest its middle; one paragraph when there is no such point or the body is under 200 characters.

## 2. Layout (core `HeroZones`, an ADR 0008 addendum)

Game-day zones on the 1280 x 720 canvas, used only while `board != null`:
- `GAME_LEFT = Zone(64, 112, 456, 270)`: Now/Next, same type sizes, line 1 may wrap to two lines as now.
- `GAME_BOARD = Zone(540, 106, 676, 204)`: surface 150 tall, 10 gap, stub row 44 tall.
- Both end `APPS_GAP` above the apps row like today. If the board zone is shorter than 204, the surface shrinks (down to
  110, type scaling with it); shorter than that, `board` is ignored and the text card shows. Unit-test both.
- Zones must not overlap each other or the top strip, and stay in the safe area (extend `HeroLayoutTest`).

## 3. The surface (app, Compose Canvas, one composable, not focusable)

Geometry is in `mockup.html` (`rinkSvg` and `field`, viewBox 676 x 150). Summary:
- **Rink:** rounded rect radius 48, vertical gradient `#21498C > #153668 > #0E2A55`; blue lines at 36% and 64%
  (`#4E8CFF`, 5 wide, 75%); red centre line (`#E0453A`, 4) broken by the centre circle (radius 52, blue, 3); goal lines 44
  from each end with a crease arc; a faint diagonal sheen and edge vignette; boards: team accent (`#FFB81C`) 3.5 wide
  plus a 1 px white 16% inner line.
- **Field:** radius 10, green gradient `#1B5A34 > #0F3B22`, alternating 10-yard bands (white 3.5%), end zones 56 wide
  (left = team primary `#BB0000`, right = `#5B6068`), yard lines every 56.4 (midfield heavier), hash marks, outline
  `#E4E6EA`; centre is a rounded box 124 x 80 (`#0C2C19`).
- **Lettering:** `sans-serif-condensed` (platform font, no new asset), weight 900, 88 for abbreviations (own in
  `--a`: gold for the Preds, white for Ohio State; opponent `#E9F0FF`), 44/22 for the time, 80 for scores either side
  of the centre line (the circle is not drawn while a score shows). Sizes are fractions of the surface height and do
  **not** follow the system font scale (it is a graphic; the content description carries the text). A four-letter
  abbreviation must fit its half: shrink to fit.
- Soft shadow under the surface.

Backdrop while the board shows: no arena/stadium image; the team gradient, a soft glow behind the surface, two faint
light cones from the top (see `.art` in the mockup). The accent line above the apps row is not drawn (the boards carry
the colour). With the text-card fallback, the backdrop is as today.

## 4. The stub row (app)

- Left: the ticket stub, 44 tall, 2 dp accent outline, semicircle notches (radius 9) at both ends, fill `#06183A`
  (Ohio State `#16090A`). Inside: "✦", the label up to and including the colon (24 sp, medium, accent), a dashed
  divider, the rest of `Picks.text` (condensed, 31 sp, weight 800, text colour). Split `Picks.text` at the first ": ".
- Focused: fill and outline become the accent (Ohio State: white), ink becomes the team's dark colour, scale 1.07 from
  its left edge, soft glow. No ring elsewhere.
- Right, right-aligned: `meta` (26 sp, medium), one line, ellipsis; live adds a red lamp dot before it.
- No pick: no stub, `meta` alone. The stub is the only focus target in the hero.

## 5. The full ticket (dialog, replaces `PickDialog`)

1060 wide, centred, over an 80% dark veil. Left stub 286 wide in the team colour (gold / scarlet) with dark ink: "✦
Claude's pick" (24), `big` (condensed 132, weight 900), `line` (condensed 60), and at the bottom, above a thin rule,
`short` (24, two lines max). A dashed perforation and two notches separate it from the body (`#06142E`): `title` (30,
bold; the part after " · " in secondary), the paragraphs (24 sp / 34), then a dashed rule and the footer: `stakes` in the
accent colour left, "Back to close" right. A long pick steps the body type down (24/34 to 22/28) to fit; if it still
does not fit, the body scrolls with the D-pad. Back or OK closes.

## 6. OLED and states

- The surface, stub and meta drift with the hero text (same `drifting` modifier). Nothing bright is fixed.
- DIM: outline and lettering in `OledTokens.DIM_TEXT`, no fills, no glow, no lamp, stub not focusable (as PR #45).
- Quiet mode: unchanged rules; the board has no personal data.
- Contrast: text at ordinary sizes (stub, meta, ticket body) at 7:1 or better. The display lettering (abbreviations,
  scores, the ticket's big pick, 40 sp and up) has a floor of 4.5:1, the AAA level for large text; the approved colours
  give 5.96 to 6.78 there (designer decision, 2026-11-13; pinned in `GlanceTokensTest`).
- Reduced motion: there is no animation in this change.

## 7. Acceptance

1. Preds game day, pick present: frames 1 and 2. Text on the stub equals `Picks.text`.
2. OK on the stub: frame 3 with tonight's real pick fixture; a 900-character analysis fits.
3. Live: frame 4; final: scores with "Final".
4. Ohio State: frame 5.
5. No opponent abbreviation, or too little room: today's text card, pixel-for-pixel behaviour of main.
6. Back from the home screen still leaves in one press with the stub focused or not; Up from any apps-row tile reaches
   the stub and Down returns to the same tile.
7. DIM frame shows outline only.
8. Emulator tour screenshots added for 1, 2, 3, 4, 5 and DIM in the `emulator` job.
