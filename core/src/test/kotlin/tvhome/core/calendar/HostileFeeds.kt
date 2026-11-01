package tvhome.core.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/**
 * Deterministic generator for the hostile probes and the Google-shaped corpus (docs/tasks/tv-home-m1.md T2b). Output
 * is a ByteArray built at test time; nothing large is committed.
 */
internal object HostileFeeds {
    /** A growable byte buffer that writes ASCII/UTF-8 text and CRLF line ends. */
    class Bytes(initial: Int = 1 shl 16) {
        private var a = ByteArray(initial)
        var size = 0
            private set

        fun raw(b: ByteArray): Bytes {
            ensure(b.size)
            System.arraycopy(b, 0, a, size, b.size)
            size += b.size
            return this
        }

        fun text(s: String): Bytes = raw(s.toByteArray(Charsets.UTF_8))
        fun line(s: String): Bytes = text(s).raw(CRLF)

        /** RFC 5545 folding at 75 octets (as Google writes), never splitting a UTF-8 sequence. */
        fun folded(s: String): Bytes {
            val b = s.toByteArray(Charsets.UTF_8)
            var i = 0
            var first = true
            while (i < b.size) {
                val room = if (first) 75 else 74
                var end = minOf(b.size, i + room)
                while (end < b.size && end > i && (b[end].toInt() and 0xC0) == 0x80) end--
                if (!first) raw(SP)
                ensure(end - i)
                System.arraycopy(b, i, a, size, end - i)
                size += end - i
                raw(CRLF)
                i = end
                first = false
            }
            if (b.isEmpty()) raw(CRLF)
            return this
        }

        /** [n] copies of [unit] without building a String of the whole run. */
        fun repeat(unit: String, n: Int): Bytes {
            val u = unit.toByteArray(Charsets.UTF_8)
            ensure(u.size.toLong().times(n).toInt())
            repeat(n) {
                System.arraycopy(u, 0, a, size, u.size)
                size += u.size
            }
            return this
        }

        fun bytes(): ByteArray = a.copyOf(size)

        private fun ensure(more: Int) {
            if (size + more <= a.size) return
            var n = a.size
            while (n < size + more) n = n * 2
            a = a.copyOf(n)
        }

        private companion object {
            val CRLF = byteArrayOf(13, 10)
            val SP = byteArrayOf(32)
        }
    }

    const val HEAD = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//probe//EN\r\n"
    const val TAIL = "END:VCALENDAR\r\n"

    fun calendar(build: Bytes.() -> Unit): ByteArray = Bytes().text(HEAD).apply(build).text(TAIL).bytes()

    /** One VEVENT from lines written with "\n" separators. */
    fun Bytes.event(body: String): Bytes {
        line("BEGIN:VEVENT")
        for (l in body.trimIndent().split('\n')) line(l)
        return line("END:VEVENT")
    }

    // ------------------------------------------------------------------ Google-shaped feed (P14, P32, I3)

    class Expected(val events: List<EventInstance>, val vevents: Int)

    private val NY = ZoneId.of("America/New_York")
    private val WEEK = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    private fun ts(d: LocalDateTime) = "%04d%02d%02dT%02d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute, d.second)
    private fun ds(d: LocalDate) = "%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth)

    private const val NY_VTIMEZONE = "BEGIN:VTIMEZONE\nTZID:America/New_York\nX-LIC-LOCATION:America/New_York\n" +
        "BEGIN:DAYLIGHT\nTZOFFSETFROM:-0500\nTZOFFSETTO:-0400\nTZNAME:EDT\nDTSTART:19700308T020000\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU\nEND:DAYLIGHT\nBEGIN:STANDARD\nTZOFFSETFROM:-0400\nTZOFFSETTO:-0500\n" +
        "TZNAME:EST\nDTSTART:19701101T020000\nRRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU\nEND:STANDARD\nEND:VTIMEZONE"
    private const val LONDON_VTIMEZONE = "BEGIN:VTIMEZONE\nTZID:Europe/London\nBEGIN:DAYLIGHT\nTZOFFSETFROM:+0000\n" +
        "TZOFFSETTO:+0100\nTZNAME:BST\nDTSTART:19700329T010000\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\nEND:DAYLIGHT\n" +
        "BEGIN:STANDARD\nTZOFFSETFROM:+0100\nTZOFFSETTO:+0000\nTZNAME:GMT\nDTSTART:19701025T020000\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\nEND:STANDARD\nEND:VTIMEZONE"

    /**
     * A feed shaped like Google's `basic.ics`: X-WR headers, VTIMEZONEs, folded long DESCRIPTIONs, VALARMs,
     * [series] recurring series (weekly, some ended, some with an EXDATE or a moved instance, yearly all-day birthdays)
     * and one-off events up to [vevents] VEVENTs in all. The window is 2026-03-09..12 in New York (the probes' window).
     * [descLen] tunes the size (P14: about 9 MB at 15,000 VEVENTs). Returns the bytes and the instances the window
     * must show, computed here independently of the parser.
     */
    fun google(vevents: Int, series: Int, descLen: Int, seed: Int = 14): Pair<ByteArray, Expected> {
        val rnd = Random(seed)
        val out = ArrayList<EventInstance>()
        val b = Bytes(maxOf(1 shl 16, vevents * (descLen + 700)))
        b.line("BEGIN:VCALENDAR").line("PRODID:-//Google Inc//Google Calendar 70.9054//EN").line("VERSION:2.0")
            .line("CALSCALE:GREGORIAN").line("METHOD:PUBLISH").line("X-WR-CALNAME:Family")
            .line("X-WR-TIMEZONE:America/New_York").folded("X-WR-CALDESC:" + "Shared family calendar. ".repeat(6))
        for (l in NY_VTIMEZONE.split('\n')) b.line(l)
        for (l in LONDON_VTIMEZONE.split('\n')) b.line(l)
        var count = 0
        val stamp = "20260301T120000Z"
        fun common(uid: String, summary: String) {
            b.line("DTSTAMP:$stamp").line("UID:$uid").line("CREATED:20240101T000000Z")
            b.folded("DESCRIPTION:" + description(rnd, descLen))
            b.line("LAST-MODIFIED:20250101T000000Z").folded("LOCATION:123 Main St\\, Springfield\\, USA")
            b.line("SEQUENCE:0").line("STATUS:CONFIRMED").folded("SUMMARY:$summary").line("TRANSP:OPAQUE")
        }
        fun alarm() {
            b.line("BEGIN:VALARM").line("ACTION:DISPLAY").line("DESCRIPTION:This is an event reminder")
                .line("TRIGGER:-P0DT0H10M0S").line("END:VALARM")
        }
        val windowDays = (9..12).map { LocalDate.of(2026, 3, it) }
        for (i in 0 until series) {
            val uid = "series$i@google.com"
            if (i >= series * 5 / 6) {
                // yearly all-day birthday from 1950, on Mar 9..12
                val d = LocalDate.of(1950, 3, 9 + i % 4)
                b.line("BEGIN:VEVENT").line("DTSTART;VALUE=DATE:${ds(d)}").line("DTEND;VALUE=DATE:${ds(d.plusDays(1))}")
                    .line("RRULE:FREQ=YEARLY")
                common(uid, "Birthday $i")
                b.line("END:VEVENT")
                count++
                val w = LocalDate.of(2026, 3, 9 + i % 4)
                out += EventInstance(uid, "Birthday $i", EventTime.AllDay(w), EventTime.AllDay(w.plusDays(1)))
                continue
            }
            val day = WEEK[i % 7]
            val hour = 8 + i % 10
            val start = LocalDate.of(2024, 1, 1).with(TemporalAdjusters.nextOrSame(day)).atTime(hour, 0)
            val inWindow = windowDays.firstOrNull { it.dayOfWeek == day }?.atTime(hour, 0)
            val title = "Weekly $i"
            b.line("BEGIN:VEVENT").line("DTSTART;TZID=America/New_York:${ts(start)}")
                .line("DTEND;TZID=America/New_York:${ts(start.plusHours(1))}")
            when (i % 5) {
                0 -> b.line("RRULE:FREQ=WEEKLY;BYDAY=${CODES[i % 7]}")
                1 -> b.line("RRULE:FREQ=WEEKLY;UNTIL=20250101T045959Z;BYDAY=${CODES[i % 7]}")
                2 -> {
                    b.line("RRULE:FREQ=WEEKLY;BYDAY=${CODES[i % 7]}")
                    if (inWindow != null) b.line("EXDATE;TZID=America/New_York:${ts(inWindow)}")
                }
                3 -> b.line("RRULE:FREQ=WEEKLY;WKST=SU;BYDAY=${CODES[i % 7]}")
                else -> b.line("RRULE:FREQ=WEEKLY;COUNT=10;BYDAY=${CODES[i % 7]}")
            }
            common(uid, title)
            alarm()
            b.line("END:VEVENT")
            count++
            when (i % 5) {
                0 -> if (inWindow != null) out += timed(uid, title, inWindow, inWindow.plusHours(1))
                3 -> if (inWindow != null) {
                    // the window instance moved two hours later
                    val moved = inWindow.plusHours(2)
                    b.line("BEGIN:VEVENT").line("DTSTART;TZID=America/New_York:${ts(moved)}")
                        .line("DTEND;TZID=America/New_York:${ts(moved.plusHours(1))}")
                        .line("RECURRENCE-ID;TZID=America/New_York:${ts(inWindow)}")
                    common(uid, "$title (moved)")
                    b.line("END:VEVENT")
                    count++
                    out += timed(uid, "$title (moved)", moved, moved.plusHours(1))
                }
            }
        }
        var j = 0
        while (count < vevents) {
            val uid = "oneoff$j@google.com"
            val title = "Event \\, number $j"
            val inWin = j % 300 == 7
            val start = if (inWin) {
                LocalDate.of(2026, 3, 9 + j % 4).atTime(7 + j % 12, 15)
            } else {
                LocalDate.of(2019, 1, 1).plusDays(rnd.nextLong(0, 2200)).atTime(rnd.nextInt(0, 24), 30)
            }
            b.line("BEGIN:VEVENT")
            if (j % 2 == 0) {
                val s = start.atZone(NY).toInstant()
                b.line("DTSTART:${utc(s)}").line("DTEND:${utc(s.plusSeconds(1800))}")
            } else {
                b.line("DTSTART;TZID=America/New_York:${ts(start)}").line("DTEND;TZID=America/New_York:${ts(start.plusMinutes(30))}")
            }
            common(uid, title)
            if (j % 3 == 0) alarm()
            b.line("END:VEVENT")
            count++
            if (inWin) out += timed(uid, "Event , number $j", start, start.plusMinutes(30))
            j++
        }
        b.line("END:VCALENDAR")
        return b.bytes() to Expected(out, count)
    }

    private fun timed(uid: String, title: String, s: LocalDateTime, e: LocalDateTime) =
        EventInstance(uid, title, EventTime.At(s.atZone(NY).toInstant()), EventTime.At(e.atZone(NY).toInstant()))

    private fun utc(i: Instant): String = ts(LocalDateTime.ofInstant(i, java.time.ZoneOffset.UTC)) + "Z"

    private fun description(rnd: Random, len: Int): String {
        val words = listOf("Join", "Zoom", "meeting", "https://example.test/j/123", "agenda", "notes", "café", "über",
            "\\n", "\\;", "\\,", "budget", "review", "dinner", "school", "pickup")
        val sb = StringBuilder(len + 16)
        while (sb.length < len) sb.append(words[rnd.nextInt(words.size)]).append(' ')
        return sb.toString()
    }
}
