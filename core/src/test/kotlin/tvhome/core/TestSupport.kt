package tvhome.core

import kotlinx.coroutines.runBlocking
import tvhome.core.calendar.BodyFetcher
import tvhome.core.calendar.IcsFetchResult

/**
 * The ONE fake secret used by every test fixture, so CI's grep for `private-` / `sk-ant-` patterns can exclude it by
 * exact match. Built at runtime so the test sources hold no literal of the pattern.
 */
object FakeSecrets {
    val CALENDAR_TOKEN: String = "private-" + "f".repeat(32)
    const val CALENDAR_PATH_USER: String = "fake%40example.test"
    val CALENDAR_URL: String = "https://calendar.google.com/calendar/ical/$CALENDAR_PATH_USER/$CALENDAR_TOKEN/basic.ics"
    val API_KEY: String = "sk-ant-" + "f".repeat(40)
}

/** Runs a suspend block to completion on the calling thread. */
fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

/** Blocking call of a [BodyFetcher], for tests. */
fun BodyFetcher.fetchNow(url: String): IcsFetchResult = runSuspend { fetch(url) }
