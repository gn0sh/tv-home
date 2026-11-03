package tvhome.core.feeds

import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.runSuspend
import java.time.Duration
import java.time.Instant

class LiveFeedsTest {
    private class Fake(val replies: MutableMap<String, String?> = HashMap()) : FeedFetcher {
        val asked = mutableListOf<HttpUrl>()
        override suspend fun fetch(url: HttpUrl): String? { asked += url; return replies[url.host] }
    }
    private val weatherDoc = """{"current":{"time":"2026-11-01T14:15","temperature_2m":50.0,"weather_code":0}}"""
    private val nhlDoc = """{"games":[{"startTimeUTC":"2026-11-02T01:00:00Z","gameState":"FUT",
        "homeTeam":{"abbrev":"NSH","commonName":{"default":"Predators"}},"awayTeam":{"abbrev":"STL","commonName":{"default":"Blues"}}}]}"""
    private var now = Instant.parse("2026-11-01T20:00:00Z")
    private fun feeds(f: Fake, loc: Location? = Location(36.5, -87.3)) = LiveFeeds(f, { loc }, clock = { now }, io = Dispatchers.Unconfined)

    @Test fun noLocationMeansNoWeatherAndNoRequest() {
        val f = Fake(); val lf = feeds(f, null)
        assertTrue(runSuspend { lf.refreshWeather() })
        assertNull(lf.snapshot.value.weather)
        assertTrue(f.asked.isEmpty())
    }

    @Test fun weatherAndSchedulesAreCachedAndFailuresKeepTheLastGood() {
        val f = Fake(mutableMapOf("api.open-meteo.com" to weatherDoc, "api-web.nhle.com" to nhlDoc, "site.api.espn.com" to """{"events":[]}"""))
        val lf = feeds(f)
        assertTrue(runSuspend { lf.refreshWeather() }); assertTrue(runSuspend { lf.refreshSchedules() })
        assertEquals(50, lf.snapshot.value.weather!!.value.tempF)
        assertEquals(now, lf.snapshot.value.weather!!.at)
        assertEquals(1, lf.snapshot.value.games!!.value.size)
        f.replies.clear()
        assertFalse(runSuspend { lf.refreshWeather() }); assertFalse(runSuspend { lf.refreshSchedules() })
        assertEquals(50, lf.snapshot.value.weather!!.value.tempF, "kept after one failure")
        assertEquals(1, lf.snapshot.value.games!!.value.size)
        now = now.plus(Duration.ofHours(3))
        runSuspend { lf.refreshWeather() }
        assertEquals(PanelView.NoData, view(lf.snapshot.value.weather, PanelPolicies.WEATHER, true, now), "weather older than 2 h is hidden")
    }

    @Test fun garbageIsNoData() {
        val f = Fake(mutableMapOf("api.open-meteo.com" to "<html>", "api-web.nhle.com" to "{", "site.api.espn.com" to "null"))
        val lf = feeds(f)
        assertFalse(runSuspend { lf.refreshWeather() }); assertFalse(runSuspend { lf.refreshSchedules() })
        assertNull(lf.snapshot.value.weather); assertNull(lf.snapshot.value.games)
    }

    @Test fun requestsOnlyGoToTheFourHosts() {
        val f = Fake(); val lf = feeds(f)
        runSuspend { lf.refreshWeather(); lf.refreshSchedules(); lf.refreshAlerts() }
        assertEquals(HttpFeedFetcher.HOSTS, f.asked.map { it.host }.toSet())
        assertTrue(f.asked.all { HttpFeedFetcher.isAllowed(it) })
    }

    @Test fun backoffDoublesAndCaps() {
        assertEquals(FeedTiming.WEATHER_MS, FeedTiming.delayMs(FeedTiming.WEATHER_MS, 0))
        assertEquals(2 * FeedTiming.WEATHER_MS, FeedTiming.delayMs(FeedTiming.WEATHER_MS, 1))
        assertEquals(FeedTiming.MAX_BACKOFF_MS, FeedTiming.delayMs(FeedTiming.WEATHER_MS, 9))
        assertEquals(2 * 60_000L, FeedTiming.delayMs(FeedTiming.LIVE_MS, 1, FeedTiming.MAX_LIVE_BACKOFF_MS))
        assertEquals(FeedTiming.MAX_LIVE_BACKOFF_MS, FeedTiming.delayMs(FeedTiming.LIVE_MS, 30, FeedTiming.MAX_LIVE_BACKOFF_MS))
    }

    @Test fun scheduleRateIsFastWhileLiveOrAboutToStart() {
        fun g(start: Instant, p: GamePhase) = TeamGame(Teams.PREDATORS, GameInfo(start, "Blues", true, p, null, null, null))
        assertEquals(FeedTiming.SCHEDULE_MS, FeedTiming.scheduleBase(emptyList(), now))
        assertEquals(FeedTiming.SCHEDULE_MS, FeedTiming.scheduleBase(listOf(g(now.plus(Duration.ofHours(2)), GamePhase.UPCOMING)), now))
        assertEquals(FeedTiming.LIVE_MS, FeedTiming.scheduleBase(listOf(g(now.plus(Duration.ofMinutes(5)), GamePhase.UPCOMING)), now))
        assertEquals(FeedTiming.LIVE_MS, FeedTiming.scheduleBase(listOf(g(now.minus(Duration.ofMinutes(2)), GamePhase.UPCOMING)), now))
        assertEquals(FeedTiming.LIVE_MS, FeedTiming.scheduleBase(listOf(g(now.minus(Duration.ofHours(1)), GamePhase.LIVE)), now))
        assertEquals(FeedTiming.SCHEDULE_MS, FeedTiming.scheduleBase(listOf(g(now.minus(Duration.ofHours(1)), GamePhase.FINAL)), now))
    }
}
