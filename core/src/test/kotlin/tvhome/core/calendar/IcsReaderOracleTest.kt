package tvhome.core.calendar

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.property.ICalProperty
import biweekly.util.ICalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * I3, differential oracle (ADR 0004): biweekly is test-only now and serves as an independent reader. For every
 * non-hostile fixture and 500 seeded Google-shaped feeds, our reader's records (uid, summary, dtstart, dtend or
 * duration, RRULE parts, EXDATE, RDATE, RECURRENCE-ID, STATUS) equal biweekly's parse of the same text. TZIDs are
 * compared after resolution (biweekly maps "/mozilla.org/.../X" to a global id; we resolve the suffix).
 */
@Tag("ics-parse-cpu-memory-dos")
class IcsReaderOracleTest {
    private val fallback = ZoneId.of("Pacific/Kiritimati") // an unlikely zone, so a missed TZID shows up

    private fun ours(body: ByteArray): List<VEventRecord> {
        val out = ArrayList<VEventRecord>()
        val budget = Budget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE) { 0L }
        assertNull(IcsReader(body, budget, Int.MAX_VALUE) { out += it }.read())
        return out
    }

    private fun zoneOf(tzid: String?): String = ZoneResolver.resolve(tzid, fallback).id

    private fun fmt(d: DateValue?): String? = d?.let { "${it.local}|date=${it.isDate}|utc=${it.utc}|${if (it.isDate || it.utc) "-" else zoneOf(it.tzid)}" }

    private fun bwTzid(cal: ICalendar, prop: ICalProperty): String? =
        cal.timezoneInfo.getTimezone(prop)?.let { it.globalId ?: it.component?.timezoneId?.value } ?: prop.parameters.timezoneId

    private fun fmtBw(cal: ICalendar, prop: ICalProperty?, d: ICalDate?): String? {
        if (prop == null || d == null) return null
        val c = d.rawComponents
        val local = java.time.LocalDateTime.of(c.year, c.month, c.date, if (d.hasTime()) c.hour else 0, if (d.hasTime()) c.minute else 0, if (d.hasTime()) c.second else 0)
        val date = !d.hasTime()
        val utc = d.hasTime() && c.isUtc
        return "$local|date=$date|utc=$utc|${if (date || utc) "-" else zoneOf(bwTzid(cal, prop))}"
    }

    private fun compare(body: ByteArray, label: String): Int {
        val mine = ours(body)
        val cal = Biweekly.parse(body.toString(Charsets.UTF_8)).first()
        val theirs = cal.events
        assertEquals(theirs.size, mine.size, "$label: VEVENT count")
        for ((i, pair) in mine.zip(theirs).withIndex()) {
            val (r, e) = pair
            val at = "$label VEVENT $i"
            assertTrue(!r.bad, "$at: ours marked bad")
            assertEquals(e.uid?.value?.let(IcsText::normalizeUid), r.uid, "$at uid")
            assertEquals(e.summary?.value?.let(IcsText::normalizeTitle), r.summary, "$at summary")
            assertEquals(fmtBw(cal, e.dateStart, e.dateStart?.value), fmt(r.dtStart), "$at dtstart")
            assertEquals(fmtBw(cal, e.dateEnd, e.dateEnd?.value), fmt(r.dtEnd), "$at dtend")
            assertEquals(e.duration?.value?.toMillis(), r.duration?.toMillis(), "$at duration")
            assertEquals(fmtBw(cal, e.recurrenceId, e.recurrenceId?.value), fmt(r.recurrenceId), "$at recurrence-id")
            assertEquals(e.status?.value?.uppercase(), r.status, "$at status")
            val bwEx = e.exceptionDates.flatMap { ex -> ex.values.map { fmtBw(cal, ex, it) } }
            assertEquals(bwEx, r.exdates.map { fmt(it) }, "$at exdate")
            val bwRd = e.recurrenceDates.flatMap { rd ->
                rd.dates.map { fmtBw(cal, rd, it) } + rd.periods.map { p ->
                    "${fmtBw(cal, rd, p.startDate)}/${fmtBw(cal, rd, p.endDate)}/${p.duration?.toMillis()}"
                }
            }
            val ourRd = r.rdates.map {
                when (it) {
                    is DateValue -> fmt(it)
                    is PeriodValue -> "${fmt(it.start)}/${fmt(it.end)}/${it.duration?.toMillis()}"
                    else -> "?"
                }
            }
            assertEquals(bwRd, ourRd, "$at rdate")
            val rr = e.recurrenceRule?.value
            assertEquals(rr != null, r.rruleSeen, "$at rrule present")
            if (rr != null) {
                val spec = r.rrule
                val f = rr.frequency?.name
                if (spec is RuleSpec.Spec) {
                    assertEquals(f, spec.freq.name, "$at freq")
                    assertEquals(rr.interval ?: 1, spec.interval, "$at interval")
                    assertEquals(rr.count?.toLong(), spec.count, "$at count")
                    assertEquals(fmtBw(cal, e.recurrenceRule, rr.until)?.substringBeforeLast('|'), fmt(spec.until)?.substringBeforeLast('|'), "$at until")
                    assertEquals(rr.byMonth.distinct().sorted(), spec.byMonth, "$at bymonth")
                    assertEquals(rr.byMonthDay.distinct().sorted(), spec.byMonthDay, "$at bymonthday")
                    assertEquals(rr.bySetPos.distinct().sorted(), spec.bySetPos, "$at bysetpos")
                    assertEquals(rr.byHour.distinct().sorted(), spec.byHour, "$at byhour")
                    assertEquals(rr.byMinute.distinct().sorted(), spec.byMinute, "$at byminute")
                    assertEquals(rr.bySecond.distinct().sorted(), spec.bySecond, "$at bysecond")
                    assertEquals(
                        rr.byDay.map { "${it.num ?: 0}${it.day.name.take(2)}" }.toSet(),
                        spec.byDay.map { "${it.n}${it.day.name.take(2)}" }.toSet(),
                        "$at byday",
                    )
                    assertEquals(rr.workweekStarts?.name?.take(2) ?: "MO", spec.wkst.name.take(2), "$at wkst")
                } else {
                    // unsupported on our side: biweekly must show why (a frequency or part we never expand)
                    assertTrue(
                        f !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY") || rr.byYearDay.isNotEmpty() || rr.byWeekNo.isNotEmpty(),
                        "$at: unsupported but biweekly reads $f",
                    )
                }
            }
        }
        return mine.size
    }

    @Test fun everyFixtureMatchesBiweekly() {
        val names = listOf("across-midnight", "allday-multiday", "allday-single", "cancelled-instance", "dst-change-week",
            "empty-calendar", "empty-day", "exdate", "floating", "moved-instance", "tzid-other-zone", "utc", "weekly-rrule")
        var n = 0
        for (name in names) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/ics/$name.ics")).readBytes()
            n += compare(bytes, name)
        }
        assertTrue(n >= 15, "only $n VEVENTs")
    }

    @Test fun fiveHundredSeededGoogleShapedFeedsMatchBiweekly() {
        val rnd = Random(500)
        var n = 0
        repeat(500) { n += compare(randomFeed(rnd), "feed $it") }
        assertTrue(n > 4_000, "only $n VEVENTs")
    }

    @Test fun theP14GeneratorMatchesBiweekly() {
        compare(HostileFeeds.google(vevents = 400, series = 60, descLen = 230, seed = 3).first, "google")
    }

    // ------------------------------------------------------------------ generator

    private val zones = listOf("America/New_York", "Europe/London", "Asia/Tokyo", "Eastern Standard Time",
        "\"/mozilla.org/20070129_1/Europe/Berlin\"", "Australia/Sydney")
    private val codes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    private fun randomFeed(rnd: Random): ByteArray {
        val b = HostileFeeds.Bytes()
        b.line("BEGIN:VCALENDAR").line("PRODID:-//Google Inc//Google Calendar 70.9054//EN").line("VERSION:2.0")
            .line("CALSCALE:GREGORIAN").line("METHOD:PUBLISH").line("X-WR-TIMEZONE:America/New_York")
        repeat(rnd.nextInt(5, 15)) { i ->
            b.line("BEGIN:VEVENT")
            val allDay = rnd.nextInt(5) == 0
            val tz = zones[rnd.nextInt(zones.size)]
            val kind = if (allDay) 0 else rnd.nextInt(1, 4) // 1 utc, 2 tzid, 3 floating
            fun value(d: java.time.LocalDateTime, k: Int = kind): String = when (k) {
                0 -> "%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth)
                1 -> "%04d%02d%02dT%02d%02d%02dZ".format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute, d.second)
                else -> "%04d%02d%02dT%02d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute, d.second)
            }
            fun params(k: Int = kind) = when (k) {
                0 -> ";VALUE=DATE"
                2 -> ";TZID=$tz"
                else -> ""
            }
            val start = LocalDate.of(2020, 1, 1).plusDays(rnd.nextLong(0, 2500)).atTime(rnd.nextInt(24), rnd.nextInt(60), rnd.nextInt(60))
            if (rnd.nextInt(10) != 0) b.folded("UID:" + listOf("abc$i@google.com", "x\\;y\\,z$i", "üñí$i-${rnd.nextInt()}", "  spaced$i  ")[rnd.nextInt(4)])
            b.line("DTSTAMP:20260301T120000Z")
            b.line("DTSTART${params()}:${value(start)}")
            when (rnd.nextInt(3)) {
                0 -> b.line("DTEND${params()}:${value(start.plusHours(rnd.nextLong(1, 30)))}")
                1 -> b.line("DURATION:" + listOf("PT1H", "PT30M", "P1D", "P2W", "-PT15M", "P1DT2H3M4S", "PT0S")[rnd.nextInt(7)])
            }
            if (rnd.nextInt(10) != 0) {
                b.folded("SUMMARY:" + listOf("Lunch", "Dr. Smith\\, room 3", "Line1\\nLine2", "Back\\\\slash", "Émile 😀 é",
                    "x".repeat(rnd.nextInt(60, 400)), "Semi\\; colon", "  padded  ")[rnd.nextInt(8)])
            }
            b.folded("DESCRIPTION:" + "Long description text, with commas. ".repeat(rnd.nextInt(0, 8)))
            if (rnd.nextInt(2) == 0) b.folded("RRULE:" + randomRule(rnd, start, kind))
            repeat(rnd.nextInt(0, 3)) {
                val vs = (0 until rnd.nextInt(1, 4)).joinToString(",") { value(start.plusDays(rnd.nextLong(1, 400))) }
                b.folded("EXDATE${params()}:$vs")
            }
            if (rnd.nextInt(4) == 0) {
                if (kind != 0 && rnd.nextInt(2) == 0) {
                    val p = value(start.plusDays(3)) + "/" + if (rnd.nextInt(2) == 0) "PT2H" else value(start.plusDays(3).plusHours(2))
                    b.line("RDATE;VALUE=PERIOD${params()}:$p")
                } else {
                    b.line("RDATE${params()}:" + (0 until rnd.nextInt(1, 3)).joinToString(",") { value(start.plusDays(rnd.nextLong(1, 99))) })
                }
            }
            if (rnd.nextInt(6) == 0) b.line("RECURRENCE-ID${params()}:${value(start.minusDays(7))}")
            if (rnd.nextInt(3) == 0) b.line("STATUS:" + listOf("CONFIRMED", "CANCELLED", "TENTATIVE", "cancelled")[rnd.nextInt(4)])
            b.line("X-GOOGLE-CONFERENCE:https://meet.example.test/abc").line("TRANSP:OPAQUE")
            if (rnd.nextInt(3) == 0) b.line("BEGIN:VALARM").line("ACTION:DISPLAY").line("TRIGGER:-P0DT0H10M0S").line("END:VALARM")
            b.line("END:VEVENT")
        }
        b.line("END:VCALENDAR")
        return b.bytes()
    }

    private fun randomRule(rnd: Random, start: java.time.LocalDateTime, kind: Int): String {
        if (rnd.nextInt(15) == 0) return listOf("FREQ=SECONDLY", "FREQ=HOURLY;INTERVAL=2", "FREQ=YEARLY;BYYEARDAY=1", "FREQ=YEARLY;BYWEEKNO=20")[rnd.nextInt(4)]
        val freq = listOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")[rnd.nextInt(4)]
        val parts = mutableListOf("FREQ=$freq")
        if (rnd.nextInt(3) == 0) parts += "INTERVAL=${rnd.nextInt(1, 5)}"
        if (rnd.nextInt(2) == 0) {
            val ord = freq == "MONTHLY" || freq == "YEARLY"
            parts += "BYDAY=" + codes.shuffled(rnd).take(rnd.nextInt(1, 4)).joinToString(",") {
                if (ord && rnd.nextInt(2) == 0) listOf("1", "2", "-1", "+3", "4")[rnd.nextInt(5)] + it else it
            }
        }
        if (freq != "WEEKLY" && rnd.nextInt(3) == 0) parts += "BYMONTHDAY=" + listOf("1", "15", "-1", "28,29", "31")[rnd.nextInt(5)]
        if (freq == "YEARLY" && rnd.nextInt(2) == 0) parts += "BYMONTH=" + listOf("1", "3,9", "12", "2,2")[rnd.nextInt(4)]
        if (rnd.nextInt(6) == 0) parts += "BYSETPOS=" + listOf("1", "-1", "2,-2")[rnd.nextInt(3)]
        if (freq != "YEARLY" && rnd.nextInt(8) == 0) parts += "BYHOUR=9,17"
        if (freq != "YEARLY" && rnd.nextInt(10) == 0) parts += "BYMINUTE=0,30"
        if (rnd.nextInt(6) == 0) parts += "WKST=" + codes[rnd.nextInt(7)]
        when (rnd.nextInt(3)) {
            0 -> parts += "COUNT=${rnd.nextInt(1, 500)}"
            1 -> parts += "UNTIL=" + if (kind == 0) "20271231" else if (kind == 3) "20271231T235959" else "20271231T235959Z"
        }
        parts.shuffle(rnd)
        return parts.joinToString(";")
    }

    @Test fun ruleParserDomainSetsAreDistinctAndSorted() {
        val s = IcsRules.parse("freq=monthly;bymonthday=5,5,-1,5;byday=1MO,+1MO,MO,-1fr;bysetpos=2,1,2") as RuleSpec.Spec
        assertEquals(listOf(-1, 5), s.byMonthDay)
        assertEquals(listOf(1, 2), s.bySetPos)
        assertEquals(setOf(WeekdayNum(1, DayOfWeek.MONDAY), WeekdayNum(0, DayOfWeek.MONDAY), WeekdayNum(-1, DayOfWeek.FRIDAY)), s.byDay.toSet())
    }
}
