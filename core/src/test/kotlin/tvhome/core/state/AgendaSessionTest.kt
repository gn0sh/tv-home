package tvhome.core.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.calendar.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

class AgendaSessionTest {
    private val zone = ZoneId.of("America/New_York")
    private val t0 = Instant.parse("2026-03-10T14:00:00Z")
    private val ev = EventInstance("u1", "Dentist", EventTime.At(Instant.parse("2026-03-10T15:00:00Z")), null)

    private class MemCache(var text: String? = null) : CacheFile {
        val writes = AtomicInteger()
        override fun read() = text
        override fun writeAtomic(text: String) { writes.incrementAndGet(); this.text = text }
    }

    private class Stop : CancellationException("stop")

    private inner class Rig(
        val source: CalendarSource,
        val cache: MemCache = MemCache(),
        var secret: Boolean = true,
        var online: Boolean = true,
    ) {
        val clock = AtomicReference(t0)
        val fetches = AtomicInteger()
        val counting = CalendarSource { w -> fetches.incrementAndGet(); source.fetch(w) }
        val session = AgendaSession(
            AgendaRefresher(counting, cache, { zone }, { clock.get() }, Dispatchers.Unconfined),
            cache, { secret }, { online }, { zone }, { clock.get() }, RefreshPolicy(), Dispatchers.Unconfined,
        )

        /** Runs the loop; each pause advances the clock by [TICK] and the run ends after [ticks] pauses. */
        fun run(ticks: Int) {
            var n = 0
            try {
                runBlocking {
                    session.runWhileStarted { ms ->
                        if (n++ >= ticks) throw Stop()
                        clock.set(clock.get().plusMillis(ms))
                    }
                }
            } catch (_: Stop) {
            }
        }
    }

    private fun ok(at: Instant = t0) = FetchResult.Ok(listOf(ev), at)
    private fun cacheJson(fetchedAt: Instant) = CachedAgenda(fetchedAt, tvhome.core.agenda.agendaWindow(fetchedAt, zone), listOf(ev)).encode()

    @Test fun startsLoadingAndFetchesOnce() {
        val r = Rig(CalendarSource { ok() })
        assertEquals(AgendaState.Loading, r.session.state.value)
        r.run(ticks = 0)
        assertEquals(1, r.fetches.get())
        assertTrue(r.session.state.value is AgendaState.Ok)
    }

    /** AC8: the cache is on screen before the network answers. */
    @Test fun cacheShowsBeforeTheFetchFinishes() {
        val gate = CompletableDeferred<FetchResult>()
        val seen = AtomicReference<AgendaState>()
        val r = Rig(CalendarSource { gate.await() }, MemCache(cacheJson(t0.minusSeconds(120))))
        runBlocking {
            val job = launch(Dispatchers.Default) { r.session.runWhileStarted { throw Stop() } }
            withTimeout(5000) { while (r.fetches.get() == 0) kotlinx.coroutines.yield() }
            seen.set(r.session.state.value) // fetch is still in flight here
            gate.complete(ok())
            job.join()
        }
        val s = seen.get()
        assertTrue(s is AgendaState.Ok, "was $s")
        assertEquals("Updated 2 min ago", (s as AgendaState.Ok).ageText)
    }

    @Test fun neverWritesTheCacheItself() {
        val r = Rig(CalendarSource { FetchResult.Failed(SourceError.NETWORK) }, MemCache(cacheJson(t0.minusSeconds(60))))
        r.run(ticks = 20)
        assertEquals(0, r.cache.writes.get())
    }

    @Test fun goodFetchGoesThroughTheRefresherWhichSaves() {
        val r = Rig(CalendarSource { ok() })
        r.run(ticks = 0)
        assertEquals(1, r.cache.writes.get())
        assertNotNull(CachedAgenda.decode(r.cache.text))
    }

    @Test fun fetchesAtStartThenEveryFifteenMinutesOnly() {
        val r = Rig(CalendarSource { FetchResult.Ok(listOf(ev), t0) })
        r.run(ticks = 14) // 14 minutes
        assertEquals(1, r.fetches.get())
        val r2 = Rig(CalendarSource { ok() })
        r2.run(ticks = 15) // 15 minutes: the interval has passed
        assertEquals(2, r2.fetches.get())
        val r3 = Rig(CalendarSource { ok() })
        r3.run(ticks = 45) // start, 15, 30, 45
        assertEquals(4, r3.fetches.get())
    }

    @Test fun ageTextKeepsMovingBetweenFetches() {
        val r = Rig(CalendarSource { ok() })
        r.run(ticks = 5)
        assertEquals("Updated 5 min ago", (r.session.state.value as AgendaState.Ok).ageText)
    }

    @Test fun failedFetchKeepsTheLastGoodDataAndSaysWhy() {
        val results = ArrayDeque(listOf<FetchResult>(ok(), FetchResult.Failed(SourceError.NETWORK)))
        val r = Rig(CalendarSource { results.removeFirstOrNull() ?: FetchResult.Failed(SourceError.NETWORK) })
        r.run(ticks = 15)
        val s = r.session.state.value
        assertTrue(s is AgendaState.Stale, "was $s")
        s as AgendaState.Stale
        assertEquals(StaleCause.CANT_REACH, s.cause)
        assertFalse(s.emphasised) // 15 min old: silent
        assertEquals(listOf("Dentist"), s.today.items.map { it.title }.ifEmpty { s.tomorrow.items.map { it.title } })
    }

    @Test fun unreadableFeedWithACacheIsStaleUnreadable() {
        val r = Rig(CalendarSource { FetchResult.Failed(SourceError.PARSE_LIMIT) }, MemCache(cacheJson(t0.minusSeconds(3 * 3600))))
        r.run(ticks = 0)
        val s = r.session.state.value as AgendaState.Stale
        assertEquals(StaleCause.UNREADABLE, s.cause)
        assertTrue(s.emphasised)
    }

    @Test fun offlineSkipsTheFetchAndShowsCantReach() {
        val r = Rig(CalendarSource { ok() }, online = false)
        r.run(ticks = 3)
        assertEquals(0, r.fetches.get())
        assertEquals(AgendaState.CantReach, r.session.state.value)
    }

    @Test fun offlineWithAnOldCacheIsStaleCantReach() {
        val r = Rig(CalendarSource { ok() }, MemCache(cacheJson(t0.minusSeconds(2 * 3600))), online = false)
        r.run(ticks = 0)
        val s = r.session.state.value as AgendaState.Stale
        assertEquals(StaleCause.CANT_REACH, s.cause)
        assertTrue(s.emphasised)
        assertEquals(0, r.fetches.get())
    }

    @Test fun networkReturningFetchesOnTheNextTickNotFifteenMinutesLater() {
        val r = Rig(CalendarSource { ok() }, online = false)
        var n = 0
        try {
            runBlocking {
                r.session.runWhileStarted { ms ->
                    if (n++ == 3) r.online = true
                    if (n > 5) throw Stop()
                    r.clock.set(r.clock.get().plusMillis(ms))
                }
            }
        } catch (_: Stop) {
        }
        assertEquals(1, r.fetches.get())
        assertTrue(r.session.state.value is AgendaState.Ok)
    }

    @Test fun noSecretIsNotSetUpAndNeverFetches() {
        val r = Rig(CalendarSource { ok() }, secret = false)
        r.run(ticks = 20)
        assertEquals(0, r.fetches.get())
        assertEquals(AgendaState.NotSetUp, r.session.state.value)
    }

    @Test fun secretProvisionedWhileRunningIsPickedUpWithinAMinute() {
        val r = Rig(CalendarSource { ok() }, secret = false)
        var n = 0
        val seen = ArrayList<AgendaState>()
        try {
            runBlocking {
                r.session.runWhileStarted { ms ->
                    seen += r.session.state.value
                    if (n++ == 2) r.secret = true
                    if (n > 4) throw Stop()
                    r.clock.set(r.clock.get().plusMillis(ms))
                }
            }
        } catch (_: Stop) {
        }
        assertEquals(AgendaState.NotSetUp, seen[0])
        assertEquals(AgendaState.NotSetUp, seen[2]) // flipped during this pause; the next step sees it
        assertTrue(seen[3] is AgendaState.Ok, "was ${seen[3]}")
        assertEquals(1, r.fetches.get())
    }

    @Test fun secretRemovedWhileRunningGoesBackToNotSetUp() {
        val r = Rig(CalendarSource { ok() })
        var n = 0
        try {
            runBlocking {
                r.session.runWhileStarted { ms ->
                    if (n++ == 1) r.secret = false
                    if (n > 3) throw Stop()
                    r.clock.set(r.clock.get().plusMillis(ms))
                }
            }
        } catch (_: Stop) {
        }
        assertEquals(AgendaState.NotSetUp, r.session.state.value)
    }

    @Test fun cancelDuringAFetchSavesNothingAndTheNextStartWorks() {
        val started = CompletableDeferred<Unit>()
        var hang = true
        val r = Rig(CalendarSource {
            if (hang) { started.complete(Unit); kotlinx.coroutines.awaitCancellation() } else ok()
        })
        runBlocking {
            val job = launch(Dispatchers.Default) { r.session.runWhileStarted() }
            withTimeout(5000) { started.await() }
            job.cancel()
            job.join()
        }
        assertEquals(0, r.cache.writes.get())
        hang = false
        r.run(ticks = 0)
        assertEquals(1, r.cache.writes.get())
        assertTrue(r.session.state.value is AgendaState.Ok)
    }

    @Test fun restartForgetsThePreviousSessionsFailureButKeepsTheCache() {
        val r = Rig(CalendarSource { FetchResult.Failed(SourceError.NETWORK) }, MemCache(cacheJson(t0.minusSeconds(30 * 60))))
        r.run(ticks = 0)
        assertEquals(StaleCause.CANT_REACH, (r.session.state.value as AgendaState.Stale).cause)
        // second start: before its fetch finishes, no failure is known
        val gate = CompletableDeferred<FetchResult>()
        val r2 = Rig(CalendarSource { gate.await() }, r.cache)
        val seen = AtomicReference<AgendaState>()
        runBlocking {
            val job = launch(Dispatchers.Default) { r2.session.runWhileStarted { throw Stop() } }
            withTimeout(5000) { while (r2.fetches.get() == 0) kotlinx.coroutines.yield() }
            seen.set(r2.session.state.value)
            gate.complete(FetchResult.Failed(SourceError.NETWORK))
            job.join()
        }
        assertEquals(StaleCause.OVERDUE, (seen.get() as AgendaState.Stale).cause)
    }

    @Test fun tickIsOneMinute() {
        assertEquals(Duration.ofMinutes(1).toMillis(), AgendaSession.TICK_MS)
    }
}
