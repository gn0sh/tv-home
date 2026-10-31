package tvhome.core.calendar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import tvhome.core.agenda.agendaFocus
import tvhome.core.secrets.SecretKey
import tvhome.core.secrets.SecretStore
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Seam to [IcsParser]: takes the ICS text and the window, returns the expanded instances, throws on malformed
 * input. Blocking and CPU-bound; [IcsCalendarSource] runs it on its CPU dispatcher and interrupts it on cancel.
 */
fun interface IcsParse {
    fun parse(ics: String, window: InstantRange): List<EventInstance>

    companion object {
        /**
         * The real parser. [zone] is read per call so a device time-zone change is picked up. When the 500-instance
         * cap cuts, today and tomorrow (see [agendaFocus]) are kept first.
         */
        fun ics(zone: () -> ZoneId = ZoneId::systemDefault): IcsParse = IcsParse { text, window ->
            val z = zone()
            IcsParser.parse(text, window, z, focus = agendaFocus(window, z))
        }
    }
}

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
                    FetchResult.Ok(runInterruptible(cpu) { parser.parse(r.text, window) }, clock())
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
