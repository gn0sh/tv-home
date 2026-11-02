package tvhome.core.feeds

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** A GET of one public JSON document. Null for any failure: callers never see an exception or a response body. */
fun interface FeedFetcher {
    suspend fun fetch(url: HttpUrl): String?
}

/**
 * The live-feeds HTTP client (ADR 0005). Same house rules as the Ask client: a host allowlist checked on every hop,
 * https only, connect/read/call timeouts, no redirects, no retry, a hard response cap, and failures that carry no text.
 * Differences: no secret exists here, and the only header sent is a plain User-Agent (no identifiers, no cookies).
 *
 * The call runs on OkHttp's own dispatcher threads (enqueue), so it never touches the caller's thread, and cancelling
 * the coroutine cancels the call.
 */
class HttpFeedFetcher internal constructor(
    private val allowed: (HttpUrl) -> Boolean,
    timeoutMs: Long,
    customize: OkHttpClient.Builder.() -> Unit,
) : FeedFetcher {

    constructor() : this(::isAllowed, TIMEOUT_MS, {})

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().apply(customize)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor { chain ->
                if (!allowed(chain.request().url)) throw java.net.ProtocolException()
                chain.proceed(chain.request())
            }
            .build()
    }

    override suspend fun fetch(url: HttpUrl): String? {
        if (!allowed(url)) return null
        val call = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build())
        } catch (_: Throwable) {
            return null
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cont.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val text = try { response.use(::readCapped) } catch (_: Throwable) { null }
                    cont.resume(text)
                }
            })
        }
    }

    private fun readCapped(response: Response): String? {
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        if (body.contentLength() > MAX_BODY) return null
        val source = body.source()
        source.request(MAX_BODY + 1) // buffers up to the cap plus one byte, or to the end of the body
        if (source.buffer.size > MAX_BODY) return null
        return source.buffer.readUtf8()
    }

    override fun toString(): String = "HttpFeedFetcher"

    companion object {
        /** Exactly these three hosts (task M2f): weather, NHL schedule, ESPN schedule. */
        val HOSTS: Set<String> = setOf("api.open-meteo.com", "api-web.nhle.com", "site.api.espn.com")
        const val MAX_BODY = 512L * 1024
        const val TIMEOUT_MS = 10_000L
        const val USER_AGENT = "tvhome-hero/1"

        internal fun isAllowed(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && url.host in HOSTS
    }
}
