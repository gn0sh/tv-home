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
import java.io.IOException
import java.time.Instant
import kotlin.random.Random

class IcsCalendarSourceTest {
    private val window = InstantRange(Instant.parse("2026-10-30T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z"))
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private val oneEvent = listOf(EventInstance("u1", "Dentist", EventTime.At(now), null))
    // Minimal stub for T2's IcsParser: any text containing VCALENDAR parses to one event, anything else throws.
    private val stubParser = IcsParse { ics, _ ->
        if (ics.contains("VCALENDAR")) oneEvent else throw IllegalStateException("bad ics near ${FakeSecrets.CALENDAR_URL}")
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

    @Test fun endToEndOverTls() {
        val server = MockWebServer()
        val host = java.net.InetAddress.getByName("localhost").canonicalHostName
        val held = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        server.start()
        try {
            val client = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
            val fetcher = IcsFetcher { sslSocketFactory(client.sslSocketFactory(), client.trustManager) }
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
