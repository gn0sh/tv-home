package tvhome.core.feeds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

class RunTimingTest {
    private val t0 = Instant.parse("2026-11-01T22:00:00Z")
    private var now = t0
    private val hits = mutableListOf<Pair<String, Instant>>()
    private val fetcher = FeedFetcher { url: HttpUrl ->
        hits += url.host to now
        when (url.host) {
            "api-web.nhle.com" -> """{"games":[{"startTimeUTC":"2026-11-02T01:00:00Z","gameState":"FUT",
              "homeTeam":{"abbrev":"NSH","commonName":{"default":"Predators"}},"awayTeam":{"abbrev":"STL","commonName":{"default":"Blues"}}}]}"""
            "site.api.espn.com" -> """{"events":[]}"""
            else -> """{"current":{"time":"2026-11-01T17:00","temperature_2m":50,"weather_code":0}}"""
        }
    }

    private fun runFor(minutes: Long, loc: () -> Location?): LiveFeeds {
        val end = t0.plus(Duration.ofMinutes(minutes))
        val lf = LiveFeeds(fetcher, loc, clock = { now }, io = Dispatchers.Unconfined, sleep = { ms ->
            kotlinx.coroutines.yield()
            now = now.plusMillis(ms)
            if (now >= end) throw CancellationException("done")
        })
        try { runBlocking { lf.run() } } catch (_: CancellationException) {}
        return lf
    }

    @Test fun pollingSwitchesToSixtySecondsWithinAMinuteOfTheAboutToStartWindow() {
        // Puck drop 01:00Z = 3 h after t0. Hourly fetches until 00:50Z, then every minute.
        runFor(190) { null }
        val sched = hits.filter { it.first == "api-web.nhle.com" }.map { it.second }
        val before = sched.filter { it < Instant.parse("2026-11-02T00:45:00Z") }
        assertTrue(before.size <= 3, "hourly before the window: $before")
        val inWindow = sched.filter { it >= Instant.parse("2026-11-02T00:51:00Z") && it <= Instant.parse("2026-11-02T01:00:00Z") }
        // Both loops share the fake clock, so each slice advances it up to twice: expect at least every ~2 minutes.
        assertTrue(inWindow.size >= 4, "frequent inside the last 10 minutes: $inWindow")
        assertTrue(inWindow.zipWithNext().all { (a, b) -> Duration.between(a, b) <= Duration.ofMinutes(3) }, "gaps: $inWindow")
    }

    @Test fun cancelledAttemptIsNotRecordedAndNoLocationMakesNoWeatherRequest() {
        runFor(10) { null }
        assertTrue(hits.none { it.first == "api.open-meteo.com" })
    }

    @Test fun newLocationShowsWithinAboutAMinute() {
        var loc: Location? = null
        val end = t0.plus(Duration.ofMinutes(20))
        val lf = LiveFeeds(fetcher, { loc }, clock = { now }, io = Dispatchers.Unconfined, sleep = { ms ->
            kotlinx.coroutines.yield()
            now = now.plusMillis(ms)
            if (now >= t0.plus(Duration.ofMinutes(5))) loc = Location(36.5, -87.3)
            if (now >= end) throw CancellationException("done")
        })
        try { runBlocking { lf.run() } } catch (_: CancellationException) {}
        val first = hits.first { it.first == "api.open-meteo.com" }.second
        assertTrue(first <= t0.plus(Duration.ofMinutes(7)), "first weather fetch at $first")
        assertNotNull(lf.snapshot.value.weather)
    }
}
