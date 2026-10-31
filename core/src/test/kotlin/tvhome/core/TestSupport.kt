package tvhome.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch

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

/** Runs a suspend block to completion without kotlinx.coroutines (not a :core dependency). */
fun <T> runSuspend(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it; latch.countDown() })
    latch.await()
    return result!!.getOrThrow()
}
