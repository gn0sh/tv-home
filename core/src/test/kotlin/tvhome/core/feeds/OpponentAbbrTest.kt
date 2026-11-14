package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/** Game-day scoreboard, data: the opponent's abbreviation from both feeds, its rule, and the games cache. */
class OpponentAbbrTest {
    private val now = Instant.parse("2026-11-13T23:00:00Z")

    @Test fun ruleTable() {
        val rows = listOf(
            "VGK" to "VGK", "vgk" to "VGK", "Mich" to "MICH", "SJ" to "SJ", "T1" to "T1", "UC12" to "UC12",
            "" to null, "A" to null, "TEXAS" to null, "V G" to null, " VGK" to null, "VG-" to null, "É12" to null,
            "<b>" to null, "VGK\n" to null,
        )
        for ((raw, want) in rows) assertEquals(want, GameInfo.abbr(raw), "'$raw'")
        assertNull(GameInfo.abbr(null))
    }

    @Test fun teamsHaveTheirOwn() {
        assertEquals("NSH", Teams.PREDATORS.abbr)
        assertEquals("OSU", Teams.OHIO_STATE.abbr)
        for (t in Teams.ALL) assertEquals(t.abbr, GameInfo.abbr(t.abbr), "${t.key}'s abbreviation passes the feed rule")
    }

    private fun nhl(home: String, away: String) = """{"games":[{"startTimeUTC":"2026-11-14T03:00:00Z","gameState":"FUT",
        "homeTeam":{"abbrev":$home,"commonName":{"default":"Golden Knights"}},
        "awayTeam":{"abbrev":$away,"commonName":{"default":"Predators"}}}]}"""

    @Test fun nhlAbbrev() {
        val g = Schedules.parse(Teams.PREDATORS, nhl("\"VGK\"", "\"NSH\""), now)!!.single()
        assertEquals("Golden Knights", g.opponent)
        assertFalse(g.home)
        assertEquals("VGK", g.opponentAbbr)
        // The opponent is home here, so the away side is ours; lower case is upper-cased.
        assertEquals("VGK", Schedules.parse(Teams.PREDATORS, nhl("\"vgk\"", "\"NSH\""), now)!!.single().opponentAbbr)
        // A value outside the rule keeps the game and drops the abbreviation.
        val odd = Schedules.parse(Teams.PREDATORS, nhl("\"VEGAS!\"", "\"NSH\""), now)!!.single()
        assertEquals("Golden Knights", odd.opponent)
        assertNull(odd.opponentAbbr)
    }

    private fun espn(oppTeam: String) = """{"events":[{"date":"2026-11-07T19:30Z","competitions":[{"date":"2026-11-07T19:30Z",
        "status":{"type":{"state":"pre"}},
        "competitors":[{"homeAway":"home","team":{"id":"194","shortDisplayName":"Ohio State","abbreviation":"OSU"}},
                       {"homeAway":"away","team":{"id":"130","shortDisplayName":"Michigan"$oppTeam}}]}]}]}"""

    @Test fun espnAbbreviation() {
        val at = Instant.parse("2026-11-07T16:00:00Z")
        assertEquals("MICH", Schedules.parse(Teams.OHIO_STATE, espn(""","abbreviation":"MICH""""), at)!!.single().opponentAbbr)
        assertNull(Schedules.parse(Teams.OHIO_STATE, espn(""), at)!!.single().opponentAbbr, "no abbreviation in the feed")
        assertNull(Schedules.parse(Teams.OHIO_STATE, espn(""","abbreviation":"MICHIGAN""""), at)!!.single().opponentAbbr)
        assertNull(Schedules.parse(Teams.OHIO_STATE, espn(""","abbreviation":7"""), at)!!.single().opponentAbbr, "not a string")
    }

    private val serializer = PanelCaches.games(Teams.ALL)
    private fun game(abbr: String?) =
        TeamGame(Teams.PREDATORS, GameInfo(Instant.parse("2026-11-14T03:00:00Z"), "Golden Knights", false, GamePhase.UPCOMING, null, null, null, listOf("ESPN+"), abbr))

    @Test fun cacheRoundTrip() {
        for (abbr in listOf("VGK", null)) {
            val text = PanelCacheCodec.encode(Stamped(listOf(game(abbr)), now), serializer)
            assertEquals(listOf(game(abbr)), PanelCacheCodec.decode(text, serializer, now)!!.value)
        }
    }

    @Test fun cacheWrittenBeforeTheScoreboardStillParses() {
        val text = PanelCacheCodec.encode(Stamped(listOf(game("VGK")), now), serializer)
        assertTrue("\"opponentAbbr\":\"VGK\"" in text, text)
        val old = text.replace(",\"opponentAbbr\":\"VGK\"", "")
        assertFalse("opponentAbbr" in old)
        assertEquals(listOf(game(null)), PanelCacheCodec.decode(old, serializer, now)!!.value)
    }

    /** The file is never trusted: the parse rule is applied again, and a bad value costs the abbreviation only. */
    @Test fun cacheReappliesTheRule() {
        val text = PanelCacheCodec.encode(Stamped(listOf(game("VGK")), now), serializer)
        for (bad in listOf("<script>", "TOOLONG", "V", "v g")) {
            val got = PanelCacheCodec.decode(text.replace("\"opponentAbbr\":\"VGK\"", "\"opponentAbbr\":\"$bad\""), serializer, now)
            assertEquals(listOf(game(null)), got!!.value, bad)
        }
        val lower = PanelCacheCodec.decode(text.replace("\"opponentAbbr\":\"VGK\"", "\"opponentAbbr\":\"vgk\""), serializer, now)
        assertEquals("VGK", lower!!.value.single().game.opponentAbbr)
    }
}
