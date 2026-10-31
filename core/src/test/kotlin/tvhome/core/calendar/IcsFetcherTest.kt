package tvhome.core.calendar

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tvhome.core.FakeSecrets
import java.io.IOException

class IcsFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var plain: MockWebServer
    private lateinit var trust: OkHttpClient.Builder.() -> Unit
    private val path = "/ical/x/${FakeSecrets.CALENDAR_TOKEN}/basic.ics"

    @BeforeEach fun setUp() {
        server = MockWebServer()
        plain = MockWebServer()
        val localhost = java.net.InetAddress.getByName("localhost").canonicalHostName
        val held = HeldCertificate.Builder().addSubjectAlternativeName(localhost).build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1) // MockWebServer's chunked bodies are not framed over h2
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        plain.start()
        trust = { sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager) }
    }

    @AfterEach fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { plain.shutdown() }
    }

    private fun fetcher(timeouts: FetchTimeouts = FetchTimeouts(), max: Long = IcsFetcher.MAX_BYTES, extra: OkHttpClient.Builder.() -> Unit = {}) =
        IcsFetcher(timeouts, max) { trust(); extra() }

    private fun url(p: String = path) = server.url(p).toString()

    private fun assertError(e: SourceError, r: IcsFetchResult) {
        assertEquals(IcsFetchResult.Error(e), r)
        assertFalse(r.toString().contains(FakeSecrets.CALENDAR_TOKEN))
    }

    @Test fun okBodyIsReturned() {
        server.enqueue(MockResponse().setBody("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"))
        val r = fetcher().fetch(url())
        assertTrue(r is IcsFetchResult.Body)
        assertEquals("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", (r as IcsFetchResult.Body).text)
        assertFalse(r.toString().contains("VCALENDAR"))
        assertEquals(path, server.takeRequest().path)
    }

    @Test fun httpUrlRejectedBeforeAnyIo() {
        val r = fetcher().fetch(plain.url("/x.ics").toString())
        assertError(SourceError.INSECURE_URL, r)
        assertEquals(0, plain.requestCount)
        assertError(SourceError.INSECURE_URL, fetcher().fetch("HTTP://example.test/x.ics"))
        assertError(SourceError.INSECURE_URL, fetcher().fetch("ftp://example.test/x.ics"))
        assertError(SourceError.INSECURE_URL, fetcher().fetch("file:///etc/passwd"))
        assertError(SourceError.INSECURE_URL, fetcher().fetch("webcal://example.test/x.ics"))
    }

    @Test fun blankAndGarbageUrlsAreNotConfigured() {
        assertError(SourceError.NOT_CONFIGURED, fetcher().fetch(""))
        assertError(SourceError.NOT_CONFIGURED, fetcher().fetch("   "))
        assertError(SourceError.NOT_CONFIGURED, fetcher().fetch("not a url"))
        assertError(SourceError.NOT_CONFIGURED, fetcher().fetch("https://"))
    }

    @Test fun non2xxIsHttpStatus() {
        for (code in listOf(401, 403, 404, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("denied ${FakeSecrets.CALENDAR_TOKEN}"))
            assertError(SourceError.HTTP_STATUS, fetcher().fetch(url()))
        }
    }

    @Test fun httpsToHttpRedirectIsRefusedAndNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", plain.url(path).toString()))
        plain.enqueue(MockResponse().setBody("BEGIN:VCALENDAR"))
        assertError(SourceError.HTTP_STATUS, fetcher().fetch(url()))
        assertEquals(0, plain.requestCount)
    }

    @Test fun httpsToHttpsRedirectOnSameServerIsFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", url("/moved.ics")))
        server.enqueue(MockResponse().setBody("OK"))
        val r = fetcher().fetch(url())
        assertEquals("OK", (r as IcsFetchResult.Body).text)
    }

    @Test fun bodyAtCapIsAcceptedAndOverCapIsTooLarge() {
        val cap = 1000L
        server.enqueue(MockResponse().setBody("a".repeat(1000)))
        assertTrue(fetcher(max = cap).fetch(url()) is IcsFetchResult.Body)
        server.enqueue(MockResponse().setBody("a".repeat(1001)))
        assertError(SourceError.TOO_LARGE, fetcher(max = cap).fetch(url()))
    }

    private fun mib10(extra: Int = 0) = Buffer().write(ByteArray(10 * 1024 * 1024 + extra) { 'a'.code.toByte() })

    @Test fun tenMegabyteCapOverWithContentLength() {
        server.enqueue(MockResponse().setBody(mib10(1)))
        assertError(SourceError.TOO_LARGE, fetcher().fetch(url()))
    }

    @Test fun tenMegabyteCapOverWithoutContentLength() {
        server.enqueue(MockResponse().setChunkedBody(mib10(1), 8192))
        assertError(SourceError.TOO_LARGE, fetcher().fetch(url()))
    }

    @Test fun exactlyTenMegabytesIsAccepted() {
        server.enqueue(MockResponse().setChunkedBody(mib10(), 8192))
        val r = fetcher().fetch(url())
        assertTrue(r is IcsFetchResult.Body, r.toString())
        server.enqueue(MockResponse().setBody(mib10()))
        val r2 = fetcher().fetch(url())
        assertTrue(r2 is IcsFetchResult.Body, r2.toString())
    }

    @Test fun gzipBombIsCappedAfterDecompression() {
        val raw = Buffer().write(ByteArray(20 * 1024 * 1024))
        val gz = Buffer()
        GzipSink(gz).buffer().use { it.writeAll(raw) }
        assertTrue(gz.size < 100_000)
        server.enqueue(MockResponse().addHeader("Content-Encoding", "gzip").setBody(gz))
        assertError(SourceError.TOO_LARGE, fetcher().fetch(url()))
    }

    @Test fun readTimeoutIsNetwork() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t = FetchTimeouts(connectMs = 500, readMs = 300, callMs = 2_000)
        assertError(SourceError.NETWORK, fetcher(t).fetch(url()))
    }

    @Test fun connectionRefusedIsNetwork() {
        val u = url()
        server.shutdown()
        assertError(SourceError.NETWORK, fetcher().fetch(u))
    }

    @Test fun untrustedCertificateIsNetwork() {
        server.enqueue(MockResponse().setBody("x"))
        assertError(SourceError.NETWORK, IcsFetcher().fetch(url()))   // default trust store does not know our test CA
    }

    @Test fun unknownHostIsOffline() {
        assertError(SourceError.OFFLINE, IcsFetcher(customize = { proxy(java.net.Proxy.NO_PROXY); dns(object : okhttp3.Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = throw java.net.UnknownHostException(hostname) }) })
            .fetch("https://calendar.example.test/${FakeSecrets.CALENDAR_TOKEN}/basic.ics"))
    }

    @Test fun exceptionTextWithUrlOrHeaderValueNeverEscapes() {
        // OkHttp-style messages: the URL in an IOException, a header value in an IllegalArgumentException (Headers.checkValue).
        val throwers = listOf<Throwable>(
            IOException("failed to connect to ${url()}"),
            IllegalArgumentException("Unexpected char 0x0a at 3 in X-Key value: ${FakeSecrets.API_KEY}"),
            RuntimeException("boom ${FakeSecrets.CALENDAR_TOKEN}", IOException(FakeSecrets.CALENDAR_URL)),
            IllegalStateException(FakeSecrets.CALENDAR_TOKEN).also { it.addSuppressed(IOException(FakeSecrets.CALENDAR_URL)) },
            AssertionError(FakeSecrets.CALENDAR_TOKEN),      // a Throwable that is not an Exception
        )
        for (t in throwers) {
            val f = fetcher(extra = { addInterceptor { throw t } })
            val r = f.fetch(url())
            assertError(SourceError.NETWORK, r)
            assertFalse(r.toString().contains(FakeSecrets.API_KEY))
        }
    }
}
