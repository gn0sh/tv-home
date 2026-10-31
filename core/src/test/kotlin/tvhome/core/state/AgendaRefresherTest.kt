package tvhome.core.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tvhome.core.FakeSecrets
import tvhome.core.calendar.*
import tvhome.core.runSuspend
import tvhome.core.secrets.InMemorySecretStore
import tvhome.core.secrets.SecretKey
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class AgendaRefresherTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-03-10T14:00:00Z")
    private val ev = EventInstance("u1", "Dentist", EventTime.At(Instant.parse("2026-03-10T15:00:00Z")), null)
    private val ev2 = EventInstance("u2", "Lunch", EventTime.At(Instant.parse("2026-03-10T17:00:00Z")), null)
    private val stats = ParseStats(1, 0, 0, 0, 0, truncated = false, steps = 1)

    private class Setup(val file: File, val refresher: AgendaRefresher, val cache: JvmCacheFile)

    private fun setup(dir: File, source: CalendarSource): Setup {
        val f = File(dir, "agenda-cache.json")
        val cf = JvmCacheFile(f)
        return Setup(f, AgendaRefresher(source, cf, { zone }, { now }, Dispatchers.IO), cf)
    }

    private fun ok(vararg e: EventInstance) = FetchResult.Ok(e.toList(), now)

    @Test fun okIsSavedAndReturned(@TempDir dir: File) {
        val s = setup(dir, CalendarSource { ok(ev) })
        assertEquals(ok(ev), runSuspend { s.refresher.refresh() })
        val c = CachedAgenda.load(s.cache)!!
        assertEquals(listOf(ev), c.events)
        assertEquals(now, c.fetchedAt)
        assertEquals(agendaWindowOf(now), c.window)
    }

    private fun agendaWindowOf(t: Instant) = tvhome.core.agenda.agendaWindow(t, zone)

    /** The source is asked for the agenda window of "now" in the injected zone. */
    @Test fun fetchesTheAgendaWindow(@TempDir dir: File) {
        var seen: InstantRange? = null
        val s = setup(dir, CalendarSource { w -> seen = w; ok() })
        runSuspend { s.refresher.refresh() }
        assertEquals(agendaWindowOf(now), seen)
    }

    /** N2 / R1: every non-Ok result leaves the earlier cache byte-identical. */
    @Test fun failedResultsNeverTouchTheCache(@TempDir dir: File) {
        var next: FetchResult = ok(ev)
        val s = setup(dir, CalendarSource { next })
        runSuspend { s.refresher.refresh() }
        val before = s.file.readBytes()
        val mtime = s.file.lastModified()
        for (e in SourceError.values()) {
            next = FetchResult.Failed(e)
            assertEquals(FetchResult.Failed(e), runSuspend { s.refresher.refresh() })
            assertArrayEquals(before, s.file.readBytes(), "$e")
        }
        assertEquals(mtime, s.file.lastModified())
        assertEquals(listOf("agenda-cache.json"), s.file.parentFile.list()!!.toList())
        // and a later good result replaces it
        next = ok(ev2)
        runSuspend { s.refresher.refresh() }
        assertEquals(listOf(ev2), CachedAgenda.load(s.cache)!!.events)
    }

    /** R2: an Exhausted parse through the real source after a good feed: cache unchanged, old events still shown (Stale, UNREADABLE). */
    @Test fun exhaustedParseKeepsEarlierCacheAndShowsItStale(@TempDir dir: File) {
        val store = InMemorySecretStore(mapOf(SecretKey.CALENDAR_URL to FakeSecrets.CALENDAR_URL))
        var outcome: ParseOutcome = ParseOutcome.Complete(listOf(ev), stats)
        val source = IcsCalendarSource(store, { IcsFetchResult.Body(ByteArray(3)) }, { _, _ -> outcome }, clock = { now })
        val s = setup(dir, source)
        assertTrue(runSuspend { s.refresher.refresh() } is FetchResult.Ok)
        val before = s.file.readBytes()
        for (bad in listOf(ParseOutcome.Exhausted(ParseLimit.STEPS), ParseOutcome.Rejected(RejectReason.LINE_ENDING))) {
            outcome = bad
            val r = runSuspend { s.refresher.refresh() }
            assertTrue(r is FetchResult.Failed, "$r")
            assertArrayEquals(before, s.file.readBytes())
            val later = now.plusSeconds(61 * 60)
            val st = reduce(true, CachedAgenda.load(s.cache), r, later, zone) as AgendaState.Stale
            assertEquals(StaleCause.UNREADABLE, st.cause)
            assertTrue(st.emphasised)
            assertEquals(listOf("Dentist"), st.today.items.map { it.title })
        }
        // with no cache at all it is Unreadable
        assertEquals(AgendaState.Unreadable, reduce(true, null, FetchResult.Failed(SourceError.PARSE_LIMIT), now, zone))
    }

    /**
     * R2 + P23 through the REAL parser (`IcsParse.ics`, the strict reader) and the real `IcsCalendarSource`: after a
     * good feed, the P23 body (3,000 COUNT walks from year 1 plus one real one-off) is `Exhausted(STEPS)`, so the
     * refresh fails with PARSE_LIMIT, the cache stays byte-identical and the old events are shown Stale/UNREADABLE.
     */
    @Test
    @org.junit.jupiter.api.Tag("deadline-drops-recurring-silently")
    fun p23BodyThroughTheRealParserKeepsTheEarlierCache(@TempDir dir: File) {
        val store = InMemorySecretStore(mapOf(SecretKey.CALENDAR_URL to FakeSecrets.CALENDAR_URL))
        val head = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\n"
        val good = (head + "BEGIN:VEVENT\r\nUID:u1\r\nDTSTART:20260310T150000Z\r\nSUMMARY:Dentist\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
            .toByteArray()
        val p23 = StringBuilder(head).apply {
            repeat(3_000) { append("BEGIN:VEVENT\r\nUID:w$it\r\nDTSTART:00010101T090000Z\r\nRRULE:FREQ=DAILY;BYDAY=MO;COUNT=1000000000\r\nEND:VEVENT\r\n") }
            append("BEGIN:VEVENT\r\nUID:real\r\nDTSTART:20260310T170000Z\r\nSUMMARY:Real\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        }.toString().toByteArray()
        var body = good
        val parser = IcsParse.ics { zone }
        assertEquals(ParseLimit.STEPS, (parser.parse(p23, agendaWindowOf(now)) as ParseOutcome.Exhausted).limit)
        val source = IcsCalendarSource(store, { IcsFetchResult.Body(body) }, parser, clock = { now })
        val s = setup(dir, source)
        assertEquals(listOf("Dentist"), (runSuspend { s.refresher.refresh() } as FetchResult.Ok).events.map { it.title })
        val before = s.file.readBytes()
        body = p23
        val r = runSuspend { s.refresher.refresh() }
        assertEquals(FetchResult.Failed(SourceError.PARSE_LIMIT), r)
        assertArrayEquals(before, s.file.readBytes())
        val st = reduce(true, CachedAgenda.load(s.cache), r, now.plusSeconds(61 * 60), zone) as AgendaState.Stale
        assertEquals(StaleCause.UNREADABLE, st.cause)
        assertEquals(listOf("Dentist"), st.today.items.map { it.title })
    }

    /** R3 / N3: two concurrent refreshes make one fetch; the second returns null. */
    @Test fun singleFlight(@TempDir dir: File) = runBlocking {
        val fetches = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val s = setup(dir, CalendarSource { fetches.incrementAndGet(); entered.complete(Unit); gate.await(); ok(ev) })
        val first = async(Dispatchers.Default) { s.refresher.refresh() }
        withTimeout(5_000) { entered.await() }
        assertNull(s.refresher.refresh())       // second call while the first is in flight
        assertNull(s.refresher.refresh())
        gate.complete(Unit)
        assertEquals(ok(ev), withTimeout(5_000) { first.await() })
        assertEquals(1, fetches.get())
        assertEquals(ok(ev), s.refresher.refresh()) // the lock was released: a new refresh runs
        assertEquals(2, fetches.get())
    }

    /** C3: cancel mid-refresh: CancellationException from refresh itself, cache untouched, the next refresh works. */
    @Test fun cancelMidRefreshChangesNothing(@TempDir dir: File) = runBlocking {
        var mode = 0
        val entered = CompletableDeferred<Unit>()
        val s = setup(dir, CalendarSource { if (mode == 0) ok(ev) else { entered.complete(Unit); awaitCancellation() } })
        s.refresher.refresh()
        val before = s.file.readBytes()
        mode = 1
        val out = CompletableDeferred<Result<FetchResult?>>()
        val job = launch(Dispatchers.Default) { out.complete(runCatching { s.refresher.refresh() }) }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        assertTrue(withTimeout(5_000) { out.await() }.exceptionOrNull() is CancellationException)
        assertArrayEquals(before, s.file.readBytes())
        mode = 0
        assertEquals(ok(ev), s.refresher.refresh())   // lock released; not stuck
    }

    /** Cancel arriving between a good fetch and the save saves nothing. */
    @Test fun cancelAfterOkBeforeSaveSavesNothing(@TempDir dir: File) = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val out = CompletableDeferred<Result<FetchResult?>>()
        lateinit var job: kotlinx.coroutines.Job
        val s = setup(dir, CalendarSource { job.cancel(); ok(ev) }) // the cancel lands just as the fetch returns Ok
        // LAZY: `job` must be assigned before the fetch can run and cancel it (was a race on Dispatchers.Default)
        job = launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            out.complete(runCatching { s.refresher.refresh() }); gate.complete(Unit)
        }
        job.start()
        withTimeout(5_000) { gate.await() }
        assertTrue(out.await().exceptionOrNull() is CancellationException, out.await().toString())
        assertFalse(s.file.exists())
    }

    @Test fun failingSaveStillReturnsTheResult(@TempDir dir: File) {
        val throwing = object : CacheFile {
            override fun read(): String? = null
            override fun writeAtomic(text: String) = throw java.io.IOException(FakeSecrets.CALENDAR_URL)
        }
        val r = AgendaRefresher({ ok(ev) }, throwing, { zone }, { now })
        assertEquals(ok(ev), runSuspend { r.refresh() })
    }
}
