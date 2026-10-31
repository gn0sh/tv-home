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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import kotlin.random.Random

class IcsCalendarSourceTest {
    private val window = InstantRange(Instant.parse("2026-10-30T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z"))
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private val oneEvent = listOf(EventInstance("u1", "Dentist", EventTime.At(now), null))
    // Minimal stub for T2's IcsParser: any text containing VCALENDAR parses to one event, anything else throws.
    private val stubParser = IcsParse { body, _ ->
        if (String(body).contains("VCALENDAR")) ParseOutcome.Complete(oneEvent, ParseStats(1, 0, 0, 0, 0, false, 1))
        else throw IllegalStateException("bad ics near ${FakeSecrets.CALENDAR_URL}")
    }

    private fun source(store: InMemorySecretStore, fetcher: BodyFetcher, parser: IcsParse = stubParser) =
        IcsCalendarSource(store, fetcher, parser) { now }

    private fun storeWithUrl(u: String = FakeSecrets.CALENDAR_URL) = InMemorySecretStore(mapOf(SecretKey.CALENDAR_URL to u))

    @Test fun noSecretIsNotConfiguredAndNeverFetches() {
        var called = false
        val s = source(InMemorySecretStore(), { called = true; IcsFetchResult.Body("") })
        assertEquals(FetchResult.Failed(SourceError.NOT_CONFIGURED), runSuspend { s.fetch(window) })
        assertFalse(called)
        val blank = source(storeWithUrl("  "), { called = true; IcsFetchResult.Body("") })
        assertEquals(FetchResult.Failed(SourceError.NOT_CONFIGURED), runSuspend { blank.fetch(window) })
        assertFalse(called)
    }

    @Test fun goodBodyParsesToOk() {
        val s = source(storeWithUrl(), { IcsFetchResult.Body("BEGIN:VCALENDAR") })
        assertEquals(FetchResult.Ok(oneEvent, now), runSuspend { s.fetch(window) })
    }

    @Test fun fetcherErrorsPassThroughAsEnum() {
        for (e in SourceError.values()) {
            val s = source(storeWithUrl(), { IcsFetchResult.Error(e) })
            assertEquals(FetchResult.Failed(e), runSuspend { s.fetch(window) })
        }
    }

    @Test fun parserFailureIsParseWithNoText() {
        val s = source(storeWithUrl(), { IcsFetchResult.Body("garbage") })
        val r = runSuspend { s.fetch(window) }
        assertEquals(FetchResult.Failed(SourceError.PARSE), r)
        assertFalse(r.toString().contains(FakeSecrets.CALENDAR_TOKEN))
    }

    @Test fun fetcherThatThrowsIsNetworkWithNoText() {
        val s = source(storeWithUrl(), { throw IOException(FakeSecrets.CALENDAR_URL) })
        val r = runSuspend { s.fetch(window) }
        assertEquals(FetchResult.Failed(SourceError.NETWORK), r)
    }

    @Test fun realParserMapsIcsParseExceptionToParseAndGoodFeedToOk() {
        val bad = source(storeWithUrl(), { IcsFetchResult.Body("not a calendar ${FakeSecrets.CALENDAR_URL}") }, IcsParse.ics { java.time.ZoneOffset.UTC })
        val r = runSuspend { bad.fetch(window) }
        assertEquals(FetchResult.Failed(SourceError.PARSE), r)
        assertFalse(r.toString().contains(FakeSecrets.CALENDAR_TOKEN))
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\nBEGIN:VEVENT\r\nUID:a@t\r\nDTSTAMP:20260101T000000Z\r\n" +
            "DTSTART:20261031T150000Z\r\nDTEND:20261031T160000Z\r\nSUMMARY:Lunch\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val good = source(storeWithUrl(), { IcsFetchResult.Body(ics) }, IcsParse.ics { java.time.ZoneOffset.UTC })
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
            val fetcher = IcsFetcher(FetchTimeouts(), IcsFetcher.MAX_BYTES) { sslSocketFactory(client.sslSocketFactory(), client.trustManager) }
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
        val s = source(storeWithUrl(), { IcsFetchResult.Body("BEGIN:VCALENDAR") }, spinning)
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
            val viaParser = source(store, { IcsFetchResult.Body("x") }, { _, _ -> throw thrown })
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
}
