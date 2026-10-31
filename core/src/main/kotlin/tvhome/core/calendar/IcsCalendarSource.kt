package tvhome.core.calendar

import tvhome.core.secrets.SecretKey
import tvhome.core.secrets.SecretStore
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Seam to T2's IcsParser: takes the ICS text and the window, returns the expanded instances, throws on malformed
 * input. T3 wraps any throw as SourceError.PARSE and drops the exception.
 */
fun interface IcsParse {
    fun parse(ics: String, window: InstantRange): List<EventInstance>
}

/**
 * Fetches the calendar URL from the [SecretStore], downloads and parses it. Blocking inside (OkHttp, parser):
 * the caller dispatches to Dispatchers.IO, because :core has no coroutines dependency.
 */
class IcsCalendarSource(
    private val secrets: SecretStore,
    private val fetcher: BodyFetcher,
    private val parser: IcsParse,
    private val clock: () -> Instant = Instant::now,
) : CalendarSource {

    override suspend fun fetch(window: InstantRange): FetchResult {
        return try {
            val url = secrets.get(SecretKey.CALENDAR_URL)
            if (url.isNullOrBlank()) return FetchResult.Failed(SourceError.NOT_CONFIGURED)
            when (val r = fetcher.fetch(url)) {
                is IcsFetchResult.Error -> FetchResult.Failed(r.error)
                is IcsFetchResult.Body -> try {
                    FetchResult.Ok(parser.parse(r.text, window), clock())
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    FetchResult.Failed(SourceError.PARSE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FetchResult.Failed(SourceError.NETWORK)
        }
    }
}
