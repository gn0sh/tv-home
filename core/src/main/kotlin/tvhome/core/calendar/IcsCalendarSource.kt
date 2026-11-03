package tvhome.core.calendar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import tvhome.core.secrets.ProvisionResult
import tvhome.core.secrets.ProvisionValidator
import tvhome.core.secrets.SecretKey
import tvhome.core.secrets.SecretStore
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the calendar URL from the [SecretStore], re-validates it, downloads it and parses it.
 *
 * Threading contract (T5 relies on it): [fetch] is main-safe. The secret read runs on [io], the download on the
 * fetcher's own IO dispatch, and the parse on [cpu] inside `runInterruptible`. Cancelling the calling coroutine
 * cancels the HTTP call and interrupts the parse; [fetch] then throws [CancellationException]. It never throws
 * anything else: every failure is a [FetchResult.Failed] carrying only an enum. Cancellation is never a result:
 * every `catch (Throwable)` calls `ensureActive()` before it maps anything (N7).
 *
 * Outcome mapping: Complete is Ok; Exhausted is `PARSE_LIMIT`; Rejected is `PARSE`. A stored URL that fails
 * [ProvisionValidator.validateCalendarUrl] is `NOT_CONFIGURED` and no request is made (H11).
 */
class IcsCalendarSource internal constructor(
    private val secrets: SecretStore,
    private val fetcher: BodyFetcher,
    private val parser: IcsParse,
    private val io: CoroutineDispatcher,
    private val cpu: CoroutineDispatcher,
    private val clock: () -> Instant,
    private val urlValid: (String) -> Boolean,
) : CalendarSource {

    constructor(
        secrets: SecretStore,
        fetcher: BodyFetcher,
        parser: IcsParse,
        io: CoroutineDispatcher = Dispatchers.IO,
        cpu: CoroutineDispatcher = Dispatchers.Default,
        clock: () -> Instant = Instant::now,
    ) : this(secrets, fetcher, parser, io, cpu, clock, ::isProvisionedUrl)

    override suspend fun fetch(window: InstantRange): FetchResult {
        return try {
            val url = withContext(io) { secrets.get(SecretKey.CALENDAR_URL) }
            if (url.isNullOrBlank() || !urlValid(url)) return FetchResult.Failed(SourceError.NOT_CONFIGURED)
            when (val r = fetcher.fetch(url)) {
                is IcsFetchResult.Error -> FetchResult.Failed(r.error)
                is IcsFetchResult.Body -> try {
                    when (val o = runInterruptible(cpu) { parser.parse(r.bytes, window) }) {
                        is ParseOutcome.Complete -> FetchResult.Ok(o.events, clock(), o.stats.truncated)
                        is ParseOutcome.Exhausted -> FetchResult.Failed(SourceError.PARSE_LIMIT)
                        is ParseOutcome.Rejected -> FetchResult.Failed(SourceError.PARSE)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive() // a cancelled parse is not a PARSE failure
                    FetchResult.Failed(SourceError.PARSE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive() // a cancelled download is not a NETWORK failure
            FetchResult.Failed(SourceError.NETWORK)
        }
    }

    private companion object {
        fun isProvisionedUrl(url: String): Boolean = ProvisionValidator.validateCalendarUrl(url) is ProvisionResult.Valid
    }
}
