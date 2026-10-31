package tvhome.core.calendar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import tvhome.core.secrets.SecretKey
import tvhome.core.secrets.SecretStore
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the calendar URL from the [SecretStore], downloads it and parses it.
 *
 * Threading contract (T5 relies on it): [fetch] is main-safe. The secret read runs on [io], the download on the
 * fetcher's own IO dispatch, and the parse on [cpu] inside `runInterruptible`. Cancelling the calling coroutine
 * cancels the HTTP call and interrupts the parse; [fetch] then throws [CancellationException]. It never throws
 * anything else: every failure is a [FetchResult.Failed] carrying only an enum.
 */
class IcsCalendarSource(
    private val secrets: SecretStore,
    private val fetcher: BodyFetcher,
    private val parser: IcsParse,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Instant = Instant::now,
) : CalendarSource {

    override suspend fun fetch(window: InstantRange): FetchResult {
        return try {
            val url = withContext(io) { secrets.get(SecretKey.CALENDAR_URL) }
            if (url.isNullOrBlank()) return FetchResult.Failed(SourceError.NOT_CONFIGURED)
            when (val r = fetcher.fetch(url)) {
                is IcsFetchResult.Error -> FetchResult.Failed(r.error)
                is IcsFetchResult.Body -> try {
                    // Minimal T2b.1 adapter to the ByteArray/ParseOutcome contract; T2b.2 owns this file (Body(bytes)).
                    when (val o = runInterruptible(cpu) { parser.parse(r.text.toByteArray(Charsets.UTF_8), window) }) {
                        is ParseOutcome.Complete -> FetchResult.Ok(o.events, clock())
                        is ParseOutcome.Exhausted -> FetchResult.Failed(SourceError.PARSE_LIMIT)
                        is ParseOutcome.Rejected -> FetchResult.Failed(SourceError.PARSE)
                    }
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
