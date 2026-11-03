package tvhome.core.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvhome.core.board.BoardInputs
import tvhome.core.board.board
import tvhome.core.calendar.FetchResult
import tvhome.core.calendar.SourceError
import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.PanelSwitches
import java.time.Instant
import java.time.ZoneId

/**
 * board-tour-week: the emulator tour's planted agenda-cache.json (made by scripts/ci/screenshot-tour.sh's plant_agenda
 * with the device clock at Mon 2 Nov 19:05 CST; the resource is that output, generated with the week table intact) shows
 * every day of the week offline. The tour's Board showed only today because the tour's loop lost its table to adb's stdin
 * (only the first week row was planted), not because of the app.
 */
class TourCacheTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = Instant.parse("2026-11-03T01:05:30Z")

    @Test fun plantedWeekShowsOnEveryDay() {
        val cache = CachedAgenda.decode(javaClass.getResource("/tour-agenda-cache.json")!!.readText())!!
        assertEquals(60, cache.events.size)
        val state = reduce(true, cache, FetchResult.Failed(SourceError.OFFLINE), now, zone)
        val b = board(BoardInputs(now, zone, false, state, FeedsSnapshot(), PanelSwitches(), false, false))
        assertTrue(b.grid.columns.all { it.known })
        assertEquals(listOf(6, 6, 16, 5, 6, 4, 4), b.grid.columns.map { it.eventCount })
        assertEquals("Wednesday 4 November, 16 events", b.grid.columns[2].description)
    }
}
