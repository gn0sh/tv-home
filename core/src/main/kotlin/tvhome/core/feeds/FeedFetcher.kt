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
 * Differences: no secret exists here, and the only headers sent are the fixed per-host set in [HEADERS] (a plain
 * User-Agent, plus `Accept` for NWS; no identifiers, no cookies). Header values are constants looked up by host, never
 * built from the URL.
 *
 * The call runs on OkHttp's own dispatcher threads (enqueue), so it never touches the caller's thread, and cancelling
 * the coroutine cancels the call.
 */
class HttpFeedFetcher internal constructor(
    private val allowed: (HttpUrl) -> Boolean,
    timeoutMs: Long,
    customize: OkHttpClient.Builder.() -> Unit,
    private val maxBody: (HttpUrl) -> Long = ::maxBodyFor,
    private val headers: (HttpUrl) -> Map<String, String> = ::headersFor,
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
            val b = Request.Builder().url(url)
            for ((k, v) in headers(url)) b.header(k, v)
            client.newCall(b.get().build())
        } catch (_: Throwable) {
            return null
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cont.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val text = try { response.use { readCapped(it, maxBody(url)) } } catch (_: Throwable) { null }
                    cont.resume(text)
                }
            })
        }
    }

    private fun readCapped(response: Response, max: Long): String? {
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        if (body.contentLength() > max) return null
        val source = body.source()
        source.request(max + 1) // buffers up to the cap plus one byte, or to the end of the body
        if (source.buffer.size > max) return null
        return source.buffer.readUtf8()
    }

    override fun toString(): String = "HttpFeedFetcher"

    companion object {
        /** Exactly these four hosts: weather, NHL schedule, ESPN schedule (M2f), NWS alerts (M7-B2). */
        val HOSTS: Set<String> = setOf("api.open-meteo.com", "api-web.nhle.com", "site.api.espn.com", "api.weather.gov")
        const val MAX_BODY = 512L * 1024
        /**
         * The ESPN team schedule carries about 50 KB per game (logos, venues, links): a 13-game season is over
         * [MAX_BODY], so that one path (`.../teams/<id>/schedule`) may be larger. Nothing else is raised.
         */
        const val ESPN_SCHEDULE_MAX_BODY = 2L * 1024 * 1024
        private val ESPN_SCHEDULE_PATH = Regex("/apis/site/v2/sports/football/college-football/teams/\\d{1,6}/schedule")

        internal fun maxBodyFor(url: HttpUrl): Long = when {
            url.host == "site.api.espn.com" && ESPN_SCHEDULE_PATH.matches(url.encodedPath) -> ESPN_SCHEDULE_MAX_BODY
            url.host == Picks.HOST -> Picks.MAX_BODY
            else -> MAX_BODY
        }

        const val TIMEOUT_MS = 10_000L
        const val USER_AGENT = "tvhome-hero/1"

        /**
         * NWS requires a User-Agent that identifies the application (weather.gov/documentation/services-web-API). It
         * names the app and its project page only: no person, email, device or location.
         */
        const val NWS_USER_AGENT = "tvhome/1 (+https://github.com/gn0sh/tv-home)"
        const val NWS_HOST = "api.weather.gov"

        private val DEFAULT_HEADERS: Map<String, String> = mapOf("User-Agent" to USER_AGENT)

        /** Fixed headers per host; any host not listed gets [DEFAULT_HEADERS]. */
        val HEADERS: Map<String, Map<String, String>> = mapOf(
            NWS_HOST to mapOf("User-Agent" to NWS_USER_AGENT, "Accept" to "application/geo+json"),
        )

        internal fun headersFor(url: HttpUrl): Map<String, String> = HEADERS[url.host] ?: DEFAULT_HEADERS

        /**
         * The four feed hosts, plus exactly the game-day pick files on the picks thread's branch ([Picks.isPickUrl]):
         * raw.githubusercontent.com serves every public repo, so only those two paths are allowed, never the host.
         */
        internal fun isAllowed(url: HttpUrl): Boolean =
            url.isHttps && url.port == 443 && (url.host in HOSTS || Picks.isPickUrl(url))
    }
}
