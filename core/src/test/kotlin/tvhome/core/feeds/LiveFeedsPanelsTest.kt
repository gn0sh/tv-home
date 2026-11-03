package tvhome.core.feeds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.Fixtures
import tvhome.core.runSuspend
import tvhome.core.state.CacheFile
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

/** M7-B4: alerts loop, switches, disk caches. */
class LiveFeedsPanelsTest {
    private val t0 = Instant.parse("2026-04-15T04:00:00Z")
    private var now = t0
    private val loc = Location(36.53, -87.36)
    private val weatherDoc = """{"current":{"time":"2026-04-14T23:00","temperature_2m":61.0,"weather_code":95}}"""
    /** Synthetic (the real captures hold no Warning), in the shape of the real nws-alerts-area.json. */
    private val alertDoc = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"event":"Tornado Warning",
        "severity":"Extreme","status":"Actual","messageType":"Alert","ends":"2026-04-15T09:00:00-00:00","expires":"2026-04-15T09:00:00Z"}}]}"""
    private val emptyAlerts = Fixtures.text("nws-alerts-point.json")

    private inner class Fake(val replies: MutableMap<String, String?> = HashMap()) : FeedFetcher {
        val asked = mutableListOf<Pair<String, Instant>>()
        var onFetch: () -> Unit = {}
        override suspend fun fetch(url: HttpUrl): String? { onFetch(); asked += url.host to now; return replies[url.host] }
        fun count(host: String) = asked.count { it.first == host }
    }

    private class MemFile(var text: String? = null, val fail: Boolean = false) : CacheFile {
        var writes = 0
        override fun read(): String? = text
        override fun writeAtomic(text: String) { if (fail) throw java.io.IOException("disk"); writes++; this.text = text }
    }

    private fun all() = mutableMapOf<String, String?>(
        "api.open-meteo.com" to weatherDoc, "api.weather.gov" to alertDoc,
        "api-web.nhle.com" to """{"games":[]}""", "site.api.espn.com" to """{"events":[]}""",
    )

    private fun feeds(f: FeedFetcher, sw: () -> PanelSwitches = { PanelSwitches() }, files: Map<String, MemFile>? = null,
                      location: () -> Location? = { loc }, sleep: suspend (Long) -> Unit = {}) =
        LiveFeeds(f, location, clock = { now }, io = Dispatchers.Unconfined, sleep = sleep, switches = sw,
            caches = files?.let { m -> { id: String -> m.getValue(id) } })

    private fun runFor(minutes: Long, lf: (suspend (Long) -> Unit) -> LiveFeeds): LiveFeeds {
        val end = now.plus(Duration.ofMinutes(minutes))
        val feeds = lf { ms ->
            kotlinx.coroutines.yield()
            now = now.plusMillis(ms)
            if (now >= end) throw CancellationException("done")
        }
        try { runBlocking { feeds.run() } } catch (_: CancellationException) {}
        return feeds
    }

    @Test fun switchOffMeansZeroCalls() {
        val f = Fake(all())
        runFor(180) { s -> feeds(f, { PanelSwitches(weather = false, alerts = false, games = false) }, sleep = s) }
        assertEquals(0, f.asked.size)
        val g = Fake(all())
        runFor(180) { s -> feeds(g, { PanelSwitches(alerts = false) }, sleep = s) }
        assertEquals(0, g.count("api.weather.gov"))
        assertTrue(g.count("api.open-meteo.com") > 0 && g.count("api-web.nhle.com") > 0)
    }

    @Test fun switchIsReadEverySlice() {
        var on = false
        val f = Fake(all())
        runFor(30) { s -> feeds(f, { PanelSwitches(alerts = on, weather = false, games = false) }, sleep = { ms ->
            s(ms); if (now >= t0.plus(Duration.ofMinutes(10))) on = true
        }) }
        val first = f.asked.first { it.first == "api.weather.gov" }.second
        assertTrue(first <= t0.plus(Duration.ofMinutes(12)), "$first")
    }

    @Test fun alertsEveryFiveMinutesWhileActiveElseFifteen() {
        val f = Fake(all())
        runFor(60) { s -> feeds(f, { PanelSwitches(weather = false, games = false) }, sleep = s) }
        val active = f.asked.filter { it.first == "api.weather.gov" }.map { it.second }
        assertTrue(active.size in 11..13, "every 5 min with an alert: $active")
        now = t0
        val g = Fake(all().apply { put("api.weather.gov", emptyAlerts) })
        runFor(60) { s -> feeds(g, { PanelSwitches(weather = false, games = false) }, sleep = s) }
        val quiet = g.asked.filter { it.first == "api.weather.gov" }.map { it.second }
        assertTrue(quiet.size in 4..5, "every 15 min: $quiet")
    }

    @Test fun alertsBackOffButNeverPastThirtyMinutes() {
        val f = Fake(all().apply { put("api.weather.gov", null) })
        runFor(240) { s -> feeds(f, { PanelSwitches(weather = false, games = false) }, sleep = s) }
        val times = f.asked.filter { it.first == "api.weather.gov" }.map { it.second }
        // The three loops share the fake clock, so each slice can add a minute or two to a gap.
        assertTrue(times.zipWithNext().all { (a, b) -> Duration.between(a, b) <= Duration.ofMinutes(33) }, "$times")
        assertTrue(times.size < 240 / 15, "backs off: ${times.size}")
    }

    @Test fun failedRefreshKeepsLastGoodAndAlertsGoUnknownAt30Min() {
        val f = Fake(all())
        val lf = feeds(f)
        assertFalse(lf.snapshot.value.alertsChecked)
        assertTrue(runSuspend { lf.refreshAlerts() })
        val good = lf.snapshot.value.alerts!!
        assertEquals("Tornado Warning", good.value.override!!.event)
        f.replies["api.weather.gov"] = "<html>503</html>"
        now = now.plus(Duration.ofMinutes(29))
        assertFalse(runSuspend { lf.refreshAlerts() })
        assertSame(good, lf.snapshot.value.alerts, "kept")
        assertTrue(view(lf.snapshot.value.alerts, PanelPolicies.ALERTS, true, now) is PanelView.Shown)
        now = t0.plus(Duration.ofMinutes(30))
        assertEquals(PanelView.NoData, view(lf.snapshot.value.alerts, PanelPolicies.ALERTS, true, now))
        assertTrue(lf.snapshot.value.alertsChecked, "so the chip says alerts unknown")
    }

    @Test fun noLocationMeansNoAlertsAndNoRequest() {
        val f = Fake(all())
        val lf = feeds(f, location = { null })
        assertTrue(runSuspend { lf.refreshAlerts() })
        assertNull(lf.snapshot.value.alerts); assertFalse(lf.snapshot.value.alertsChecked)
        runFor(30) { s -> feeds(f, location = { null }, sleep = s) }
        assertEquals(0, f.count("api.weather.gov") + f.count("api.open-meteo.com"))
    }

    @Test fun cacheIsPublishedBeforeTheFirstFetchAndWrittenAfterAGoodOne() {
        val files = mapOf("weather" to MemFile(), "alerts" to MemFile(), "games" to MemFile())
        val first = Fake(all().apply { put("api-web.nhle.com", """{"games":[{"startTimeUTC":"2026-04-16T00:00:00Z","gameState":"FUT",
            "homeTeam":{"abbrev":"NSH","commonName":{"default":"Predators"}},"awayTeam":{"abbrev":"STL","commonName":{"default":"Blues"}}}]}""") })
        val a = feeds(first, files = files)
        runSuspend { a.refreshWeather(); a.refreshAlerts(); a.refreshSchedules() }
        assertEquals(1, files.getValue("weather").writes); assertEquals(1, files.getValue("alerts").writes); assertEquals(1, files.getValue("games").writes)

        // A new process: network off; caches show with their stamps before any fetch.
        now = now.plus(Duration.ofMinutes(20))
        val off = Fake()
        var seenAtFirstFetch: FeedsSnapshot? = null
        lateinit var b: LiveFeeds
        off.onFetch = { if (seenAtFirstFetch == null) seenAtFirstFetch = b.snapshot.value }
        b = feeds(off, files = files)
        runFor(5) { s -> feeds(off, files = files, sleep = s).also { b = it } }
        val seen = seenAtFirstFetch!!
        assertEquals(61, seen.weather!!.value.tempF)
        assertEquals(t0, seen.weather!!.at)
        assertEquals("Tornado Warning", seen.alerts!!.value.override!!.event)
        assertTrue(seen.alertsChecked)
        assertEquals("Blues", seen.games!!.value.single().game.opponent)
        assertSame(Teams.PREDATORS, seen.games!!.value.single().team)
        // Failed fetches never overwrite the files.
        assertEquals(1, files.getValue("weather").writes); assertEquals(1, files.getValue("alerts").writes)
        val ageMin = Duration.between(t0, now).toMinutes()
        assertTrue(ageMin >= 20)
        assertEquals("Updated $ageMin min ago", (view(b.snapshot.value.weather, PanelPolicies.WEATHER, true, now) as PanelView.Shown).ageText)
    }

    @Test fun networkOffWithNoCacheIsHiddenAndNeverThrows() {
        val files = mapOf("weather" to MemFile("{garbage"), "alerts" to MemFile(null), "games" to MemFile("""{"v":2}"""))
        val lf = runFor(20) { s -> feeds(Fake(), files = files, sleep = s) }
        val snap = lf.snapshot.value
        assertNull(snap.weather); assertNull(snap.alerts); assertNull(snap.games)
        assertTrue(snap.alertsChecked, "a failed attempt was made")
    }

    @Test fun unwritableCacheDoesNotBreakTheFeed() {
        val files = mapOf("weather" to MemFile(fail = true), "alerts" to MemFile(fail = true), "games" to MemFile(fail = true))
        val lf = feeds(Fake(all()), files = files)
        assertTrue(runSuspend { lf.refreshWeather() }); assertTrue(runSuspend { lf.refreshAlerts() })
        assertNotNull(lf.snapshot.value.weather); assertNotNull(lf.snapshot.value.alerts)
    }

    @Test fun futureStampedOrOutOfBoundsCachesAreNoCache() {
        val src = mapOf("weather" to MemFile(), "alerts" to MemFile(), "games" to MemFile())
        val a = feeds(Fake(all()), files = src)
        runSuspend { a.refreshWeather(); a.refreshAlerts() }
        // Clock goes back an hour: the stamp is now 60 min in the future.
        now = t0.minus(Duration.ofHours(1))
        val b = feeds(Fake(), files = src)
        runSuspend { b.loadCaches() }
        assertNull(b.snapshot.value.weather); assertNull(b.snapshot.value.alerts); assertFalse(b.snapshot.value.alertsChecked)
        // Planted kind / oversize event in an otherwise valid envelope.
        now = t0
        val planted = MemFile(src.getValue("alerts").text!!.replace("\"OVERRIDE\"", "\"ROOT\""))
        val hot = MemFile(src.getValue("weather").text!!.replace("61", "999"))
        val c = feeds(Fake(), files = mapOf("weather" to hot, "alerts" to planted, "games" to MemFile()))
        runSuspend { c.loadCaches() }
        assertNull(c.snapshot.value.alerts); assertNull(c.snapshot.value.weather)
        val long = MemFile(src.getValue("alerts").text!!.replace("Tornado Warning", "T".repeat(300) + "‮"))
        val d = feeds(Fake(), files = mapOf("weather" to MemFile(), "alerts" to long, "games" to MemFile()))
        runSuspend { d.loadCaches() }
        assertTrue(d.snapshot.value.alerts!!.value.alerts.all { it.event.length <= 40 && !it.event.contains('‮') })
    }

    @Test fun cachesLoadOnceAndNeverReplaceFresherMemory() {
        val files = mapOf("weather" to MemFile(), "alerts" to MemFile(), "games" to MemFile())
        val f = Fake(all())
        val lf = feeds(f, files = files)
        runSuspend { lf.refreshWeather() }
        val fresh = lf.snapshot.value.weather
        files.getValue("weather").text = PanelCacheCodec.encode(Stamped(WeatherNow(1, "Clear", null, null), t0.minusSeconds(600)), PanelCaches.weather)
        runSuspend { lf.loadCaches() }
        assertSame(fresh, lf.snapshot.value.weather)
    }

    /**
     * Emulator Q1 root cause (job 125648452241): the alerts cache exactly as emulator-qa.sh plants it is shown while
     * offline, but with the network up the first alerts fetch is due at once, and a good (empty) NWS answer replaces
     * the planted Tornado Warning in memory and on disk. Correct per ADR 0008 (a fresh good fetch beats a cache), so Q1
     * must really be offline.
     */
    @Test fun plantedQaAlertCacheShowsOfflineAndAGoodFetchReplacesIt() {
        val until = t0.plus(Duration.ofMinutes(90))
        val planted = """{"v":1,"at":"$t0","data":{"alerts":[{"event":"Tornado Warning","severity":"Extreme","kind":"OVERRIDE","until":"$until"}]}}"""
        val offline = mapOf("weather" to MemFile(), "alerts" to MemFile(planted), "games" to MemFile())
        runFor(2) { sl -> feeds(Fake(), files = offline, sleep = sl) }.also { lf ->
            assertEquals(listOf("Tornado Warning"), lf.snapshot.value.alerts!!.value.alerts.map { it.event })
        }
        assertEquals(planted, offline.getValue("alerts").text, "a failed fetch leaves the planted cache alone")
        val online = mapOf("weather" to MemFile(), "alerts" to MemFile(planted), "games" to MemFile())
        val lf = runFor(2) { sl -> feeds(Fake(all().apply { put("api.weather.gov", emptyAlerts) }), files = online, sleep = sl) }
        assertTrue(lf.snapshot.value.alerts!!.value.alerts.isEmpty(), "the good empty fetch replaced the planted alert")
        assertFalse(online.getValue("alerts").text!!.contains("Tornado Warning"), "and overwrote the planted file")
    }
}
