package tvhome.core.feeds

import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.Fixtures
import tvhome.core.runSuspend
import tvhome.core.state.CacheFile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** M7-B8: the schedule adapter, its season fallback and disk cache, against real captured shapes. */
class GameSourcesTest {
    private val now0 = Instant.parse("2026-11-02T21:36:00Z")
    private var now = now0

    private class Mem(var text: String? = null) : CacheFile {
        var writes = 0
        override fun read(): String? = text
        override fun writeAtomic(text: String) { this.text = text; writes++ }
    }

    private class Scripted : FeedFetcher {
        val asked = mutableListOf<HttpUrl>()
        var week: String? = null
        var season: String? = null
        override suspend fun fetch(url: HttpUrl): String? {
            asked += url
            return if ("club-schedule-season" in url.encodedPath) season else week
        }
        fun seasonCalls() = asked.count { "club-schedule-season" in it.encodedPath }
        fun weekCalls() = asked.count { "/week/" in it.encodedPath }
    }

    private fun nhlDoc(vararg starts: String) = """{"games":[${starts.joinToString(",") { s ->
        """{"startTimeUTC":"$s","gameState":"FUT","homeTeam":{"abbrev":"NSH","commonName":{"default":"Predators"}},""" +
            """"awayTeam":{"abbrev":"STL","commonName":{"default":"Blues"}},""" +
            """"tvBroadcasts":[{"countryCode":"US","network":"ESPN+"},{"countryCode":"CA","network":"SN+"}]}"""
    }}]}"""

    private fun source(f: FeedFetcher, cache: CacheFile? = null, team: TeamConfig = Teams.PREDATORS) =
        LeagueSchedule(team, f, cache, Dispatchers.Unconfined)

    @Test fun nhlSeasonIdFollowsTheCalendar() {
        assertEquals("20262027", nhlSeason(LocalDate.parse("2026-11-02")))
        assertEquals("20262027", nhlSeason(LocalDate.parse("2027-02-10")))
        assertEquals("20262027", nhlSeason(LocalDate.parse("2026-07-01")))
        assertEquals("20252026", nhlSeason(LocalDate.parse("2026-06-30")))
        val u = Teams.PREDATORS.seasonUrl(now0)
        assertEquals("https://api-web.nhle.com/v1/club-schedule-season/NSH/20262027", u.toString())
        assertTrue(HttpFeedFetcher.isAllowed(u), "same host as the week feed: no new network host")
        assertEquals(Teams.OHIO_STATE.scheduleUrl(now0), Teams.OHIO_STATE.seasonUrl(now0), "ESPN: the team schedule is the season")
    }

    @Test fun liveGamesAreReturnedAndTheSeasonIsFetchedOncePerDay() {
        val f = Scripted().apply { week = nhlDoc("2026-11-04T01:00:00Z"); season = nhlDoc("2026-11-04T01:00:00Z", "2026-11-06T02:30:00Z") }
        val cache = Mem()
        val s = source(f, cache)
        val r = runSuspend { s.games(now) }!!
        assertFalse(r.fromFallback)
        assertEquals(1, r.games.size)
        assertEquals(listOf("ESPN+"), r.games[0].networks, "US broadcasters only")
        assertEquals(1, f.seasonCalls()); assertEquals(1, cache.writes)
        now = now.plus(Duration.ofHours(23)); runSuspend { s.games(now) }
        assertEquals(1, f.seasonCalls(), "still fresh at 23 h")
        now = now.plus(Duration.ofHours(2)); runSuspend { s.games(now) }
        assertEquals(2, f.seasonCalls(), "refreshed after 24 h")
        assertEquals(3, f.weekCalls())
    }

    @Test fun shapeChangeInTheLiveDocumentGivesGamesFromTheFallbackNotAThrow() {
        for (broken in listOf("""{"schedule":[]}""", """{"games":"nope"}""", "[]", "<html>", "{")) {
            val f = Scripted().apply { week = nhlDoc("2026-11-04T01:00:00Z"); season = nhlDoc("2026-11-04T01:00:00Z", "2026-11-06T02:30:00Z") }
            val s = source(f, Mem())
            assertFalse(runSuspend { s.games(now) }!!.fromFallback)
            now = now.plus(Duration.ofMinutes(30))
            f.week = broken
            val r = runSuspend { s.games(now) }!!
            assertTrue(r.fromFallback, "for $broken")
            assertEquals(2, r.games.size, "for $broken")
        }
    }

    @Test fun liveDownAndNothingCachedIsNullAndTheSeasonIsTriedOnce() {
        val f = Scripted().apply { season = nhlDoc("2026-11-04T01:00:00Z") }
        val r = runSuspend { source(f, Mem()).games(now) }!!
        assertTrue(r.fromFallback); assertEquals(1, r.games.size)
        val none = Scripted()
        assertNull(runSuspend { source(none, Mem()).games(now) })
    }

    @Test fun theSeasonCacheSurvivesARestartAndExpiresAfterAWeek() {
        val f = Scripted().apply { week = nhlDoc("2026-11-04T01:00:00Z"); season = nhlDoc("2026-11-04T01:00:00Z") }
        val disk = Mem()
        runSuspend { source(f, disk).games(now) }
        assertNotNull(disk.text)
        // New process, network down: the file alone gives the games.
        val down = Scripted()
        now = now.plus(Duration.ofHours(30))
        val r = runSuspend { source(down, disk).games(now) }!!
        assertTrue(r.fromFallback); assertEquals(1, r.games.size)
        // Older than a week: not trusted.
        val later = now.plus(Duration.ofDays(8))
        assertNull(runSuspend { source(Scripted(), disk).games(later) })
    }

    @Test fun aGarbageOrOversizeCacheFileIsNoCache() {
        for (text in listOf("not json", """{"v":2,"at":"2026-11-02T00:00:00Z","data":[]}""", "x".repeat(PanelCacheCodec.MAX_BYTES + 5))) {
            assertNull(runSuspend { source(Scripted(), Mem(text)).games(now) }, text.take(20))
        }
    }

    @Test fun espnTeamScheduleIsLiveAndSeasonAndIsCached() {
        val doc = Fixtures.text("espn-team-194.json")
        val f = Scripted().apply { week = doc }
        val disk = Mem()
        val september = Instant.parse("2026-09-04T12:00:00Z")
        val r = runSuspend { source(f, disk, Teams.OHIO_STATE).games(september) }!!
        assertFalse(r.fromFallback); assertEquals(2, r.games.size)
        assertEquals(1, f.asked.size, "one request: the live document is the season")
        // ESPN down: the cached, windowed season answers.
        val s2 = source(Scripted(), disk, Teams.OHIO_STATE)
        val r2 = runSuspend { s2.games(september.plus(Duration.ofHours(6))) }!!
        assertTrue(r2.fromFallback)
        assertEquals(listOf("BTN"), r2.games.first().networks)
    }

    @Test fun seasonWindowIsAppliedBeforeTheCapSoALongSeasonIsNotCutAtItsStart() {
        val starts = (0 until 90).map { Instant.parse("2026-10-01T01:00:00Z").plus(Duration.ofDays(it.toLong())).toString() }
        val doc = nhlDoc(*starts.toTypedArray())
        val games = Schedules.parseSeason(Teams.PREDATORS, doc, Instant.parse("2026-12-20T00:00:00Z"))!!
        assertTrue(games.isNotEmpty())
        assertTrue(games.all { it.start >= Instant.parse("2026-12-18T00:00:00Z") && it.start <= Instant.parse("2027-01-10T00:00:00Z") })
        // Without the window the first 64 rows would all be before Dec 4.
        assertEquals(12, games.size, "Dec 18 to Dec 29, the last game of the 90")
    }

    @Test fun realSeasonFixtureParsesInsideAndOutsideTheWindow() {
        val doc = Fixtures.text("nhl-season.json")
        assertEquals(2, Schedules.parseSeason(Teams.PREDATORS, doc, Instant.parse("2026-09-21T12:00:00Z"))!!.size)
        assertEquals(emptyList<GameInfo>(), Schedules.parseSeason(Teams.PREDATORS, doc, now0), "a valid season with no game in the window is empty, not null")
        assertNull(Schedules.parseSeason(Teams.PREDATORS, """{"nope":1}""", now0))
    }

    @Test fun networksAreCleanedCappedAndSurviveTheCacheCodec() {
        val names = (1..20).joinToString(",") { """{"countryCode":"US","network":"N$it\u0007${"x".repeat(60)}"}""" }
        val doc = """{"games":[{"startTimeUTC":"2026-11-04T01:00:00Z","gameState":"FUT","homeTeam":{"abbrev":"NSH"},"awayTeam":{"abbrev":"STL"},"tvBroadcasts":[$names]}]}"""
        val g = Schedules.parse(Teams.PREDATORS, doc, now0)!!.single()
        assertEquals(GameInfo.MAX_NETWORKS, g.networks.size)
        assertTrue(g.networks.all { it.length <= Feeds.LABEL_CAP && it.none { c -> c.isISOControl() } })
        val ser = PanelCaches.games(listOf(Teams.PREDATORS))
        val back = PanelCacheCodec.decode(PanelCacheCodec.encode(Stamped(listOf(TeamGame(Teams.PREDATORS, g)), now0), ser), ser, now0)!!
        assertEquals(g.networks, back.value.single().game.networks)
        // A planted file with too many networks is no cache.
        val tooMany = PanelCacheCodec.encode(Stamped(listOf(TeamGame(Teams.PREDATORS, g.copy(networks = List(9) { "A$it" }))), now0), ser)
        assertNull(PanelCacheCodec.decode(tooMany, ser, now0))
    }

    @Test fun espnScoreboardNamesAreRead() {
        val doc = Fixtures.text("espn-cfb-scoreboard.json")
        val all = Schedules.parse(Teams.OHIO_STATE, doc, now0)
        assertNotNull(all)
        val synthetic = """{"events":[{"date":"2026-11-07T17:00Z","competitions":[{"date":"2026-11-07T17:00Z",
          "competitors":[{"id":"194","homeAway":"home","team":{"id":"194"}},{"id":"9","homeAway":"away","team":{"id":"9","shortDisplayName":"Rival"}}],
          "status":{"type":{"state":"pre"}},"broadcasts":[{"market":"national","names":["CBSSN","FOX"]}]}]}]}"""
        assertEquals(listOf("CBSSN", "FOX"), Schedules.parse(Teams.OHIO_STATE, synthetic, now0)!!.single().networks)
    }

    @Test fun liveFeedsUsesTheFallbackButReportsAFailedRefresh() {
        val f = Scripted().apply { week = nhlDoc("2026-11-03T01:00:00Z"); season = nhlDoc("2026-11-03T01:00:00Z", "2026-11-05T01:00:00Z") }
        val lf = LiveFeeds(f, { null }, teams = listOf(Teams.PREDATORS), clock = { now }, io = Dispatchers.Unconfined)
        assertTrue(runSuspend { lf.refreshSchedules() })
        f.week = """{"schedule":[]}"""
        now = now.plus(Duration.ofMinutes(61))
        assertFalse(runSuspend { lf.refreshSchedules() }, "fallback games count as a failed refresh so the back-off applies")
        assertEquals(2, lf.snapshot.value.games!!.value.size, "and the games from the fallback are shown")
    }

    // --- review round: row-shape-change-no-fallback, fallback stamp, write churn, depth, body cap ----------------------

    @Test fun aFieldRenamedInEveryRowIsNullNotEmptyForBothLeagues() {
        val nhl = nhlDoc("2026-11-04T01:00:00Z", "2026-11-06T02:30:00Z").replace("startTimeUTC", "startTime")
        assertNull(Schedules.parse(Teams.PREDATORS, nhl, now0))
        assertNull(Schedules.parseSeason(Teams.PREDATORS, nhl, now0))
        val renamedState = nhlDoc("2026-11-04T01:00:00Z").replace("gameState", "state")
        assertNull(Schedules.parse(Teams.PREDATORS, renamedState, now0))
        val espn = Fixtures.text("espn-team-194.json").replace("\"competitors\"", "\"teams\"")
        assertNull(Schedules.parse(Teams.OHIO_STATE, espn, Instant.parse("2026-09-04T12:00:00Z")))
        assertNull(Schedules.parseSeason(Teams.OHIO_STATE, espn, Instant.parse("2026-09-04T12:00:00Z")))
        val espnDate = Fixtures.text("espn-team-194.json").replace("\"date\"", "\"when\"")
        assertNull(Schedules.parse(Teams.OHIO_STATE, espnDate, Instant.parse("2026-09-04T12:00:00Z")))
        // Legit empties stay non-null: no rows, rows that are valid but outside the window, other teams on a scoreboard.
        assertEquals(emptyList<GameInfo>(), Schedules.parse(Teams.PREDATORS, """{"games":[]}""", now0))
        assertEquals(emptyList<GameInfo>(), Schedules.parseSeason(Teams.PREDATORS, nhlDoc("2026-03-04T01:00:00Z"), now0))
    }

    @Test fun rowShapeDriftInTheLiveDocFallsBackAndNeverOverwritesTheCachedSeason() {
        for (team in listOf(Teams.PREDATORS)) {
            val f = Scripted().apply { week = nhlDoc("2026-11-04T01:00:00Z"); season = nhlDoc("2026-11-04T01:00:00Z", "2026-11-06T02:30:00Z") }
            val disk = Mem()
            val s = source(f, disk, team)
            runSuspend { s.games(now) }
            val saved = disk.text
            now = now.plus(Duration.ofHours(30))
            f.week = nhlDoc("2026-11-04T01:00:00Z").replace("startTimeUTC", "startTime")
            f.season = f.week
            val r = runSuspend { s.games(now) }!!
            assertTrue(r.fromFallback); assertEquals(2, r.games.size)
            assertEquals(saved, disk.text, "a drifted season document did not touch the cache")
        }
        // ESPN: live doc drifted in every row after a good fetch: cache kept, fallback used.
        val good = Fixtures.text("espn-team-194.json")
        val f = Scripted().apply { week = good }
        val disk = Mem(); val sep = Instant.parse("2026-09-04T12:00:00Z")
        val s = source(f, disk, Teams.OHIO_STATE)
        runSuspend { s.games(sep) }
        val saved = disk.text
        f.week = good.replace("\"competitors\"", "\"teams\"")
        val r = runSuspend { s.games(sep.plus(Duration.ofHours(2))) }!!
        assertTrue(r.fromFallback); assertEquals(2, r.games.size)
        assertEquals(saved, disk.text)
    }

    @Test fun anEmptySeasonNeverReplacesANonEmptyOne() {
        val f = Scripted().apply { week = nhlDoc("2026-11-04T01:00:00Z"); season = nhlDoc("2026-11-04T01:00:00Z") }
        val disk = Mem(); val s = source(f, disk)
        runSuspend { s.games(now) }
        val saved = disk.text
        now = now.plus(Duration.ofHours(25)); f.season = """{"games":[]}"""
        runSuspend { s.games(now) }
        assertEquals(saved, disk.text)
    }

    @Test fun fallbackGamesCarryTheCachesOwnStampNotTheClock() {
        val f = Scripted().apply { week = nhlDoc("2026-11-04T01:00:00Z"); season = nhlDoc("2026-11-04T01:00:00Z") }
        val s = source(f, Mem())
        runSuspend { s.games(now) }
        val fetched = now
        now = now.plus(Duration.ofHours(30)); f.week = "{"; f.season = null
        assertEquals(fetched, runSuspend { s.games(now) }!!.at)
        // And LiveFeeds stamps with it.
        val lf = LiveFeeds(f, { null }, teams = listOf(Teams.PREDATORS), clock = { now }, io = Dispatchers.Unconfined)
        f.week = nhlDoc("2026-11-04T01:00:00Z"); runSuspend { lf.refreshSchedules() }
        val goodAt = lf.snapshot.value.games!!.at
        now = now.plus(Duration.ofHours(2)); f.week = "{"
        runSuspend { lf.refreshSchedules() }
        assertEquals(goodAt, lf.snapshot.value.games!!.at, "a fallback refresh does not re-stamp the games as fresh")
    }

    @Test fun theEspnSeasonFileIsWrittenOnChangeOrOncePerDayNotEveryPoll() {
        val doc = Fixtures.text("espn-team-194.json")
        val f = Scripted().apply { week = doc }
        val disk = Mem(); val s = source(f, disk, Teams.OHIO_STATE)
        var t = Instant.parse("2026-09-04T12:00:00Z")
        repeat(6) { runSuspend { s.games(t) }; t = t.plus(Duration.ofHours(1)) }
        assertEquals(1, disk.writes, "six hourly polls, unchanged: one write")
        t = t.plus(Duration.ofHours(20)); runSuspend { s.games(t) }
        assertEquals(2, disk.writes, "a day later it is refreshed")
        f.week = doc.replace("\"Ball State\"", "\"Ball Stat\"").replace("\"shortDisplayName\":\"Ball State\"", "\"shortDisplayName\":\"Ball Stat\"")
        t = t.plus(Duration.ofHours(1)); runSuspend { s.games(t) }
        assertTrue(disk.writes >= 2)
    }

    @Test fun hostileDepthIsRefusedByEveryFeedParser() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertNull(Schedules.parse(Teams.PREDATORS, """{"games":$deep}""", now0))
        assertNull(Schedules.parseSeason(Teams.OHIO_STATE, """{"events":$deep}""", now0))
        assertNull(WeatherFeed.parse("""{"current":{"time":"2026-11-01T14:15","temperature_2m":50,"weather_code":0},"x":$deep}"""))
    }

    @Test fun aFullSeasonFitsTheBodyCapOnlyBecauseTheEspnPathIsRaised() {
        // NHL: scale the real fixture's rows up to a 100-game season.
        val nhlRows = Regex("""\{"id":2026\d+,"season".*?"gameCenterLink":"[^"]*"\}""").findAll(Fixtures.text("nhl-season.json")).map { it.value }.toList()
        assertTrue(nhlRows.isNotEmpty())
        val perRow = nhlRows.sumOf { it.length } / nhlRows.size
        assertTrue(perRow * 100 < HttpFeedFetcher.MAX_BODY / 2, "100 NHL rows of $perRow bytes fit with room to spare")
        // ESPN: about 50 KB per event, 13+ events is over 512 KB.
        val espn = Fixtures.text("espn-team-194.json")
        assertTrue(espn.length / 2 * 13 > HttpFeedFetcher.MAX_BODY, "a 13-game ESPN season is over the default cap")
        val u = Teams.OHIO_STATE.scheduleUrl(now0)
        assertEquals(HttpFeedFetcher.ESPN_SCHEDULE_MAX_BODY, HttpFeedFetcher.maxBodyFor(u))
        assertEquals(HttpFeedFetcher.MAX_BODY, HttpFeedFetcher.maxBodyFor(Teams.PREDATORS.scheduleUrl(now0)))
        assertEquals(HttpFeedFetcher.MAX_BODY, HttpFeedFetcher.maxBodyFor(Teams.PREDATORS.seasonUrl(now0)))
        assertEquals(HttpFeedFetcher.MAX_BODY, HttpFeedFetcher.maxBodyFor("https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard".toHttpUrl()))
        assertEquals(HttpFeedFetcher.MAX_BODY, HttpFeedFetcher.maxBodyFor("https://api.weather.gov/apis/site/v2/sports/football/college-football/teams/194/schedule".toHttpUrl()))
    }
}
