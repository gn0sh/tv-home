package tvhome.core.assistant

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Raw HTTPS to the Messages API (ADR 0002 "M1b lean": the documented raw-HTTP fallback instead of the SDK, to keep the
 * APK small and R8-simple). Non-streaming, 20 s for the whole call, no redirects, host pinned to api.anthropic.com on
 * every hop. The key is read from [apiKey] (the SecretStore) per call and only ever goes into the `x-api-key` header.
 * Every failure is a [ClaudeError]: no exception, response body or header text escapes, and nothing is logged.
 *
 * The internal constructor takes a base URL and a [customize] hook for MockWebServer tests in this module only.
 */
class AnthropicClient internal constructor(
    private val apiKey: () -> String?,
    private val baseUrl: HttpUrl,
    private val allowed: (HttpUrl) -> Boolean,
    timeoutMs: Long,
    customize: OkHttpClient.Builder.() -> Unit,
) : ClaudeClient {

    constructor(apiKey: () -> String?) :
        this(apiKey, API_URL.toHttpUrl(), ::isPinned, AskConfig.TIMEOUT_MS, {})

    private val client: OkHttpClient = OkHttpClient.Builder().apply(customize)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false) // a retry is a second paid request; Brian presses Retry instead
        .addNetworkInterceptor { chain ->
            if (!allowed(chain.request().url)) throw OffPin()
            chain.proceed(chain.request())
        }
        .build()

    override suspend fun send(apps: List<AskApp>, turns: List<Turn>): ClaudeResult {
        if (!allowed(baseUrl)) return ClaudeResult.Failed(ClaudeError.HTTP_STATUS)
        val result = try {
            // The key is read on the IO dispatcher (KeystoreSecretStore decrypts), together with the call.
            val key = withContext(Dispatchers.IO) { runCatching { apiKey() }.getOrNull() }?.takeIf { it.isNotBlank() }
                ?: return ClaudeResult.Failed(ClaudeError.NO_KEY)
            val request = Request.Builder()
                .url(baseUrl)
                .header("x-api-key", key)
                .header("anthropic-version", API_VERSION)
                .header("anthropic-beta", FALLBACK_BETA)
                .header("content-type", "application/json")
                .post(requestBody(apps, turns).toRequestBody(JSON))
                .build()
            val call = client.newCall(request)
            coroutineScope {
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    withContext(Dispatchers.IO) { execute(call) }
                } finally {
                    canceller.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            // Deliberately no text: OkHttp messages can carry the URL or header values.
            ClaudeResult.Failed(if (t is InterruptedIOException) ClaudeError.TIMEOUT else ClaudeError.NETWORK)
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun execute(call: Call): ClaudeResult = call.execute().use { response ->
        if (!response.isSuccessful) return ClaudeResult.Failed(statusError(response.code))
        val body = response.body ?: return ClaudeResult.Failed(ClaudeError.BAD_RESPONSE)
        if (body.contentLength() > MAX_BODY) return ClaudeResult.Failed(ClaudeError.BAD_RESPONSE)
        val source = body.source()
        source.request(MAX_BODY + 1) // buffers up to the cap plus one byte, or to the end of the body
        if (source.buffer.size > MAX_BODY) return ClaudeResult.Failed(ClaudeError.BAD_RESPONSE)
        replyText(source.buffer.readUtf8())
    }

    /** Defence in depth: the client's toString never shows the key provider or the base URL's query. */
    override fun toString(): String = "AnthropicClient"

    /** A redirect or hop off the pin: never retried, no message, no trace. */
    private class OffPin : java.net.ProtocolException() {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        const val API_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private const val MAX_BODY = 256L * 1024
        private val JSON = "application/json".toMediaType()
        private val envelope = Json { ignoreUnknownKeys = true }

        /** 401/403 key refused, 400 request refused, 429/529 busy; anything else (5xx, 3xx) is "can't reach". */
        internal fun statusError(code: Int): ClaudeError = when (code) {
            401, 403 -> ClaudeError.KEY_REJECTED
            400 -> ClaudeError.BAD_REQUEST
            429, 529 -> ClaudeError.BUSY
            else -> ClaudeError.HTTP_STATUS
        }

        internal fun isPinned(url: HttpUrl): Boolean = url.isHttps && url.host == "api.anthropic.com"

        /**
         * The Messages API envelope: a declined request (`stop_reason` refusal) is [ClaudeError.REFUSED]; a cut-off
         * one (max_tokens) or one with no text is [ClaudeError.BAD_RESPONSE]. Otherwise the LAST text block (after a
         * server-side fallback, earlier blocks may belong to the declined attempt) is the structured reply.
         */
        internal fun replyText(raw: String): ClaudeResult {
            val obj = runCatching { envelope.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                ?: return ClaudeResult.Failed(ClaudeError.BAD_RESPONSE)
            when ((obj["stop_reason"] as? JsonPrimitive)?.content) {
                "refusal" -> return ClaudeResult.Failed(ClaudeError.REFUSED)
                "max_tokens" -> return ClaudeResult.Failed(ClaudeError.BAD_RESPONSE)
            }
            val content = obj["content"] as? JsonArray ?: return ClaudeResult.Failed(ClaudeError.BAD_RESPONSE)
            val text = content.filterIsInstance<JsonObject>()
                .lastOrNull { (it["type"] as? JsonPrimitive)?.content == "text" }
                ?.let { (it["text"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.takeIf { it.isNotBlank() }
                ?: return ClaudeResult.Failed(ClaudeError.BAD_RESPONSE)
            return ClaudeResult.Text(text)
        }
    }
}
