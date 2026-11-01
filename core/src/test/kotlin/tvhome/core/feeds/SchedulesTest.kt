package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/** Fixtures follow the shapes of the unofficial NHL and ESPN JSON as we know them; unverified against live responses. */
class SchedulesTest {
    private fun nhlGame(state: String, home: String = "NSH", away: String = "STL", hs: String = "", ays: String = "",
                        period: String = "", start: String = "2026-11-02T01:00:00Z") =
        """{"id":2025020123,"gameType":2,"gameDate":"2026-11-01","startTimeUTC":"$start","gameState":"$state",
        "homeTeam":{"id":18,"abbrev":"$home","commonName":{"default":"${if (home == "NSH") "Predators" else "Blues"}"},$hs"logo":"x"},
        "awayTeam":{"id":19,"abbrev":"$away","commonName":{"default":"${if (away == "NSH") "Predators" else "Blues"}"},$ays"logo":"y"}$period}"""

    private fun nhl(vararg g: String) = """{"previousStartDate":"2026-10-25","games":[${g.joinToString(",")}]}"""
    private val nsh = Teams.PREDATORS
    private val osu = Teams.OHIO_STATE

    @Test fun nhlUpcomingHome() {
        val g = Schedules.parse(nsh, nhl(nhlGame("FUT")))!!.single()
        assertEquals(GamePhase.UPCOMING, g.phase)
        assertEquals("Blues", g.opponent); assertTrue(g.home)
        assertEquals(Instant.parse("2026-11-02T01:00:00Z"), g.start)
        assertNull(g.ownScore); assertNull(g.period)
    }

    @Test fun nhlLiveAway() {
        val g = Schedules.parse(nsh, nhl(nhlGame("LIVE", home = "STL", away = "NSH", hs = "\"score\":1,", ays = "\"score\":2,",
            period = ""","periodDescriptor":{"number":2,"periodType":"REG"}""")))!!.single()
        assertEquals(GamePhase.LIVE, g.phase); assertFalse(g.home)
        assertEquals(2, g.ownScore); assertEquals(1, g.oppScore); assertEquals("2nd", g.period)
    }

    @Test fun nhlFinalOvertime() {
        val g = Schedules.parse(nsh, nhl(nhlGame("OFF", hs = "\"score\":4,", ays = "\"score\":3,",
            period = ""","periodDescriptor":{"number":4,"periodType":"OT"}""")))!!.single()
        assertEquals(GamePhase.FINAL, g.phase); assertEquals("OT", g.period); assertEquals(4, g.ownScore)
    }

    @Test fun nhlSkipsBadGamesKeepsGoodOnes() {
        val bad = listOf(
            nhlGame("FUT", start = "tomorrow"), nhlGame("WEIRD"), nhlGame("FUT", home = "ANA", away = "STL"),
            """{"gameState":"FUT"}""", "7",
        )
        val out = Schedules.parse(nsh, nhl(*bad.toTypedArray(), nhlGame("FUT")))!!
        assertEquals(1, out.size)
    }

    @Test fun malformedDocumentsAreNull() {
        for (t in listOf(nsh, osu)) for (bad in listOf("", "x", "[]", "{}", """{"games":5}""", """{"events":"x"}"""))
            if (!(t === nsh && bad == "{}") ) assertNull(Schedules.parse(t, bad), "${t.key}: $bad")
    }

    private fun espn(state: String, status: String = "", period: Int = 0, clock: String = "0:00", name: String = "STATUS_SCHEDULED",
                     ownScore: String = "", oppScore: String = "", date: String = "2026-11-01T17:00Z", ownHome: Boolean = true) =
        """{"events":[{"id":"1","date":"$date","competitions":[{"date":"$date",
        "competitors":[{"homeAway":"${if (ownHome) "home" else "away"}","team":{"id":"194","shortDisplayName":"Ohio State","displayName":"Ohio State Buckeyes"}$ownScore},
        {"homeAway":"${if (ownHome) "away" else "home"}","team":{"id":"130","shortDisplayName":"Michigan","displayName":"Michigan Wolverines"}$oppScore}],
        "status":{"displayClock":"$clock","period":$period,"type":{"name":"$name","state":"$state"}}}]}]}"""

    @Test fun espnUpcomingWithSecondlessDate() {
        val g = Schedules.parse(osu, espn("pre"))!!.single()
        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), g.start)
        assertEquals("Michigan", g.opponent); assertTrue(g.home); assertEquals(GamePhase.UPCOMING, g.phase)
    }

    @Test fun espnLiveScoreboardStringScores() {
        val g = Schedules.parse(osu, espn("in", period = 3, clock = "8:12", name = "STATUS_IN_PROGRESS",
            ownScore = ""","score":"21"""", oppScore = ""","score":"14""""))!!.single()
        assertEquals(GamePhase.LIVE, g.phase); assertEquals(21, g.ownScore); assertEquals(14, g.oppScore); assertEquals("Q3 8:12", g.period)
    }

    @Test fun espnFinalScheduleObjectScoresAndOvertime() {
        val g = Schedules.parse(osu, espn("post", period = 5, name = "STATUS_FINAL", ownHome = false,
            ownScore = ""","score":{"value":24.0,"displayValue":"24"}""", oppScore = ""","score":{"value":27.0,"displayValue":"27"}"""))!!.single()
        assertEquals(GamePhase.FINAL, g.phase); assertFalse(g.home); assertEquals(24, g.ownScore); assertEquals(27, g.oppScore); assertEquals("OT", g.period)
    }

    @Test fun espnHalftimeAndHostileClock() {
        assertEquals("Half", Schedules.parse(osu, espn("in", period = 2, name = "STATUS_HALFTIME"))!!.single().period)
        assertEquals("Q1", Schedules.parse(osu, espn("in", period = 1, clock = "<script>"))!!.single().period)
    }

    @Test fun espnOtherTeamsGameAndBadFieldsAreSkipped() {
        assertEquals(0, Schedules.parse(Teams.OHIO_STATE.copy(feedId = "99"), espn("pre"))!!.size)
        assertEquals(0, Schedules.parse(osu, espn("pre", date = "soon"))!!.size)
        assertEquals(0, Schedules.parse(osu, espn("weird"))!!.size)
    }

    @Test fun opponentNamesAreCappedAndStripped() {
        val long = "A".repeat(100)
        val raw = nhlGame("FUT").replace("Blues", "\u0007$long")
        val g = Schedules.parse(nsh, nhl(raw))!!.single()
        assertEquals(Feeds.LABEL_CAP, g.opponent.length)
        assertFalse(g.opponent.any { it.isISOControl() })
    }
}
