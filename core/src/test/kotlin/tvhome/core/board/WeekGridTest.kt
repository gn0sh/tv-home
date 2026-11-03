package tvhome.core.board

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class WeekGridTest {
    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026, 11, 3)
    private val now = today.atTime(14, 30).atZone(zone).toInstant()

    private fun at(d: Int, h: Int, m: Int = 0): Instant = today.plusDays(d.toLong()).atTime(h, m).atZone(zone).toInstant()
    private fun timed(title: String, d: Int, h: Int, m: Int, endH: Int, endM: Int = 0, endDay: Int = d) =
        AgendaItem(title, TimeLabel.Starts(LocalTime.of(h, m)), at(d, h, m), at(endDay, endH, endM))
    private fun week(vararg perDay: List<AgendaItem>) = perDay.mapIndexed { i, items -> AgendaDay(today.plusDays(i.toLong()), items) }

    @Test fun defaultRangeAndSevenColumnsTodayFirst() {
        val g = weekGrid(week(*Array(7) { emptyList() }), true, now, zone, false, false)
        assertEquals(8, g.startHour); assertEquals(21, g.endHour)
        assertEquals(7, g.columns.size)
        assertTrue(g.columns[0].isToday); assertFalse(g.columns[1].isToday)
        assertEquals("Tue 3 Nov", g.columns[0].heading)
        assertEquals(14 * 60 + 30, g.nowMin)
    }

    @Test fun rangeExtendsToWholeHoursForEarlyAndLateEvents() {
        val g = weekGrid(week(listOf(timed("Gym", 0, 6, 15, 7)), emptyList(), listOf(timed("Late", 2, 21, 0, 22, 30))), true, now, zone, false, false)
        assertEquals(6, g.startHour); assertEquals(23, g.endHour)
        val midnight = weekGrid(week(listOf(timed("Party", 0, 22, 0, 1, 0, endDay = 1))), true, now, zone, false, false)
        assertEquals(24, midnight.endHour)
        assertEquals(8, midnight.startHour, "the part continuing into the next day does not pull the range to midnight")
        val cont = midnight.columns[1]
        // Week only covers today here: tomorrow is not known.
        assertFalse(cont.known)
    }

    @Test fun continuingEventIsClippedToTheTopAndLabelled() {
        val shift = timed("Shift", 0, 22, 0, 9, 0, endDay = 1)
        val g = weekGrid(week(listOf(shift), listOf(shift.copy(label = TimeLabel.Continues))), true, now, zone, false, false)
        val b = g.columns[1].blocks.single()
        assertEquals("Continues", b.time)
        assertEquals(g.startHour * 60, b.startMin)
        assertEquals(9 * 60, b.endMin)
    }

    /** grid-continues-offrange: a continuing part that ends before the visible hours is not drawn; the list keeps it. */
    @Test fun continuingPartBeforeTheRangeIsListedNotDrawn() {
        val party = timed("Party", 0, 22, 0, 1, 0, endDay = 1)
        val g = weekGrid(week(listOf(party), listOf(party.copy(label = TimeLabel.Continues))), true, now, zone, false, false)
        assertTrue(g.columns[1].blocks.isEmpty())
        assertEquals(listOf("Continues"), g.columns[1].rows.map { it.time })
    }

    @Test fun overlapsGoSideBySide() {
        val items = listOf(timed("A", 0, 9, 0, 10), timed("B", 0, 9, 30, 11), timed("C", 0, 10, 0, 10, 30), timed("D", 0, 12, 0, 13))
        val b = weekGrid(week(items), true, now, zone, false, false).columns[0].blocks
        assertEquals(listOf(0, 1, 0, 0), b.map { it.lane })
        assertEquals(listOf(2, 2, 2, 1), b.map { it.lanes })
        assertEquals("9:00", b[0].time)
        assertEquals("9:00, A", b[0].description)
    }

    @Test fun packLanesTable() {
        assertEquals(emptyList<Pair<Int, Int>>(), packLanes(emptyList()))
        assertEquals(listOf(0 to 1, 0 to 1), packLanes(listOf(0 to 60, 60 to 120)), "touching is not overlapping")
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3), packLanes(listOf(0 to 60, 0 to 60, 0 to 60)))
        // Long first in its own lane; the short ones reuse lane 1.
        assertEquals(listOf(0 to 2, 1 to 2, 1 to 2), packLanes(listOf(0 to 180, 0 to 60, 60 to 120)))
    }

    @Test fun shortAndOpenEndedEventsKeepAMinimumHeight() {
        val noEnd = AgendaItem("Call", TimeLabel.Starts(LocalTime.of(9, 0)), at(0, 9), null)
        val blip = timed("Blip", 0, 10, 0, 10, 5)
        val b = weekGrid(week(listOf(noEnd, blip)), true, now, zone, false, false).columns[0].blocks
        assertEquals(30, b[0].endMin - b[0].startMin)
        assertEquals(WeekGrid.MIN_BLOCK_MIN, b[1].endMin - b[1].startMin)
    }

    @Test fun allDayAndListAndQuiet() {
        val items = listOf(AgendaItem("Holiday", TimeLabel.AllDay), timed("Dentist", 0, 9, 0, 10))
        val g = weekGrid(week(items), true, now, zone, false, quiet = false)
        val c = g.columns[0]
        assertEquals(listOf("Holiday"), c.allDay)
        assertEquals(1, c.blocks.size)
        assertEquals(listOf("All day", "9:00 AM – 10:00 AM"), c.rows.map { it.time })
        assertEquals("Tuesday 3 November, 2 events", c.description)
        val q = weekGrid(week(items), true, now, zone, false, quiet = true).columns[0]
        assertEquals(listOf("Busy"), q.allDay)
        assertTrue((q.blocks.map { it.title } + q.rows.map { it.title }).all { it == "Busy" })
        assertFalse(q.toString().contains("Dentist"))
    }

    @Test fun busyDayKeepsEveryEventInTheList() {
        val items = (0 until 25).map { val m = 8 * 60 + it * 30; timed("E$it", 0, m / 60, m % 60, (m + 30) / 60, (m + 30) % 60) }
        val c = weekGrid(week(items), true, now, zone, false, false).columns[0]
        assertEquals(25, c.blocks.size)
        assertEquals(25, c.rows.size)
        assertEquals(21, weekGrid(week(items), true, now, zone, false, false).endHour)
    }

    @Test fun unknownDaysAreMarkedNotEmpty() {
        val g = weekGrid(week(emptyList(), emptyList()), true, now, zone, false, false)
        assertEquals(listOf(true, true, false, false, false, false, false), g.columns.map { it.known })
        assertTrue(g.columns[3].description.endsWith("not loaded"))
        assertTrue(weekGrid(week(emptyList()), false, now, zone, false, false).columns.none { it.known })
    }

    @Test fun nowLineOnlyInsideTheRange() {
        val early = today.atTime(6, 0).atZone(zone).toInstant()
        assertNull(weekGrid(week(emptyList()), true, early, zone, false, false).nowMin)
    }

    @Test fun blockTextFitsTheBlock() {
        assertEquals(0, BlockText.lines(20f, 150f))
        assertEquals(1, BlockText.lines(30f, 150f))
        assertEquals(2, BlockText.lines(56f, 150f))
        assertEquals(0, BlockText.lines(200f, 30f), "too narrow (four lanes)")
        assertEquals(2, BlockText.lines(56f, 150f, fontScale = 1f))
        assertEquals(1, BlockText.lines(56f, 150f, fontScale = 1.3f), "bigger text, fewer lines")
        assertEquals(0, BlockText.lines(200f, 50f, fontScale = 1.3f), "bigger text needs a wider lane")
    }

    /** board-axis-label-overlap: labels never closer than their own height. */
    @Test fun labelStepKeepsLabelsApart() {
        for (hourDp in listOf(40f, 28f, 27.9f, 14f, 13.9f, 9.3f, 5f, 2f)) for (fs in listOf(1f, 1.3f)) {
            val step = BlockText.labelStep(hourDp, fs)
            assertTrue(step >= 1)
            assertTrue(step * hourDp >= BlockText.LABEL_DP * fs - 1e-3, "$hourDp $fs $step")
            if (step > 1) assertTrue((step - 1) * hourDp < BlockText.LABEL_DP * fs, "smallest such step")
        }
        assertEquals(1, BlockText.labelStep(30f)); assertEquals(2, BlockText.labelStep(20f)); assertEquals(3, BlockText.labelStep(10f))
    }

    /** grid-dst-minutes: on both DST days a 9:00-10:00 event sits at 9:00 on the wall-clock axis. */
    @Test fun dstDaysUseWallClockMinutes() {
        for (day in listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2027, 3, 14))) {
            val n = day.atTime(8, 0).atZone(zone).toInstant()
            val item = AgendaItem("A", TimeLabel.Starts(LocalTime.of(9, 0)), day.atTime(9, 0).atZone(zone).toInstant(), day.atTime(10, 0).atZone(zone).toInstant())
            val late = AgendaItem("B", TimeLabel.Starts(LocalTime.of(22, 0)), day.atTime(22, 0).atZone(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())
            val g = weekGrid(listOf(AgendaDay(day, listOf(item, late))), true, n, zone, false, false)
            val (b, l) = g.columns[0].blocks
            assertEquals(9 * 60, b.startMin, "$day"); assertEquals(10 * 60, b.endMin, "$day")
            assertEquals("9:00", b.time)
            assertEquals(22 * 60, l.startMin, "$day"); assertEquals(24 * 60, l.endMin, "$day")
            assertEquals(24, g.endHour)
            assertEquals(8 * 60, g.nowMin, "$day")
        }
    }

    @Test fun twentyFourHourTimes() {
        val b = weekGrid(week(listOf(timed("A", 0, 13, 5, 14))), true, now, zone, true, false).columns[0].blocks.single()
        assertEquals("13:05", b.time)
    }
}
