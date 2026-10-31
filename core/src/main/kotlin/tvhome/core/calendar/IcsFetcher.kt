package tvhome.core.calendar

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Result of one HTTP fetch. Carries a body or an enum: never exception text, never the URL. */
sealed interface IcsFetchResult {
    class Body(val text: String) : IcsFetchResult {
        override fun toString(): String = "Body(${text.length} chars)"
    }

    data class Error(val error: SourceError) : IcsFetchResult
}

fun interface BodyFetcher {
    fun fetch(url: String): IcsFetchResult
}

data class FetchTimeouts(val connectMs: Long = 5_000, val readMs: Long = 10_000, val callMs: Long = 15_000)

/**
 * Blocking HTTPS fetch of an ICS feed. Callers must run it off the main thread (the app wraps it in
 * Dispatchers.IO). Policy: https only (checked before any I/O), connect 5 s / read 10 s / call 15 s,
 * no redirect that changes scheme (https to http comes back as HTTP_STATUS), body capped at [maxBytes] after
 * decompression. Every failure becomes a [SourceError]; nothing from an exception escapes.
 *
 * [customize] is for tests (trust store, interceptors). It runs first and cannot loosen the policy above.
 */
class IcsFetcher(
    timeouts: FetchTimeouts = FetchTimeouts(),
    private val maxBytes: Long = MAX_BYTES,
    customize: OkHttpClient.Builder.() -> Unit = {},
) : BodyFetcher {

    private val client: OkHttpClient = OkHttpClient.Builder().apply(customize)
        .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeouts.callMs, TimeUnit.MILLISECONDS)
        .followSslRedirects(false)
        .followRedirects(true)
        .build()

    override fun fetch(url: String): IcsFetchResult {
        val parsed = when (val c = checkUrl(url)) {
            is UrlCheck.Rejected -> return IcsFetchResult.Error(c.error)
            is UrlCheck.Ok -> c.url
        }
        return try {
            val request = Request.Builder().url(parsed).header("Accept", "text/calendar, text/plain;q=0.5").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return IcsFetchResult.Error(SourceError.HTTP_STATUS)
                val body = response.body ?: return IcsFetchResult.Error(SourceError.HTTP_STATUS)
                val declared = body.contentLength()
                if (declared > maxBytes) return IcsFetchResult.Error(SourceError.TOO_LARGE)
                val source = body.source()
                val buffer = okio.Buffer()
                while (true) {
                    val n = source.read(buffer, 8192)
                    if (n == -1L) break
                    if (buffer.size > maxBytes) return IcsFetchResult.Error(SourceError.TOO_LARGE)
                }
                IcsFetchResult.Body(buffer.readUtf8())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Deliberately Throwable and deliberately no text: OkHttp messages can contain the URL or header values.
            IcsFetchResult.Error(if (isDnsFailure(t)) SourceError.OFFLINE else SourceError.NETWORK)
        }
    }

    companion object {
        const val MAX_BYTES: Long = 10L * 1024 * 1024

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
