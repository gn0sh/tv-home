package tvhome.core.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.feeds.GameDay
import tvhome.core.feeds.GameInfo
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.Pick
import tvhome.core.feeds.Picks
import tvhome.core.feeds.TeamGame
import tvhome.core.feeds.Teams
import tvhome.core.feeds.WatchMap
import tvhome.core.feeds.WatchTarget
import tvhome.core.ui.Scoreboard.Center
import tvhome.core.ui.Scoreboard.Surface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Game-day scoreboard (docs/design/tv-home-game-day-scoreboard.md section 1): the board model and the ticket text. */
class ScoreboardTest {
    private val zone = ZoneId.of("America/Chicago")
    private val fri = LocalDate.of(2026, 11, 13)
    private fun local(h: Int, m: Int = 0, day: LocalDate = fri) = day.atTime(h, m).atZone(zone).toInstant()
    private val now = local(19, 1)

    private fun preds(
        phase: GamePhase = GamePhase.UPCOMING, home: Boolean = false, abbr: String? = "VGK", own: Int? = null, opp: Int? = null,
        period: String? = null, nets: List<String> = listOf("ESPN+"), start: Instant = local(21),
    ) = TeamGame(Teams.PREDATORS, GameInfo(start, "Golden Knights", home, phase, own, opp, period, nets, abbr))

    private val map = WatchMap.parse("ESPN+=com.espn.gtv", null)
    private val pill = WatchTarget("ESPN+", "com.espn.gtv")

    private fun card(g: TeamGame, at: Instant = now, watch: WatchTarget? = pill, is24h: Boolean = false, picks: Map<String, Pick> = emptyMap(), m: WatchMap = map) =
        contextCard(null, GameDay.select(listOf(g), at, zone), at, zone, is24h, null, m, watch, null, picks) as ContextCard.Game

    @Test fun upcomingAwayWithThePill() {
        val c = card(preds())
        assertEquals(Scoreboard(Surface.RINK, "NSH", "VGK", Center.Time("9:00", "PM"), "Away · in 1h 59m", live = false), c.board)
        // The line is still the whole sentence: it is the content description.
        assertEquals(GameDay.line(preds(), now, zone) + " · ESPN+", c.line)
    }

    @Test fun upcomingHome() {
        assertEquals("Home · in 1h 59m", card(preds(home = true)).board!!.meta)
    }

    @Test fun networkOnlyWithoutThePill() {
        // No pill (the app is not installed): the mapped network is named on the board.
        assertEquals("Away · in 1h 59m · ESPN+", card(preds(), watch = null).board!!.meta)
        // No pill and nothing mapped: what the feed lists, as the text card says it.
        assertEquals("Away · in 1h 59m · TV: FanDuel", card(preds(nets = listOf("FanDuel")), watch = null, m = WatchMap.EMPTY).board!!.meta)
        assertEquals("Away · in 1h 59m · " + GameDay.TV_NOT_ANNOUNCED, card(preds(nets = emptyList()), watch = null, m = WatchMap.EMPTY).board!!.meta)
        // With the pill the board never names it.
        assertFalse("ESPN" in card(preds()).board!!.meta)
    }

    @Test fun twentyFourHourClock() {
        assertEquals(Center.Time("21:00", null), card(preds(), is24h = true).board!!.center)
        assertEquals(Center.Time("09:05", null), card(preds(start = local(9, 5)), at = local(8), is24h = true).board!!.center)
    }

    @Test fun live() {
        val at = local(22, 7)
        val b = card(preds(GamePhase.LIVE, own = 2, opp = 1, period = "2nd"), at).board!!
        assertEquals(Scoreboard(Surface.RINK, "NSH", "VGK", Center.Score(own = 2, opp = 1), "2nd", live = true), b)
        assertEquals("live", card(preds(GamePhase.LIVE, own = 0, opp = 0), at).board!!.meta, "period unknown")
        assertEquals("2nd · ESPN+", card(preds(GamePhase.LIVE, own = 2, opp = 1, period = "2nd"), at, watch = null).board!!.meta)
    }

    @Test fun liveWithNoScoresYetKeepsTheTime() {
        val at = local(21, 2)
        assertEquals(Center.Time("9:00", "PM"), card(preds(GamePhase.LIVE, period = "1st"), at).board!!.center)
        assertEquals(Center.Time("9:00", "PM"), card(preds(GamePhase.LIVE, own = 1, period = "1st"), at).board!!.center, "one score is not a score")
    }

    @Test fun finalNeverNamesTheNetwork() {
        val at = local(23, 40)
        val b = card(preds(GamePhase.FINAL, own = 3, opp = 2), at, watch = null).board!!
        assertEquals(Scoreboard(Surface.RINK, "NSH", "VGK", Center.Score(3, 2), "Final", live = false), b)
        assertEquals("Final (OT)", card(preds(GamePhase.FINAL, own = 3, opp = 2, period = "OT"), at, watch = null).board!!.meta)
    }

    @Test fun ohioStateIsAField() {
        val sat = LocalDate.of(2026, 11, 7)
        val at = local(11, 1, sat)
        val g = TeamGame(Teams.OHIO_STATE, GameInfo(local(14, 30, sat), "Michigan", true, GamePhase.UPCOMING, null, null, null, listOf("FOX"), "MICH"))
        val c = contextCard(null, g, at, zone, false, watch = WatchTarget("FOX", "com.fox")) as ContextCard.Game
        assertEquals(Scoreboard(Surface.FIELD, "OSU", "MICH", Center.Time("2:30", "PM"), "Home · in 3h 29m", live = false), c.board)
        val live = g.copy(game = g.game.copy(phase = GamePhase.LIVE, ownScore = 14, oppScore = 7, period = "Q3 8:12"))
        assertEquals("Q3 8:12", (contextCard(null, live, local(16, 0, sat), zone, false, watch = WatchTarget("FOX", "com.fox")) as ContextCard.Game).board!!.meta)
    }

    @Test fun noAbbreviationKeepsTheTextCard() {
        val with = card(preds())
        val without = card(preds(abbr = null))
        assertNull(without.board)
        assertNull(without.ticket)
        // Everything else is what the card was before the board existed.
        assertEquals(with.copy(board = null, ticket = null), without)
        // A team with no abbreviation of its own has no board either.
        val other = preds().copy(team = Teams.PREDATORS.copy(abbr = null))
        assertNull((contextCard(null, other, now, zone, false) as ContextCard.Game).board)
    }

    @Test fun nextGameCardIsAlwaysText() {
        val tomorrow = preds(start = local(19, 0, fri.plusDays(1)))
        val c = contextCard(null, null, now, zone, false, next = tomorrow) as ContextCard.Game
        assertEquals(ContextCard.Game.NEXT_GAME, c.heading)
        assertNull(c.board)
    }

    // --- The pick: the stub's words and the full ticket ---------------------------------------------------------

    private val analysis = "Nashville comes into Vegas on a six-game win streak and is 7-1-2 in its last 10, with a +6 goal differential. " +
        "Vegas is good too: 13-5-2, +15, four straight wins, and 7-3 at home. The edge is in goal: Juuse Saros is at .919 this season " +
        "against Adin Hill at .903 (neither confirmed at pick time). Vegas is missing defenseman Noah Hanifin; Jonathan Marchessault is " +
        "day-to-day for Nashville. The books opened Vegas at -163 and moved to -180, which prices Vegas near a 62% win chance. With the " +
        "hotter team and the better goalie, the Preds at +155 are worth the risk, and Nashville +1.5 only needs them to lose by no more " +
        "than one goal. Pretend stakes: \$50 on the Preds to win, \$60 on Nashville +1.5."

    private fun pick(line: String? = "NSH +1.5", score: String? = null, short: String? = "6 straight wins, Saros sharper", analysis: String? = this.analysis, reason: String = "Hotter team, better goalie.") =
        Pick(fri, "VGK", "NSH", score, line, reason, local(19, 0), short, analysis)

    @Test fun stubTextIsPicksTextAndTheTicketComesWithTheBoard() {
        val p = pick()
        val c = card(preds(), picks = mapOf(Teams.PREDATORS.key to p))
        assertEquals(Picks.text(p), c.pick)
        assertEquals("Claude's pick: NSH (+1.5)", c.pick)
        assertEquals(TicketText.ticket(p, preds(), zone), c.ticket)
        assertEquals(Picks.full(p), c.pickFull, "the text card's pop-up text is unchanged")
        // No board: the pick is on the text card as before, with no ticket.
        val text = card(preds(abbr = null), picks = mapOf(Teams.PREDATORS.key to p))
        assertEquals(Picks.text(p), text.pick)
        assertNull(text.ticket)
        // A final has a board and no pick.
        val final = card(preds(GamePhase.FINAL, own = 3, opp = 2), local(23, 40), picks = mapOf(Teams.PREDATORS.key to p))
        assertNotNull(final.board)
        assertNull(final.pick); assertNull(final.ticket)
    }

    @Test fun ticketTitle() {
        assertEquals("Predators at Golden Knights · Tonight, 9:00 PM", TicketText.title(preds(), zone))
        assertEquals("Predators vs Golden Knights · Tonight, 5:00 PM", TicketText.title(preds(home = true, start = local(17)), zone))
        assertEquals("Predators at Golden Knights · Today, 4:59 PM", TicketText.title(preds(start = local(16, 59)), zone))
        assertEquals("Predators at Golden Knights · Tonight, 21:00", TicketText.title(preds(), zone, is24h = true))
        assertEquals("Predators at Golden Knights · Today, 11:00", TicketText.title(preds(start = local(11)), zone, is24h = true))
    }

    @Test fun ticketBigLineShort() {
        class Row(val p: Pick, val big: String, val line: String?, val short: String?)
        val rows = listOf(
            Row(pick(), "NSH", "+1.5", "6 straight wins, Saros sharper"),
            Row(pick(line = "NSH -1.5", score = "4-2"), "NSH", "-1.5", "6 straight wins, Saros sharper"),
            Row(pick(line = "VGK -1.5"), "NSH", "VGK -1.5", "6 straight wins, Saros sharper"), // the line names the other side: kept whole
            Row(pick(line = null, score = "4-2", short = null), "NSH", "4-2", null),               // no line: the score
            Row(pick(line = null, score = null), "NSH", null, "6 straight wins, Saros sharper"),
            Row(pick(line = "NSH "), "NSH", null, "6 straight wins, Saros sharper"),
        )
        for (r in rows) {
            assertEquals(r.big, TicketText.big(r.p))
            assertEquals(r.line, TicketText.line(r.p), "${r.p.line} ${r.p.score}")
            assertEquals(r.short, TicketText.short(r.p))
        }
    }

    @Test fun stakesAreCutFromTheBodyAtTheLastOccurrence() {
        val (body, stakes) = TicketText.bodyAndStakes(pick())
        assertEquals("Pretend stakes: \$50 on the Preds to win, \$60 on Nashville +1.5.", stakes)
        assertTrue(body.endsWith("no more than one goal."), body)
        assertFalse("Pretend stakes" in body)

        val twice = TicketText.bodyAndStakes(pick(analysis = "First. Pretend stakes: none yet. Then more. Pretend stakes: \$5 on NSH."))
        assertEquals("First. Pretend stakes: none yet. Then more." to "Pretend stakes: \$5 on NSH.", twice)
        // No stakes: the whole text is the body. No analysis: the reason.
        assertEquals("Just a hunch." to null, TicketText.bodyAndStakes(pick(analysis = "Just a hunch.")))
        assertEquals("Hotter team, better goalie." to null, TicketText.bodyAndStakes(pick(analysis = null)))
        // Stakes and nothing else: the body is never empty.
        assertEquals("Pretend stakes: \$5 on NSH." to null, TicketText.bodyAndStakes(pick(analysis = "Pretend stakes: \$5 on NSH.")))
    }

    @Test fun bodySplitsAtTheSentenceEndNearestTheMiddle() {
        val (body, _) = TicketText.bodyAndStakes(pick())
        val ps = TicketText.paragraphs(body)
        assertEquals(2, ps.size)
        // The mockup's split (frame 3).
        assertTrue(ps[0].startsWith("Nashville comes into Vegas") && ps[0].endsWith("(neither confirmed at pick time)."), ps[0])
        assertTrue(ps[1].startsWith("Vegas is missing defenseman") && ps[1].endsWith("no more than one goal."), ps[1])
        assertEquals(body, ps.joinToString(" "), "nothing is lost but the one space")
    }

    @Test fun shortOrUnsplittableBodyIsOneParagraph() {
        val short = "Hotter team. Better goalie. Take the Preds."
        assertEquals(listOf(short), TicketText.paragraphs(short))
        val under = "A".repeat(99) + ". " + "B".repeat(98)
        assertEquals(199, under.length)
        assertEquals(listOf(under), TicketText.paragraphs(under), "under 200 characters")
        val at = "A".repeat(99) + ". " + "B".repeat(99)
        assertEquals(listOf("A".repeat(99) + ".", "B".repeat(99)), TicketText.paragraphs(at), "200 characters split")
        val noPoint = "a".repeat(120) + ". " + "b".repeat(120) + ", " + "c".repeat(60)
        assertEquals(listOf(noPoint), TicketText.paragraphs(noPoint), "a sentence end must be followed by a capital")
        val marks = "A".repeat(60) + "! " + "B".repeat(100) + "? " + "C".repeat(100)
        assertEquals(listOf("A".repeat(60) + "! " + "B".repeat(100) + "?", "C".repeat(100)), TicketText.paragraphs(marks))
    }

    @Test fun wholeTicket() {
        val t = TicketText.ticket(pick(), preds(), zone)
        assertEquals("Predators at Golden Knights · Tonight, 9:00 PM", t.title)
        assertEquals("NSH", t.big); assertEquals("+1.5", t.line); assertEquals("6 straight wins, Saros sharper", t.short)
        assertEquals(2, t.paragraphs.size)
        assertTrue(t.stakes!!.startsWith(TicketText.STAKES))
        val brief = TicketText.ticket(pick(analysis = null, short = null), preds(), zone)
        assertEquals(listOf("Hotter team, better goalie."), brief.paragraphs)
        assertNull(brief.stakes); assertNull(brief.short)
    }

    @Test fun palettes() {
        assertEquals(BoardPalette.PREDATORS, BoardPalette.of(Teams.PREDATORS.key))
        assertEquals(BoardPalette.OHIO_STATE, BoardPalette.of(Teams.OHIO_STATE.key))
        assertNull(BoardPalette.of("nobody"))
        assertEquals(Teams.PREDATORS.accent, BoardPalette.PREDATORS.boards, "the boards carry the team accent")
        assertEquals(Teams.OHIO_STATE.primary, BoardPalette.OHIO_STATE.endZone)
    }
}
