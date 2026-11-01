package tvhome.core.calendar

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.FakeSecrets
import tvhome.core.runSuspend
import tvhome.core.secrets.InMemorySecretStore
import tvhome.core.secrets.Redactor
import tvhome.core.secrets.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import kotlin.random.Random

class IcsCalendarSourceTest {
    private val window = InstantRange(Instant.parse("2026-10-30T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z"))
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private val oneEvent = listOf(EventInstance("u1", "Dentist", EventTime.At(now), null))
    private val stats = ParseStats(1, 0, 0, 0, 0, truncated = false, steps = 1)
    private fun complete(events: List<EventInstance> = oneEvent) = ParseOutcome.Complete(events, stats)

    // Stub for the bounded parse (T2b.1 supplies the real one): a body containing VCALENDAR is Complete with one
    // event, anything else throws (a bug in the parser, mapped to PARSE).
    private val stubParser = IcsParse { body, _ ->
        if (String(body).contains("VCALENDAR")) complete() else throw IllegalStateException("bad ics near ${FakeSecrets.CALENDAR_URL}")
    }

    private fun body(s: String) = IcsFetchResult.Body(s.toByteArray())

    /** Test source: the stored-URL check is open, because the test servers and fake hosts are not Google. */
    private fun source(store: InMemorySecretStore, fetcher: BodyFetcher, parser: IcsParse = stubParser) =
        IcsCalendarSource(store, fetcher, parser, Dispatchers.IO, Dispatchers.Default, { now }, { true })

    private fun storeWithUrl(u: String = FakeSecrets.CALENDAR_URL) = InMemorySecretStore(mapOf(SecretKey.CALENDAR_URL to u))

    @Test fun noSecretIsNotConfiguredAndNeverFetches() {
        var called = false
        val s = source(InMemorySecretStore(), { called = true; body("") })
        assertEquals(FetchResult.Failed(SourceError.NOT_CONFIGURED), runSuspend { s.fetch(window) })
        assertFalse(called)
        val blank = source(storeWithUrl("  "), { called = true; body("") })
        assertEquals(FetchResult.Failed(SourceError.NOT_CONFIGURED), runSuspend { blank.fetch(window) })
        assertFalse(called)
    }

    @Test fun goodBodyParsesToOk() {
        val s = source(storeWithUrl(), { body("BEGIN:VCALENDAR") })
        assertEquals(FetchResult.Ok(oneEvent, now), runSuspend { s.fetch(window) })
    }

    @Test fun fetcherErrorsPassThroughAsEnum() {
        for (e in SourceError.values()) {
            val s = source(storeWithUrl(), { IcsFetchResult.Error(e) })
            assertEquals(FetchResult.Failed(e), runSuspend { s.fetch(window) })
        }
    }

    @Test fun parserFailureIsParseWithNoText() {
        val s = source(storeWithUrl(), { body("garbage") })
        val r = runSuspend { s.fetch(window) }
        assertEquals(FetchResult.Failed(SourceError.PARSE), r)
        assertFalse(r.toString().contains(FakeSecrets.CALENDAR_TOKEN))
    }

    @Test fun fetcherThatThrowsIsNetworkWithNoText() {
        val s = source(storeWithUrl(), { throw IOException(FakeSecrets.CALENDAR_URL) })
        val r = runSuspend { s.fetch(window) }
        assertEquals(FetchResult.Failed(SourceError.NETWORK), r)
    }

    @Test fun realParserAdapterMapsIcsParseExceptionToParseAndGoodFeedToOk() {
        val bad = source(storeWithUrl(), { body("not a calendar ${FakeSecrets.CALENDAR_URL}") }, IcsParse.ics { java.time.ZoneOffset.UTC })
        val r = runSuspend { bad.fetch(window) }
        assertEquals(FetchResult.Failed(SourceError.PARSE), r)
        assertFalse(r.toString().contains(FakeSecrets.CALENDAR_TOKEN))
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\nBEGIN:VEVENT\r\nUID:a@t\r\nDTSTAMP:20260101T000000Z\r\n" +
            "DTSTART:20261031T150000Z\r\nDTEND:20261031T160000Z\r\nSUMMARY:Lunch\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val good = source(storeWithUrl(), { body(ics) }, IcsParse.ics { java.time.ZoneOffset.UTC })
        val ok = runSuspend { good.fetch(window) } as FetchResult.Ok
        assertEquals(listOf("Lunch"), ok.events.map { it.title })
    }

    @Test fun endToEndOverTls() {
        val server = MockWebServer()
        val host = java.net.InetAddress.getByName("localhost").canonicalHostName
        val held = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        server.start()
        try {
            val client = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
            val fetcher = IcsFetcher(FetchTimeouts(), IcsFetcher.MAX_BYTES, { true }) { sslSocketFactory(client.sslSocketFactory(), client.trustManager) }
            server.enqueue(MockResponse().setBody("BEGIN:VCALENDAR"))
            val u = server.url("/ical/x/${FakeSecrets.CALENDAR_TOKEN}/basic.ics").toString()
            val r = runSuspend { source(storeWithUrl(u), fetcher).fetch(window) }
            assertEquals(FetchResult.Ok(oneEvent, now), r)
            // the secret travels only in the request line to the host the user provisioned
            assertEquals("/ical/x/${FakeSecrets.CALENDAR_TOKEN}/basic.ics", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    /** blocking-in-suspend: fetch is main-safe and cancelling it interrupts a parse in progress. */
    @Test fun cancellingFetchInterruptsTheParse() {
        val started = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val spinning = IcsParse { _, _ ->
            started.countDown()
            while (!Thread.currentThread().isInterrupted) { /* CPU-bound work that polls the interrupt flag */ }
            interrupted.set(true)
            throw InterruptedException()
        }
        val s = source(storeWithUrl(), { body("BEGIN:VCALENDAR") }, spinning)
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10)) {
            runBlocking { // single thread: the parse must not run on it
                val job = async { s.fetch(window) }
                withContext(Dispatchers.IO) { assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                job.cancel()
                val r = runCatching { job.await() }
                assertTrue(r.exceptionOrNull() is CancellationException, r.toString())
            }
        }
        assertTrue(interrupted.get())
    }

    /**
     * Seeded property test: whatever random secret is provisioned and however an exception embeds it (message,
     * cause, suppressed, URL form), nothing in the FetchResult, its text, or the redacted log line contains it.
     * T4 adds the same assertion on AgendaState (not in this branch).
     */
    @Test fun seededPropertyNoSecretInResultsOrRedactedStrings() {
        val rnd = Random(31102026)
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_', '.')
        repeat(200) {
            // Random secrets avoid the "private-" / "sk-ant-" prefixes so build logs stay free of lookalikes.
            val token = "tok" + (1..rnd.nextInt(16, 64)).map { alphabet[rnd.nextInt(alphabet.size)] }.joinToString("")
            val u = "https://calendar.example.test/ical/$token/basic.ics"
            val msgs = listOf(u, "failed: $u", token, "Unexpected char in header value: $token", "$u -> $u")
            val msg = msgs[rnd.nextInt(msgs.size)]
            val thrown: Throwable = when (rnd.nextInt(4)) {
                0 -> IOException(msg)
                1 -> IllegalArgumentException(msg, IOException(u))
                2 -> RuntimeException(msg).also { it.addSuppressed(IllegalStateException(token)) }
                else -> Error(msg)
            }
            val store = storeWithUrl(u)
            val viaFetcher = source(store, { throw thrown })
            val viaParser = source(store, { body("x") }, { _, _ -> throw thrown })
            val results = listOf(runSuspend { viaFetcher.fetch(window) }, runSuspend { viaParser.fetch(window) })
            val redactor = Redactor { listOfNotNull(store.get(SecretKey.CALENDAR_URL)) }
            for (r in results) {
                assertTrue(r is FetchResult.Failed)
                assertFalse(r.toString().contains(token))
                assertFalse((r as FetchResult.Failed).error.name.contains(token))
            }
            // a careless logger that does print the exception: the Redactor must still remove the secret
            val careless = "fetch error: $thrown / ${thrown.cause} / ${thrown.suppressed.joinToString()}"
            assertFalse(redactor.redact(careless).contains(token), careless)
            assertFalse(store.toString().contains(token))
        }
    }

    // ---- T2b.2 ----

    /** ADR 0004 item 4: only Complete is Ok; Exhausted is PARSE_LIMIT; Rejected is PARSE. No path to Ok otherwise. */
    @Test fun outcomesMapToResults() {
        fun run(o: ParseOutcome) = runSuspend { source(storeWithUrl(), { body("x") }, { _, _ -> o }).fetch(window) }
        assertEquals(FetchResult.Ok(oneEvent, now), run(complete()))
        assertEquals(FetchResult.Ok(emptyList(), now), run(complete(emptyList())))
        for (l in ParseLimit.values()) assertEquals(FetchResult.Failed(SourceError.PARSE_LIMIT), run(ParseOutcome.Exhausted(l)))
        for (r in RejectReason.values()) assertEquals(FetchResult.Failed(SourceError.PARSE), run(ParseOutcome.Rejected(r)))
    }

    @Test fun parserReceivesTheExactBytesAndWindow() {
        val raw = byteArrayOf(0, 1, -1, -128, 127, 13, 10)
        var seen: ByteArray? = null
        var seenWindow: InstantRange? = null
        val s = source(storeWithUrl(), { IcsFetchResult.Body(raw) }, { b, w -> seen = b; seenWindow = w; complete() })
        runSuspend { s.fetch(window) }
        assertSame(raw, seen)          // no copy between fetcher and parser
        assertEquals(window, seenWindow)
    }

    /** H11: a stored URL that fails the provisioning check is NOT_CONFIGURED and makes no request. */
    @Test fun storedUrlIsRevalidatedBeforeAnyRequest() {
        var called = false
        val bad = listOf(
            "http://calendar.google.com/calendar/ical/a/private-" + "f".repeat(32) + "/basic.ics",
            "https://evil.example.test/calendar/ical/a/private-" + "f".repeat(32) + "/basic.ics",
            "https://calendar.google.com/calendar/ical/a/private-short/basic.ics",
            "https://calendar.google.com.evil.test/calendar/ical/a/private-" + "f".repeat(32) + "/basic.ics",
            "https://calendar.google.com/calendar/ical/a/b/c/basic.ics",
            "not a url",
        )
        for (u in bad) {
            val s = IcsCalendarSource(storeWithUrl(u), { called = true; body("BEGIN:VCALENDAR") }, stubParser) // the real check
            assertEquals(FetchResult.Failed(SourceError.NOT_CONFIGURED), runSuspend { s.fetch(window) }, u)
        }
        assertFalse(called)
        val good = IcsCalendarSource(storeWithUrl(), { called = true; body("BEGIN:VCALENDAR") }, stubParser, clock = { now })
        assertEquals(FetchResult.Ok(oneEvent, now), runSuspend { good.fetch(window) })
        assertTrue(called)
    }

    /** What `fetch` itself produced when its caller was cancelled while [blocked] was running. */
    private fun outcomeOnCancel(s: IcsCalendarSource, started: java.util.concurrent.CountDownLatch, afterCancel: () -> Unit = {}): Result<FetchResult> =
        runBlocking {
            val out = kotlinx.coroutines.CompletableDeferred<Result<FetchResult>>()
            val job = launch(Dispatchers.Default) { out.complete(runCatching { s.fetch(window) }) }
            withContext(Dispatchers.IO) { assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            job.cancel()
            afterCancel()
            kotlinx.coroutines.withTimeout(10_000) { out.await() }
        }

    /** cancel-returns-network-error (C2), download phase: a fetcher that fails because of the cancel. */
    @Test fun cancelDuringDownloadThrowsEvenIfTheFetcherFailsWithAnIoError() {
        val started = java.util.concurrent.CountDownLatch(1)
        val fetcher = BodyFetcher {
            started.countDown()
            try { kotlinx.coroutines.awaitCancellation() } catch (e: CancellationException) { throw IOException("Canceled") }
        }
        val r = outcomeOnCancel(source(storeWithUrl(), fetcher), started)
        assertTrue(r.exceptionOrNull() is CancellationException, "fetch returned: $r")
    }

    /** C2, parse phase: the parser fails with something other than InterruptedException when interrupted. */
    @Test fun cancelDuringParseThrowsEvenIfTheParserThrowsSomethingElse() {
        val started = java.util.concurrent.CountDownLatch(1)
        val parser = IcsParse { _, _ ->
            started.countDown()
            while (!Thread.currentThread().isInterrupted) { /* spin until the cancel interrupts us */ }
            throw IllegalStateException("parser bug after interrupt")
        }
        val r = outcomeOnCancel(source(storeWithUrl(), { body("x") }, parser), started)
        assertTrue(r.exceptionOrNull() is CancellationException, "fetch returned: $r")
    }

    /** Without a cancel the same failures are still PARSE / NETWORK (the fix must not swallow real errors). */
    @Test fun uncancelledFailuresStillMapToEnums() {
        assertEquals(FetchResult.Failed(SourceError.PARSE),
            runSuspend { source(storeWithUrl(), { body("x") }, { _, _ -> throw IllegalStateException() }).fetch(window) })
        assertEquals(FetchResult.Failed(SourceError.NETWORK),
            runSuspend { source(storeWithUrl(), { throw IOException() }).fetch(window) })
    }
}
