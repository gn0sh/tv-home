package tvhome.core.feeds

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.ui.ContextCard
import tvhome.core.ui.contextCard
import java.time.Instant
import java.time.ZoneId

/** Claude's game-day pick on the hero card (Brian 2026-11-13). */
class PicksTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = Instant.parse("2026-11-13T23:30:00Z") // Fri 5:30 PM CT
    private val game = TeamGame(Teams.PREDATORS, GameInfo(Instant.parse("2026-11-14T01:00:00Z"), "Stars", true, GamePhase.UPCOMING, null, null, null))

    private val preds = """{"v":1,"gameId":2025020245,"date":"2026-11-13","opponent":"DAL","pick":"NSH","score":"4-2",
        "puckLine":"NSH -1.5","reason":"Saros is 7-1 against Dallas at home.","at":"2026-11-13T22:45:00Z"}"""
    private val buckeyes = """{"v":1,"gameId":401752900,"date":"2026-11-14","opponent":"NU","pick":"OSU","score":"38-10",
        "spread":"OSU -24.5","reason":"The defense has allowed 9 points a game.","at":"2026-11-14T14:30:00Z"}"""

    @Test fun parsesBothShapes() {
        val p = Picks.parse(preds)!!
        assertEquals("NSH", p.pick); assertEquals("4-2", p.score); assertEquals("NSH -1.5", p.line); assertEquals("DAL", p.opponent)
        val b = Picks.parse(buckeyes)!!
        assertEquals("OSU -24.5", b.line)
        assertEquals("Claude's pick: NSH 4-2 (-1.5)", Picks.text(p)) // the reasoning is behind OK, never on the card
        assertEquals("Claude's pick: NSH 4-2 (-1.5)", Picks.text(p.copy(short = "Saros owns Dallas.")))
        assertEquals("Claude's pick vs DAL: NSH 4-2 (NSH -1.5)\n\nLong write-up.", Picks.full(p.copy(analysis = "Long write-up.")))
    }

    @Test fun onlyTheRequiredFieldsAreNeeded() {
        val p = Picks.parse("""{"v":1,"date":"2026-11-13","pick":"NSH","reason":"Home ice","at":"2026-11-13T22:45:00Z"}""")!!
        assertEquals("Claude's pick: NSH", Picks.text(p))
        assertEquals("Claude's pick: NSH\n\nHome ice", Picks.full(p))
    }

    @Test fun anythingOddIsIgnoredWhole() {
        val bad = listOf(
            "", "[]", "not json",
            preds.replace("\"v\":1", "\"v\":2"),
            preds.replace("\"pick\":\"NSH\",", ""),
            preds.replace("Saros is", "<script>Saros is"),
            preds.replace("Saros is", "x".repeat(200)),
            preds.replace("\"score\":\"4-2\"", "\"score\":42"),
            preds.replace("2026-11-13T22:45:00Z", "yesterday"),
            preds.replace("\"opponent\":\"DAL\"", "\"opponent\":\"\""),
            preds + " ".repeat(5000),
        )
        for (raw in bad) assertNull(Picks.parse(raw), raw.take(80))
    }

    @Test fun shownOnlyForTodaysGameAndWhileFresh() {
        val p = Picks.parse(preds)!!
        assertEquals(p, Picks.forGame(p, game, now, zone))
        assertNull(Picks.forGame(p.copy(date = p.date.minusDays(1)), game, now, zone))
        assertNull(Picks.forGame(p.copy(at = now.minusSeconds(37 * 3600)), game, now, zone))
        assertNull(Picks.forGame(p.copy(at = now.plusSeconds(3600)), game, now, zone))
    }

    @Test fun theCardCarriesThePickUntilTheGameIsFinal() {
        val picks = mapOf(Teams.PREDATORS.key to Picks.parse(preds)!!)
        val card = contextCard(null, game, now, zone, false, picks = picks) as ContextCard.Game
        assertTrue(card.pick!!.startsWith("Claude's pick: NSH 4-2"))
        assertEquals("Claude's pick vs DAL: NSH 4-2 (NSH -1.5)\n\nSaros is 7-1 against Dallas at home.", card.pickFull)
        val final = game.copy(game = game.game.copy(phase = GamePhase.FINAL))
        assertNull((contextCard(null, final, now, zone, false, picks = picks) as ContextCard.Game).pick)
        assertNull((contextCard(null, game, now, zone, false) as ContextCard.Game).pick)
    }

    @Test fun onlyTheTwoPickFilesAreAllowedOnGithub() {
        val ok = HttpFeedFetcher.isAllowed(Picks.url(Teams.PREDATORS.key)!!) && HttpFeedFetcher.isAllowed(Picks.url(Teams.OHIO_STATE.key)!!)
        assertTrue(ok)
        for (u in listOf(
            "https://raw.githubusercontent.com/gn0sh/tv-home/main/README.md",
            "https://raw.githubusercontent.com/someone/else/refs/heads/claude/project-thread-3aau8u/preds-latest.json",
            "https://raw.githubusercontent.com/gn0sh/tv-home/refs/heads/claude/project-thread-3aau8u/other.json",
            "https://raw.githubusercontent.com/gn0sh/tv-home/refs/heads/main/preds-latest.json",
            "https://raw.githubusercontent.com/gn0sh/tv-home/refs/heads/claude/project-thread-3aau8u/preds-latest.json?x=1",
            "http://raw.githubusercontent.com/gn0sh/tv-home/refs/heads/claude/project-thread-3aau8u/preds-latest.json",
        )) assertFalse(HttpFeedFetcher.isAllowed(u.toHttpUrl()), u)
        assertEquals(Picks.MAX_BODY, HttpFeedFetcher.maxBodyFor(Picks.url(Teams.PREDATORS.key)!!))
    }

    @Test fun picksAreFetchedOnlyForTeamsPlayingToday() {
        val osuTomorrow = TeamGame(Teams.OHIO_STATE, GameInfo(Instant.parse("2026-11-14T17:00:00Z"), "Northwestern", true, GamePhase.UPCOMING, null, null, null))
        assertEquals(setOf(Teams.PREDATORS.key), FeedTiming.pickTeams(listOf(game, osuTomorrow), now))
        val final = game.copy(game = game.game.copy(phase = GamePhase.FINAL))
        assertEquals(emptySet<String>(), FeedTiming.pickTeams(listOf(final), now))
    }
}

class PicksCardFitTest {
    @Test fun tonightsRealFileFitsTwoLines() {
        val raw = """{"v":1,"date":"2026-11-13","opponent":"VGK","pick":"NSH","puckLine":"NSH +1.5","reason":"Preds have won 6 straight and Saros (.919) tops Hill (.903). Vegas is favored at home, but Nashville is the better bet tonight.","at":"2026-11-14T00:25:00Z","short":"6 straight wins, Saros sharper"}"""
        val text = Picks.text(Picks.parse(raw)!!)
        assertEquals("Claude's pick: NSH (+1.5)", text)
        assertTrue(text.length <= 74, "two card lines of about 37 characters: ${text.length}")
    }

    @Test fun aShortLongerThanTheCardAllowsDropsTheFile() {
        val raw = """{"v":1,"date":"2026-11-13","pick":"NSH","reason":"r","at":"2026-11-14T00:25:00Z","short":"${"x".repeat(41)}"}"""
        assertNull(Picks.parse(raw))
    }
}

class PicksAnalysisTest {
    @Test fun analysisUpToTheCapIsKeptLongerDropsTheFile() {
        fun raw(n: Int) = "{\"v\":1,\"date\":\"2026-11-13\",\"pick\":\"NSH\",\"reason\":\"r\",\"at\":\"2026-11-14T00:25:00Z\",\"analysis\":\"" +
            "a".repeat(n) + "\"}"
        assertEquals(Picks.MAX_ANALYSIS, Picks.parse(raw(Picks.MAX_ANALYSIS))!!.analysis!!.length)
        assertNull(Picks.parse(raw(Picks.MAX_ANALYSIS + 1)))
    }
}
