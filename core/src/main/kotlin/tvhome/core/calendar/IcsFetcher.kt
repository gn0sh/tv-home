package tvhome.core.calendar

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Result of one HTTP fetch. Carries a body or an enum: never exception text, never the URL. */
sealed interface IcsFetchResult {
    /** The raw response bytes: one copy, never turned into a String here (the reader decodes kept lines only). */
    class Body(val bytes: ByteArray) : IcsFetchResult {
        override fun toString(): String = "Body(${bytes.size} bytes)"
    }

    data class Error(val error: SourceError) : IcsFetchResult
}

/**
 * Downloads one URL. Contract (relied on by the app, T5): main-safe (does its own dispatching), cancellable
 * (cancelling the caller cancels the HTTP call), never throws except [CancellationException].
 */
fun interface BodyFetcher {
    suspend fun fetch(url: String): IcsFetchResult
}

data class FetchTimeouts(val connectMs: Long = 5_000, val readMs: Long = 10_000, val callMs: Long = 15_000)

/**
 * HTTPS fetch of an ICS feed. Main-safe: the blocking OkHttp call runs on Dispatchers.IO, and cancelling the
 * calling coroutine cancels the call. Policy: https only (checked before any I/O), connect 5 s / read 10 s /
 * call 15 s, no redirect that changes scheme (https to http comes back as HTTP_STATUS), body capped at [maxBytes]
 * after decompression, returned as one `ByteArray`. Host pin (H11): the request URL and the final response URL must
 * pass [allowed] (the app: https on `calendar.google.com` or `*.google.com`); a final response elsewhere is
 * [SourceError.HTTP_STATUS]. Every failure becomes a [SourceError]; nothing from an exception escapes, and a cancel
 * is never a result: it throws [CancellationException] (`ensureActive()` before every mapping).
 *
 * The public constructor uses the platform trust store and cannot be loosened. The internal one takes [customize]
 * for tests in this module only (trust store, DNS): it runs first, so it cannot change the timeouts or redirects.
 */
class IcsFetcher internal constructor(
    timeouts: FetchTimeouts,
    private val maxBytes: Long,
    private val allowed: (HttpUrl) -> Boolean,
    customize: OkHttpClient.Builder.() -> Unit,
) : BodyFetcher {

    constructor(timeouts: FetchTimeouts = FetchTimeouts(), maxBytes: Long = MAX_BYTES) :
        this(timeouts, maxBytes, ::isPinnedUrl, {})

    private val client: OkHttpClient = OkHttpClient.Builder().apply(customize)
        .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeouts.callMs, TimeUnit.MILLISECONDS)
        .followSslRedirects(false)
        .followRedirects(true)
        .build()

    override suspend fun fetch(url: String): IcsFetchResult {
        val parsed = when (val c = checkUrl(url)) {
            is UrlCheck.Rejected -> return IcsFetchResult.Error(c.error)
            is UrlCheck.Ok -> c.url
        }
        // Host pin before any I/O: the secret travels in the request line, so never send it elsewhere.
        if (!allowed(parsed)) return IcsFetchResult.Error(SourceError.NOT_CONFIGURED)
        val result = try {
            val request = Request.Builder().url(parsed).header("Accept", "text/calendar, text/plain;q=0.5").get().build()
            val call = client.newCall(request)
            coroutineScope {
                // Cancelling this scope cancels the call, which makes the blocking execute() below throw at once.
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel() // idempotent; a no-op once the response has been read
                    }
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
            // A cancelled call fails with an IOException ("Canceled"); that is the caller's cancel, not a network
            // error (cancel-returns-network-error). Check before mapping anything.
            currentCoroutineContext().ensureActive()
            // Deliberately Throwable and deliberately no text: OkHttp messages can contain the URL or header values.
            IcsFetchResult.Error(if (isDnsFailure(t)) SourceError.OFFLINE else SourceError.NETWORK)
        }
        currentCoroutineContext().ensureActive() // a result that raced a cancel is dropped too
        return result
    }

    private fun execute(call: Call): IcsFetchResult = call.execute().use { response ->
        if (!response.isSuccessful) return IcsFetchResult.Error(SourceError.HTTP_STATUS)
        if (!allowed(response.request.url)) return IcsFetchResult.Error(SourceError.HTTP_STATUS) // H11: final host
        val body = response.body ?: return IcsFetchResult.Error(SourceError.HTTP_STATUS)
        val declared = body.contentLength()
        if (declared > maxBytes) return IcsFetchResult.Error(SourceError.TOO_LARGE)
        val source = body.source()
        if (declared >= 0) {
            // Known length: one exact allocation, filled straight from the socket (single copy, H10).
            val bytes = ByteArray(declared.toInt())
            source.readFully(bytes)
            return IcsFetchResult.Body(bytes)
        }
        // Unknown length (chunked, or gzip that OkHttp inflates): count while reading, cap at maxBytes.
        val buffer = okio.Buffer()
        while (true) {
            val n = source.read(buffer, 8192)
            if (n == -1L) break
            if (buffer.size > maxBytes) return IcsFetchResult.Error(SourceError.TOO_LARGE)
        }
        IcsFetchResult.Body(buffer.readByteArray())
    }

    companion object {
        const val MAX_BYTES: Long = 10L * 1024 * 1024

        /** The pin: https, and host `calendar.google.com` or any `*.google.com`. */
        internal fun isPinnedUrl(url: HttpUrl): Boolean =
            url.isHttps && (url.host == "calendar.google.com" || url.host.endsWith(".google.com"))

        private sealed interface UrlCheck {
            class Ok(val url: okhttp3.HttpUrl) : UrlCheck
            class Rejected(val error: SourceError) : UrlCheck
        }

        private fun isDnsFailure(t: Throwable): Boolean {
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth++ < 8) {
                if (c is UnknownHostException) return true
                c = c.cause
            }
            return false
        }

        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

        private fun checkUrl(raw: String): UrlCheck {
            val s = raw.trim()
            if (s.isEmpty()) return UrlCheck.Rejected(SourceError.NOT_CONFIGURED)
            val scheme = SCHEME.find(s)?.groupValues?.get(1)?.lowercase()
                ?: return UrlCheck.Rejected(SourceError.NOT_CONFIGURED)
            if (scheme != "https") return UrlCheck.Rejected(SourceError.INSECURE_URL)
            val url = s.toHttpUrlOrNull() ?: return UrlCheck.Rejected(SourceError.NOT_CONFIGURED)
            return UrlCheck.Ok(url)
        }
    }
}
