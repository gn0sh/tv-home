package tvhome.core.state

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tvhome.core.FakeSecrets
import tvhome.core.agenda.agendaWindow
import tvhome.core.calendar.EventInstance
import tvhome.core.calendar.EventTime
import tvhome.core.calendar.FetchResult
import tvhome.core.calendar.SourceError
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

class FakeClock(var now: Instant) {
    fun advance(d: Duration) { now = now.plus(d) }
}

class AgendaStateTest {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val clock = FakeClock(Instant.parse("2026-10-31T01:00:00Z")) // 14:00 local
    private val ev = EventInstance("u1", "Dentist", EventTime.At(Instant.parse("2026-10-31T02:00:00Z")), null)

    private fun cache(fetchedAt: Instant, events: List<EventInstance> = listOf(ev)) =
        CachedAgenda(fetchedAt, agendaWindow(fetchedAt, zone), events)

    private fun mins(m: Long) = clock.now.minus(Duration.ofMinutes(m))
    private fun fail(e: SourceError = SourceError.NETWORK) = FetchResult.Failed(e)
    private fun state(secret: Boolean = true, c: CachedAgenda? = null, f: FetchResult? = null) =
        reduce(secret, c, f, clock.now, zone)

    @Test fun noSecretIsNotSetUpWhateverElseExists() {
        assertEquals(AgendaState.NotSetUp, state(false, cache(mins(1)), FetchResult.Ok(listOf(ev), mins(0))))
    }

    @Test fun noCacheStates() {
        assertEquals(AgendaState.Loading, state())
        assertEquals(AgendaState.CantReach, state(f = fail(SourceError.OFFLINE)))
        assertEquals(AgendaState.CantReach, state(f = fail(SourceError.HTTP_STATUS)))
        assertEquals(AgendaState.Unreadable, state(f = fail(SourceError.PARSE)))
        assertEquals(AgendaState.Unreadable, state(f = fail(SourceError.TOO_LARGE)))
    }

    @Test fun goodFetchWinsOverCache() {
        val s = state(c = cache(mins(500)), f = FetchResult.Ok(listOf(ev), mins(0)))
        assertTrue(s is AgendaState.Ok)
        assertEquals("Updated just now", (s as AgendaState.Ok).ageText)
        assertEquals(1, s.today.items.size)
    }

    @Test fun emptyFreshAgendaIsNothingScheduled() {
        assertTrue(state(f = FetchResult.Ok(emptyList(), mins(4))) is AgendaState.NothingScheduled)
        assertTrue(state(c = cache(mins(4), emptyList())) is AgendaState.NothingScheduled)
    }

    // age (min), lastFetch failed?, expected: null = Ok, else (emphasised)
    private data class Row(val age: Long, val failed: Boolean, val stale: Boolean, val emph: Boolean = false)

    @Test fun cacheAgeTable() {
        val rows = listOf(
            Row(4, false, stale = false),
            Row(15, false, stale = false),
            Row(16, false, stale = true),      // no fetch yet and older than 15 min
            Row(60, false, stale = true),
            Row(61, false, stale = true, emph = true),
            Row(4, true, stale = true),         // failed, young: Stale, not emphasised (UI shows like Ok)
            Row(60, true, stale = true),
            Row(61, true, stale = true, emph = true),
            Row(3 * 60, true, stale = true, emph = true),
        )
        for (r in rows) {
            val s = state(c = cache(mins(r.age)), f = if (r.failed) fail() else null)
            if (!r.stale) {
                assertTrue(s is AgendaState.Ok, "age ${r.age} failed ${r.failed}: $s")
            } else {
                assertTrue(s is AgendaState.Stale, "age ${r.age} failed ${r.failed}: $s")
                s as AgendaState.Stale
                assertEquals(r.age, s.ageMinutes)
                assertEquals(r.emph, s.emphasised, "age ${r.age}")
            }
        }
    }

    @Test fun ageWording() {
        val t = mapOf(
            0L to "Updated just now", 1L to "Updated 1 min ago", 59L to "Updated 59 min ago",
            60L to "Updated 1 hour ago", 119L to "Updated 1 hour ago", 120L to "Updated 2 hours ago",
            23 * 60L to "Updated 23 hours ago", 24 * 60L to "Updated 1 day ago", 47 * 60L to "Updated 1 day ago",
            48 * 60L to "Updated 2 days ago", 5 * 24 * 60L to "Updated 5 days ago",
        )
        for ((m, text) in t) assertEquals(text, ageText(m), "min $m")
    }

    @Test fun cacheFromYesterdayStillCoversTodayAndTomorrow() {
        val yesterday = Instant.parse("2026-10-30T01:00:00Z")
        val s = state(c = cache(yesterday), f = fail())
        assertTrue(s is AgendaState.Stale, "$s")
        s as AgendaState.Stale
        assertTrue(s.emphasised)
        assertEquals("Updated 1 day ago", s.ageText)
        assertEquals(1, s.today.items.size)
    }

    @Test fun cacheOlderThanTheWindowCountsAsNoCache() {
        val twoDaysAgo = Instant.parse("2026-10-29T01:00:00Z")
        assertEquals(AgendaState.CantReach, state(c = cache(twoDaysAgo), f = fail()))
        assertEquals(AgendaState.Loading, state(c = cache(twoDaysAgo)))
        assertEquals(AgendaState.Unreadable, state(c = cache(twoDaysAgo), f = fail(SourceError.PARSE)))
    }

    @Test fun windowEdgeIsLocalMidnightNotUtc() {
        // 2026-10-31 14:00 NZDT. A cache fetched 2026-10-30 23:59 local has window end 2026-11-02 00:00 local: covers.
        val justBefore = Instant.parse("2026-10-30T10:59:00Z")
        assertTrue(state(c = cache(justBefore), f = fail()) is AgendaState.Stale)
        // Fetched 2026-10-29 23:59 local: window ends 2026-10-31 00:00 local, tomorrow uncovered.
        assertEquals(AgendaState.CantReach, state(c = cache(Instant.parse("2026-10-29T10:59:00Z")), f = fail()))
    }

    @Test fun cacheRoundTripsAndNeverHoldsTheUrl() {
        val long = "x".repeat(500)
        val c = cache(mins(3), listOf(ev, EventInstance("u2", long, EventTime.AllDay(java.time.LocalDate.of(2026, 10, 31)), EventTime.AllDay(java.time.LocalDate.of(2026, 11, 1)))))
        val text = c.encode()
        assertFalse(text.contains("https://"))
        assertTrue(text.contains("\"v\":1"))
        val back = CachedAgenda.decode(text)!!
        assertEquals(c.fetchedAt, back.fetchedAt)
        assertEquals(c.window, back.window)
        assertEquals(ev, back.events[0])
        assertEquals(200, back.events[1].title.length)
    }

    @Test fun badCacheFilesCountAsNoCache() {
        for (t in listOf(null, "", "{", "[]", """{"v":2,"fetchedAt":"x"}""", """{"v":1,"fetchedAt":"nope","windowStart":"a","windowEnd":"b","events":[]}""",
            """{"v":1,"fetchedAt":"2026-10-31T01:00:00Z","windowStart":"2026-10-30T00:00:00Z","windowEnd":"2026-11-02T00:00:00Z","events":[{"uid":"a","title":"t","start":{"kind":"zz","value":"1"}}]}""")) {
            assertNull(CachedAgenda.decode(t), t)
        }
    }

    @Test fun atomicWriteReplacesAndLeavesNoTemp(@TempDir dir: File) {
        val cf = JvmCacheFile(File(dir, "agenda-cache.json"))
        assertNull(CachedAgenda.load(cf))
        cf.save(cache(mins(1)))
        cf.save(cache(mins(2), emptyList()))
        assertEquals(0, CachedAgenda.load(cf)!!.events.size)
        assertEquals(listOf("agenda-cache.json"), dir.list()!!.toList())
        File(dir, "agenda-cache.json").writeText("garbage{")
        assertNull(CachedAgenda.load(cf))
    }

    @Test fun refreshPolicyTable() {
        val p = RefreshPolicy()
        val t0 = clock.now
        val S = RefreshPolicy.Trigger.START
        val T = RefreshPolicy.Trigger.TICK
        assertEquals(RefreshPolicy.Decision.FETCH, p.decide(S, true, null, t0))
        assertEquals(RefreshPolicy.Decision.FETCH, p.decide(S, true, t0, t0))
        assertEquals(RefreshPolicy.Decision.SKIP_OFFLINE, p.decide(S, false, null, t0))
        assertEquals(RefreshPolicy.Decision.SKIP_OFFLINE, p.decide(T, false, null, t0))
        assertEquals(RefreshPolicy.Decision.SKIP_TOO_SOON, p.decide(T, true, t0, t0.plus(Duration.ofMinutes(14))))
        assertEquals(RefreshPolicy.Decision.FETCH, p.decide(T, true, t0, t0.plus(Duration.ofMinutes(15))))
        assertEquals(Duration.ofMinutes(15), p.tickDelay())
    }

    @Test fun seededPropertyNoStateOrCacheContainsTheSecret() {
        val rnd = Random(20261031)
        val alphabet = ('a'..'z') + ('0'..'9') + listOf('-', '_', '%')
        val errors = SourceError.values()
        repeat(300) {
            val secret = "tok" + (1..rnd.nextInt(8, 60)).map { alphabet[rnd.nextInt(alphabet.size)] }.joinToString("")
            val url = "https://host.example.test/a/$secret/basic.ics"
            // Titles are event text, never the secret; the URL lives only in the store/fetcher, which state never sees.
            val events = (0..rnd.nextInt(0, 4)).map { EventInstance("u$it", "Event $it", EventTime.At(clock.now.plusSeconds(rnd.nextLong(0, 100000))), null) }
            val fetchedAt = clock.now.minus(Duration.ofMinutes(rnd.nextLong(0, 3000)))
            val c = if (rnd.nextBoolean()) cache(fetchedAt, events) else null
            val f: FetchResult? = when (rnd.nextInt(3)) {
                0 -> null
                1 -> FetchResult.Ok(events, fetchedAt)
                else -> FetchResult.Failed(errors[rnd.nextInt(errors.size)])
            }
            val s = reduce(rnd.nextInt(5) != 0, c, f, clock.now, zone)
            assertFalse(s.toString().contains(secret), "state leak")
            assertFalse(s.toString().contains(url))
            assertFalse(s.toString().contains(FakeSecrets.CALENDAR_TOKEN))
            c?.let { assertFalse(it.encode().contains(secret)); assertFalse(it.encode().contains("https://")) }
        }
    }
}
