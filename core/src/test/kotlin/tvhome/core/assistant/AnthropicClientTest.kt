package tvhome.core.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import tvhome.core.FakeSecrets
import tvhome.core.runSuspend
import java.util.concurrent.TimeUnit

class AnthropicClientTest {
    private lateinit var server: MockWebServer
    private lateinit var trust: OkHttpClient.Builder.() -> Unit
    private val apps = listOf(AskApp("Netflix", "com.netflix.ninja"))
    private val turns = listOf(Turn(Turn.Role.USER, "something funny"))

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

    private fun client(key: String? = FakeSecrets.API_KEY, timeoutMs: Long = 2_000, allowed: (okhttp3.HttpUrl) -> Boolean = { it.isHttps }) =
        AnthropicClient({ key }, server.url("/v1/messages"), allowed, timeoutMs, trust)

    private fun send(c: AnthropicClient) = runSuspend { c.send(apps, turns) }

    @Test fun sendsHeadersAndBodyAndReadsTheLastTextBlock() {
        server.enqueue(MockResponse().setBody(fixture("envelope-ok.json")))
        val r = send(client()) as ClaudeResult.Text
        assertEquals("""{"answer":"Hello","open":null}""", r.text)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals(FakeSecrets.API_KEY, req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        assertEquals("server-side-fallback-2026-07-01", req.getHeader("anthropic-beta"))
        assertNull(req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertFalse(body.toString().contains(FakeSecrets.API_KEY), "the key is never in the body")
    }

    @Test fun postSendsTheGivenBodyWithTheSameKeyAndNoFallbackBeta() {
        server.enqueue(MockResponse().setBody(fixture("envelope-ok.json")))
        val body = """{"model":"claude-opus-5-5","max_tokens":5,"messages":[]}"""
        val r = runSuspend { client().post(body) } as ClaudeResult.Text
        assertTrue(r.text.isNotEmpty())
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(FakeSecrets.API_KEY, req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        assertNull(req.getHeader("anthropic-beta"), "the brief never opts into the server-side fallback")
        assertEquals(body, req.body.readUtf8())
        assertEquals(ClaudeResult.Failed(ClaudeError.NO_KEY), runSuspend { client(key = null).post(body) })
    }

    @Test fun afterAServerSideFallbackTheFinalTextIsUsed() {
        server.enqueue(MockResponse().setBody(fixture("envelope-fallback.json")))
        assertEquals("""{"answer":"From the fallback","open":null}""", (send(client()) as ClaudeResult.Text).text)
    }

    @Test fun refusalAndCutOffReplies() {
        server.enqueue(MockResponse().setBody(fixture("envelope-refusal.json")))
        assertEquals(ClaudeResult.Failed(ClaudeError.REFUSED), send(client()))
        server.enqueue(MockResponse().setBody(fixture("envelope-max-tokens.json")))
        assertEquals(ClaudeResult.Failed(ClaudeError.BAD_RESPONSE), send(client()))
        server.enqueue(MockResponse().setBody("not json"))
        assertEquals(ClaudeResult.Failed(ClaudeError.BAD_RESPONSE), send(client()))
        server.enqueue(MockResponse().setBody("""{"stop_reason":"end_turn","content":[]}"""))
        assertEquals(ClaudeResult.Failed(ClaudeError.BAD_RESPONSE), send(client()))
    }

    @Test fun httpErrorsAreSplitByMeaningWithNoBodyText() {
        val expected = mapOf(
            400 to ClaudeError.BAD_REQUEST,
            401 to ClaudeError.KEY_REJECTED,
            403 to ClaudeError.KEY_REJECTED,
            404 to ClaudeError.HTTP_STATUS,
            429 to ClaudeError.BUSY,
            500 to ClaudeError.HTTP_STATUS,
            503 to ClaudeError.HTTP_STATUS,
            529 to ClaudeError.BUSY,
        )
        for ((code, err) in expected) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"${FakeSecrets.API_KEY}"}}"""))
            val r = send(client())
            assertEquals(ClaudeResult.Failed(err), r, "$code")
            assertFalse(r.toString().contains(FakeSecrets.API_KEY))
        }
    }

    @Test fun theKeyIsReadOffTheCallingThread() {
        server.enqueue(MockResponse().setBody(fixture("envelope-ok.json")))
        val caller = Thread.currentThread()
        var readOn: Thread? = null
        val c = AnthropicClient({ readOn = Thread.currentThread(); FakeSecrets.API_KEY }, server.url("/v1/messages"), { it.isHttps }, 2_000, trust)
        assertTrue(send(c) is ClaudeResult.Text)
        assertNotNull(readOn)
        assertNotSame(caller, readOn)
    }

    @Test fun redirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
        assertEquals(ClaudeResult.Failed(ClaudeError.HTTP_STATUS), send(client()))
        assertEquals(1, server.requestCount)
    }

    @Test fun timeoutIsReported() {
        server.enqueue(MockResponse().setBody(fixture("envelope-ok.json")).setHeadersDelay(3, TimeUnit.SECONDS))
        assertEquals(ClaudeResult.Failed(ClaudeError.TIMEOUT), send(client(timeoutMs = 500)))
    }

    @Test fun connectionFailureIsNetworkAndNotRetried() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ClaudeResult.Failed(ClaudeError.NETWORK), send(client()))
        assertEquals(1, server.requestCount)
    }

    @Test fun oversizeBodyIsRefused() {
        server.enqueue(MockResponse().setBody("x".repeat(300 * 1024)))
        assertEquals(ClaudeResult.Failed(ClaudeError.BAD_RESPONSE), send(client()))
    }

    @Test fun noKeySendsNothing() {
        assertEquals(ClaudeResult.Failed(ClaudeError.NO_KEY), send(client(key = null)))
        assertEquals(ClaudeResult.Failed(ClaudeError.NO_KEY), send(client(key = "  ")))
        assertEquals(0, server.requestCount)
    }

    @Test fun thePinAllowsOnlyHttpsApiAnthropicCom() {
        assertTrue(AnthropicClient.isPinned("https://api.anthropic.com/v1/messages".toHttpUrl()))
        assertFalse(AnthropicClient.isPinned("http://api.anthropic.com/v1/messages".toHttpUrl()))
        assertFalse(AnthropicClient.isPinned("https://api.anthropic.com.evil.test/v1/messages".toHttpUrl()))
        assertFalse(AnthropicClient.isPinned("https://evil.test/v1/messages".toHttpUrl()))
        // The test server is not the pinned host: with the real pin nothing is sent to it.
        assertEquals(ClaudeResult.Failed(ClaudeError.HTTP_STATUS), send(client(allowed = AnthropicClient::isPinned)))
        assertEquals(0, server.requestCount)
        assertEquals("AnthropicClient", AnthropicClient { FakeSecrets.API_KEY }.toString())
    }
}
