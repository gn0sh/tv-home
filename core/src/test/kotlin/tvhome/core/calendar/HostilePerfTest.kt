package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tvhome.core.agenda.agendaFocus
import tvhome.core.agenda.agendaWindow
import java.time.Instant
import java.time.ZoneId

/**
 * P14 perf (security r2 #4, `ics-parse-deadline-excludes-biweekly`): the 9 MB Google-shaped feed parses in under
 * 1 s on the FIRST call in a fresh test JVM (`:core:hostileTest` forks one JVM per test class, so this class has
 * its own, cold). About 350 ms is measured; the 1 s limit is a 2x+ margin for a loaded machine (`p14-perf-flaky`).
 * The whole pipeline is ours, so this one number covers the old uninterruptible text phase too.
 */
@Tag("hostile")
@Tag("ics-parse-deadline-excludes-biweekly")
class HostilePerfTest {
    @Test fun p14FirstCallUnder1s() {
        val ny = ZoneId.of("America/New_York")
        val window = agendaWindow(Instant.parse("2026-03-10T14:00:00Z"), ny)
        val (body, expected) = HostileFeeds.google(vevents = 15_000, series = 300, descLen = 230)
        val t0 = System.nanoTime()
        val r = IcsParser.parse(body, window, ny, agendaFocus(window, ny))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("p14 first call: ${body.size} B, $ms ms, $r")
        assertTrue(r is ParseOutcome.Complete && r.events.size == expected.events.size, r.toString())
        assertTrue(ms < 1_000, "first call took $ms ms")
    }
}
