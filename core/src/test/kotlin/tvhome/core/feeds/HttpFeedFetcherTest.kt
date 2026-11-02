package tvhome.core.feeds

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tvhome.core.runSuspend
import java.util.concurrent.TimeUnit

class HttpFeedFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var trust: OkHttpClient.Builder.() -> Unit

    @BeforeEach fun setUp() {
        server = MockWebServer()
        val localhost = java.net.InetAddress.getByName("localhost").canonicalHostName
        val held = HeldCertificate.Builder().addSubjectAlternativeName(localhost).build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        trust = { sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager) }
    }
    @AfterEach fun tearDown() { runCatching { server.shutdown() } }

    private fun fetcher(timeoutMs: Long = 2_000, allowed: (HttpUrl) -> Boolean = { it.isHttps }) = HttpFeedFetcher(allowed, timeoutMs, trust)
    private fun get(f: HttpFeedFetcher, path: String = "/x") = runSuspend { f.fetch(server.url(path)) }

    @Test fun okBodyAndOnlyAPlainUserAgentIsSent() {
        server.enqueue(MockResponse().setBody("""{"a":1}"""))
        assertEquals("""{"a":1}""", get(fetcher()))
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertEquals(HttpFeedFetcher.USER_AGENT, req.getHeader("User-Agent"))
        for (h in listOf("Authorization", "Cookie", "x-api-key", "Referer")) assertNull(req.getHeader(h), h)
    }

    @Test fun errorsRedirectsAndOversizeAreNull() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertNull(get(fetcher()))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/y").toString()).setBody("moved"))
        assertNull(get(fetcher()))
        server.enqueue(MockResponse().setBody("x".repeat((HttpFeedFetcher.MAX_BODY + 1).toInt())))
        assertNull(get(fetcher()))
        server.enqueue(MockResponse().setBody("x".repeat(HttpFeedFetcher.MAX_BODY.toInt())))
        assertEquals(HttpFeedFetcher.MAX_BODY.toInt(), get(fetcher())!!.length)
    }

    @Test fun chunkedOversizeWithNoLengthIsNull() {
        server.enqueue(MockResponse().setChunkedBody("y".repeat((HttpFeedFetcher.MAX_BODY + 10).toInt()), 8192))
        assertNull(get(fetcher()))
    }

    @Test fun stalledServerTimesOut() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.nanoTime()
        assertNull(get(fetcher(timeoutMs = 300)))
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(5))
    }

    @Test fun offAllowlistHostsAreNeverContacted() {
        assertNull(get(fetcher(allowed = { false })))
        assertEquals(0, server.requestCount)
        val real = HttpFeedFetcher.Companion
        assertTrue(real.isAllowed(okhttp3.HttpUrl.Builder().scheme("https").host("api-web.nhle.com").addPathSegment("v1").build()))
        assertFalse(real.isAllowed(okhttp3.HttpUrl.Builder().scheme("http").host("api-web.nhle.com").build()))
        assertFalse(real.isAllowed(okhttp3.HttpUrl.Builder().scheme("https").host("evil.example").build()))
        assertFalse(real.isAllowed(okhttp3.HttpUrl.Builder().scheme("https").host("api-web.nhle.com.evil.example").build()))
        assertFalse(real.isAllowed(okhttp3.HttpUrl.Builder().scheme("https").host("api-web.nhle.com").port(8443).build()))
        assertEquals(setOf("api.open-meteo.com", "api-web.nhle.com", "site.api.espn.com"), HttpFeedFetcher.HOSTS)
    }

    @Test fun configuredTeamUrlsAreAllowlisted() {
        for (t in Teams.ALL) assertTrue(HttpFeedFetcher.isAllowed(t.scheduleUrl()), t.key)
        assertEquals("https://api-web.nhle.com/v1/club-schedule/NSH/week/now", Teams.PREDATORS.scheduleUrl().toString())
    }
}
