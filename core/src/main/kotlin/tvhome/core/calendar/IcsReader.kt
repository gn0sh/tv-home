package tvhome.core.calendar

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Our strict reader for the iCalendar subset Google writes (ADR 0004 decision 1; architecture section 3a). One
 * tokenizer, one pass over the bytes, no third-party parser.
 *
 * - **Strict framing** (else [RejectReason], whole feed): CRLF line ends only (a CR not followed by LF, or an LF not
 *   preceded by CR, rejects); an optional UTF-8 BOM; the first line `BEGIN:VCALENDAR`; exactly one VCALENDAR and
 *   nothing but empty lines after its END; `BEGIN`/`END` lines with no parameters and matching names; depth at most
 *   [MAX_DEPTH]; `VERSION:2.0` before the first VEVENT (and present at all); `CALSCALE`, if present, `GREGORIAN`.
 * - **Whitelist interpretation**: only VCALENDAR `VERSION`/`CALSCALE` and, in a top-level VEVENT, `UID`, `DTSTART`,
 *   `DTEND`, `DURATION`, `RRULE` (first only), `EXDATE`, `RDATE`, `RECURRENCE-ID`, `STATUS`, `SUMMARY` are ever
 *   decoded; parameters `TZID`, `VALUE`, `RANGE`. Every other line (VTIMEZONE, VALARM, X- components, DAYLIGHT, TZ,
 *   DESCRIPTION, ...) is opaque: scanned for its line end and never buffered beyond its name.
 * - **Tolerant content**: a bad value marks the VEVENT [VEventRecord.bad] (the parser skips it and counts it); one bad
 *   EXDATE/RDATE value is ignored and counted.
 *
 * Bounds: the scan is O(bytes) (bound (i)); a kept logical line is buffered up to [MAX_LINE] bytes in one reused
 * buffer; the deadline and interrupt flag are polled every [POLL_BYTES] bytes; more than `maxVevents` top-level
 * VEVENTs throws [Stop] with [ParseLimit.VEVENTS].
 */
internal class IcsReader(
    private val body: ByteArray,
    private val budget: Budget,
    private val maxVevents: Int,
    private val sink: (VEventRecord) -> Unit,
) {
    /** Top-level VEVENTs read so far. */
    var vevents = 0
        private set

    private class Reject(val reason: RejectReason) : Throwable() {
        override fun fillInStackTrace(): Throwable = this
    }

    // ---- the one line buffer ----
    private val buf = ByteArray(MAX_LINE)
    private var len = 0
    private var overflow = false
    private var mode = NAME
    private var kind = K_OTHER
    private var lineHasBytes = false

    // ---- structure ----
    private val stack = arrayOfNulls<String>(MAX_DEPTH)
    private var depth = 0
    private var opened = false
    private var closed = false
    private var versionSeen = false
    private var record: VEventRecord? = null

    /** Reads the whole body. Null when the framing is valid (records went to the sink), else why it is not. */
    fun read(): RejectReason? = try {
        scan()
        null
    } catch (r: Reject) {
        r.reason
    }

    private fun scan() {
        val b = body
        val n = b.size
        var i = if (n >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        var nextPoll = i + POLL_BYTES
        var haveLine = false
        // bound: (i) every iteration consumes at least one byte (a line end) or ends the loop
        while (i < n) {
            val continuation = haveLine && (b[i] == SP || b[i] == HT)
            var j = i
            if (continuation) {
                j++ // the fold's whitespace is not content
            } else {
                if (haveLine) endLine()
                startLine()
                haveLine = true
            }
            // bound: (i) one byte per iteration, up to the next CR or LF
            while (j < n) {
                val c = b[j]
                if (c == CR || c == LF) break
                if (mode != SKIP) take(c)
                j++
                if (j >= nextPoll) {
                    budget.poll()
                    nextPoll += POLL_BYTES
                }
            }
            if (j >= n) {
                i = n // last line with no line end: allowed
                break
            }
            if (b[j] == LF || j + 1 >= n || b[j + 1] != LF) throw Reject(RejectReason.LINE_ENDING)
            i = j + 2
            if (i >= nextPoll) {
                budget.poll()
                nextPoll = i + POLL_BYTES
            }
        }
        if (haveLine) endLine()
        if (!opened) throw Reject(RejectReason.NOT_VCALENDAR)
        if (!closed) throw Reject(RejectReason.STRUCTURE)
    }

    private fun startLine() {
        len = 0
        overflow = false
        mode = NAME
        kind = K_OTHER
        lineHasBytes = false
    }

    private fun take(c: Byte) {
        lineHasBytes = true
        if (len < MAX_LINE) {
            buf[len++] = c
        } else {
            overflow = true
            if (mode == NAME) mode = SKIP // a name over 8 KiB is no name we read
            return
        }
        if (mode == NAME && (c == SEMI || c == COLON)) resolveName(len - 1)
    }

    /** The name is buf[0, end). Decides whether the rest of the line is kept or skipped. */
    private fun resolveName(end: Int) {
        kind = classify(end)
        if (!opened && kind != K_BEGIN) throw Reject(RejectReason.NOT_VCALENDAR)
        if (closed) throw Reject(RejectReason.STRUCTURE)
        if (kind == K_GROUPED_BEGIN_END) throw Reject(RejectReason.STRUCTURE)
        val keep = when (kind) {
            K_BEGIN, K_END -> true
            K_VERSION, K_CALSCALE -> depth == 1
            K_OTHER -> false
            else -> record?.let { depth == 2 && it.wants(kind) } ?: false
        }
        mode = if (keep) KEEP else SKIP
    }

    /** Which whitelisted name buf[0, end) is (ASCII, case-insensitive). */
    private fun classify(end: Int): Int {
        // bound: (ii) a fixed table of 14 names, each compared over at most its own length
        for (k in NAMES.indices) if (nameEquals(0, end, NAMES[k])) return k
        // "x.BEGIN:..." / "a.END:...": a group prefix (vCard syntax) hiding a component boundary
        var dot = -1
        for (p in 0 until end) if (buf[p] == DOT) dot = p // bound: (i) the name, at most 8 KiB
        if (dot >= 0 && (nameEquals(dot + 1, end, NAMES[K_BEGIN]) || nameEquals(dot + 1, end, NAMES[K_END]))) {
            return K_GROUPED_BEGIN_END
        }
        return K_OTHER
    }

    private fun nameEquals(from: Int, end: Int, upper: ByteArray): Boolean {
        if (end - from != upper.size) return false
        // bound: (ii) at most the length of a fixed name (13)
        for (p in upper.indices) {
            var c = buf[from + p].toInt()
            if (c in 'a'.code..'z'.code) c -= 32
            if (c != upper[p].toInt()) return false
        }
        return true
    }

    private fun endLine() {
        if (!lineHasBytes) return // empty line: ignored
        if (mode == NAME) {
            // no ':' or ';' at all: not a content line
            if (!opened) throw Reject(RejectReason.NOT_VCALENDAR)
            if (closed) throw Reject(RejectReason.STRUCTURE)
            val k = classify(len)
            if (k == K_BEGIN || k == K_END || k == K_GROUPED_BEGIN_END) throw Reject(RejectReason.STRUCTURE)
            record?.let { if (depth == 2 && it.wants(k)) it.bad = true }
            return
        }
        if (mode == SKIP) {
            if (!opened) throw Reject(RejectReason.NOT_VCALENDAR)
            if (closed) throw Reject(RejectReason.STRUCTURE)
            return
        }
        when (kind) {
            K_BEGIN -> begin()
            K_END -> end()
            // bound: (i) trim and compare are linear in this kept line, at most 8 KiB (likewise every String
            // operation on a kept line below)
            K_VERSION -> if (contentLine().value.trim() != "2.0") throw Reject(RejectReason.VERSION) else versionSeen = true
            K_CALSCALE -> if (!contentLine().value.trim().equals("GREGORIAN", ignoreCase = true)) {
                throw Reject(RejectReason.CALSCALE)
            }
            else -> record?.let { property(it) }
        }
    }

    private fun componentName(): String {
        if (overflow) throw Reject(RejectReason.STRUCTURE)
        val line = contentLine()
        if (line.hasParams || line.malformed) throw Reject(RejectReason.STRUCTURE)
        return line.value.uppercase()
    }

    private fun begin() {
        val name = componentName()
        if (!opened) {
            if (name != "VCALENDAR") throw Reject(RejectReason.NOT_VCALENDAR)
            opened = true
        } else if (name == "VCALENDAR") {
            throw Reject(RejectReason.STRUCTURE)
        }
        if (depth >= MAX_DEPTH) throw Reject(RejectReason.NESTING)
        stack[depth++] = name
        if (depth == 2 && name == "VEVENT") {
            if (!versionSeen) throw Reject(RejectReason.VERSION)
            if (++vevents > maxVevents) throw Stop(ParseLimit.VEVENTS)
            record = VEventRecord()
        }
    }

    private fun end() {
        val name = componentName()
        if (depth == 0 || stack[depth - 1] != name) throw Reject(RejectReason.STRUCTURE)
        stack[--depth] = null
        if (depth == 1 && name == "VEVENT") {
            val r = record
            record = null
            if (r != null) sink(r)
        }
        if (depth == 0) {
            if (!versionSeen) throw Reject(RejectReason.VERSION)
            closed = true
        }
    }

    /** A whitelisted property of the current top-level VEVENT. */
    private fun property(r: VEventRecord) {
        if (kind == K_SUMMARY) {
            // over 8 KiB: the first 200 chars of what was buffered, cut at a whole UTF-8 sequence
            val line = contentLine(if (overflow) utf8Boundary(len) else len)
            if (line.malformed) return
            r.summary = IcsText.title(line.value)
            return
        }
        if (kind == K_RRULE) {
            r.rruleSeen = true
            r.rrule = if (overflow || len > MAX_RRULE_LINE) {
                RuleSpec.Unsupported
            } else {
                val line = contentLine()
                if (line.malformed) RuleSpec.Unsupported else IcsRules.parse(line.value)
            }
            return
        }
        if (overflow) {
            r.bad = true
            return
        }
        val line = contentLine()
        if (line.malformed) {
            r.bad = true
            return
        }
        when (kind) {
            K_UID -> r.uid = IcsText.uid(line.value)
            K_STATUS -> r.status = line.value.trim().uppercase()
            K_DTSTART -> r.dtStart = IcsValues.dateValue(line.value, line.tzid, line.valueType) ?: bad(r)
            K_DTEND -> r.dtEnd = IcsValues.dateValue(line.value, line.tzid, line.valueType) ?: bad(r)
            K_RECURRENCE_ID -> r.recurrenceId = IcsValues.dateValue(line.value, line.tzid, line.valueType) ?: bad(r)
            K_DURATION -> r.duration = IcsValues.duration(line.value.trim()) ?: bad(r)
            K_EXDATE, K_RDATE -> values(r, line)
        }
    }

    private fun <T> bad(r: VEventRecord): T? {
        r.bad = true
        return null
    }

    /** EXDATE / RDATE: comma-separated values; more than [MAX_VALUES] per VEVENT marks it bad. */
    private fun values(r: VEventRecord, line: ContentLine) {
        val v = line.value
        val period = line.valueType == "PERIOD"
        var p = 0
        // bound: (i) the value, at most 8 KiB; each iteration consumes one comma-separated item
        while (p <= v.length) {
            var q = v.indexOf(',', p)
            if (q < 0) q = v.length
            if (++r.valueCount > MAX_VALUES) {
                r.bad = true
                return
            }
            val item = v.substring(p, q)
            if (kind == K_EXDATE) {
                val d = IcsValues.dateValue(item, line.tzid, line.valueType)
                if (d == null) r.ignoredValues++ else r.exdates += d
            } else if (period || item.indexOf('/') >= 0) {
                val pv = IcsValues.period(item, line.tzid)
                if (pv == null) r.ignoredValues++ else r.rdates += pv
            } else {
                val d = IcsValues.dateValue(item, line.tzid, line.valueType)
                if (d == null) r.ignoredValues++ else r.rdates += d
            }
            p = q + 1
        }
    }

    /** Index <= [end] such that buf[0, it) does not end inside a UTF-8 sequence. */
    private fun utf8Boundary(end: Int): Int {
        var k = end
        // bound: (ii) at most 3 continuation bytes are stepped over
        while (k > 0 && end - k < 3 && (buf[k - 1].toInt() and 0xC0) == 0x80) k--
        if (k == 0) return end
        val lead = buf[k - 1].toInt() and 0xFF
        val need = when {
            lead >= 0xF0 -> 4
            lead >= 0xE0 -> 3
            lead >= 0xC0 -> 2
            else -> 1
        }
        return if (need > 1 && end - (k - 1) < need) k - 1 else end
    }

    private fun contentLine(end: Int = len): ContentLine = ContentLine.parse(String(buf, 0, end, Charsets.UTF_8))

    companion object {
        /** Kept logical line cap (after unfolding). */
        const val MAX_LINE = 8 * 1024
        const val MAX_RRULE_LINE = 1024
        const val MAX_DEPTH = 4
        const val MAX_VALUES = 2_000
        const val POLL_BYTES = 64 * 1024

        private const val CR: Byte = 13
        private const val LF: Byte = 10
        private const val SP: Byte = 32
        private const val HT: Byte = 9
        private const val SEMI: Byte = ';'.code.toByte()
        private const val COLON: Byte = ':'.code.toByte()
        private const val DOT: Byte = '.'.code.toByte()

        private const val NAME = 0
        private const val KEEP = 1
        private const val SKIP = 2

        const val K_BEGIN = 0
        const val K_END = 1
        const val K_VERSION = 2
        const val K_CALSCALE = 3
        const val K_UID = 4
        const val K_DTSTART = 5
        const val K_DTEND = 6
        const val K_DURATION = 7
        const val K_RRULE = 8
        const val K_EXDATE = 9
        const val K_RDATE = 10
        const val K_RECURRENCE_ID = 11
        const val K_STATUS = 12
        const val K_SUMMARY = 13
        const val K_OTHER = 100
        const val K_GROUPED_BEGIN_END = 101

        private val NAMES: Array<ByteArray> = arrayOf(
            "BEGIN", "END", "VERSION", "CALSCALE", "UID", "DTSTART", "DTEND", "DURATION", "RRULE", "EXDATE", "RDATE",
            "RECURRENCE-ID", "STATUS", "SUMMARY",
        ).map { it.toByteArray(Charsets.US_ASCII) }.toTypedArray() // bound: (ii) 14 constants, once
    }
}

/**
 * The whitelisted fields of one top-level VEVENT, as written (zones not yet resolved). Single-valued properties keep
 * their first occurrence. At most [IcsReader.MAX_VALUES] EXDATE+RDATE values (transient: one VEVENT at a time).
 */
internal class VEventRecord {
    var uid: String? = null
    var summary: String? = null
    var dtStart: DateValue? = null
    var dtEnd: DateValue? = null
    var duration: IcsDuration? = null
    var rruleSeen = false
    var rrule: RuleSpec? = null
    val exdates = ArrayList<DateValue>()
    val rdates = ArrayList<Any>() // DateValue or PeriodValue
    var recurrenceId: DateValue? = null
    var status: String? = null
    var bad = false
    var valueCount = 0
    var ignoredValues = 0

    /** Whether a line of this kind is still read (first occurrence only, except EXDATE/RDATE). */
    fun wants(kind: Int): Boolean = when (kind) {
        IcsReader.K_UID -> uid == null
        IcsReader.K_SUMMARY -> summary == null
        IcsReader.K_DTSTART -> dtStart == null
        IcsReader.K_DTEND -> dtEnd == null
        IcsReader.K_DURATION -> duration == null
        IcsReader.K_RRULE -> !rruleSeen
        IcsReader.K_RECURRENCE_ID -> recurrenceId == null
        IcsReader.K_STATUS -> status == null
        IcsReader.K_EXDATE, IcsReader.K_RDATE -> !bad
        else -> false
    }
}

/** One decoded content line: name, the three parameters we read, the value. [malformed]: no value separator. */
internal class ContentLine(
    val name: String,
    val tzid: String?,
    val valueType: String?,
    val range: String?,
    val hasParams: Boolean,
    val value: String,
    val malformed: Boolean,
) {
    companion object {
        /** RFC 5545 3.1: name *(";" param) ":" value; param values may be quoted. bound: (i) the line, <= 8 KiB. */
        fun parse(s: String): ContentLine {
            var i = 0
            val n = s.length
            // bound: (i) every loop below only advances i over s, a kept line of at most 8 KiB
            while (i < n && s[i] != ';' && s[i] != ':') i++
            val name = s.substring(0, i)
            var tzid: String? = null
            var valueType: String? = null
            var range: String? = null
            var hasParams = false
            while (i < n && s[i] == ';') { // bound: (i) advances i, at most 8 KiB
                hasParams = true
                i++
                val ps = i
                while (i < n && s[i] != '=' && s[i] != ';' && s[i] != ':') i++ // bound: (i) advances i
                val pname = s.substring(ps, i).uppercase()
                var first: String? = null
                if (i < n && s[i] == '=') {
                    i++
                    while (true) { // bound: (i) each pass advances i past one value; exits at the end of s
                        val v: String
                        if (i < n && s[i] == '"') {
                            val close = s.indexOf('"', i + 1)
                            if (close < 0) return ContentLine(name, tzid, valueType, range, hasParams, "", true)
                            v = s.substring(i + 1, close)
                            i = close + 1
                        } else {
                            val vs = i
                            while (i < n && s[i] != ',' && s[i] != ';' && s[i] != ':') i++ // bound: (i) advances i
                            v = s.substring(vs, i)
                        }
                        if (first == null) first = v
                        if (i < n && s[i] == ',') i++ else break
                    }
                }
                when (pname) {
                    "TZID" -> if (tzid == null) tzid = first
                    "VALUE" -> if (valueType == null) valueType = first?.uppercase()
                    "RANGE" -> if (range == null) range = first?.uppercase()
                }
            }
            if (i >= n || s[i] != ':') return ContentLine(name, tzid, valueType, range, hasParams, "", true)
            return ContentLine(name, tzid, valueType, range, hasParams, s.substring(i + 1), false)
        }
    }
}

/** A DATE or DATE-TIME as written: wall clock (midnight for a DATE), UTC flag, TZID parameter. */
internal class DateValue(val local: LocalDateTime, val isDate: Boolean, val utc: Boolean, val tzid: String?) {
    override fun toString(): String = "DateValue($local, date=$isDate, utc=$utc, tzid=${tzid != null})"
}

/** An RDATE PERIOD: start plus an end or a duration. */
internal class PeriodValue(val start: DateValue, val end: DateValue?, val duration: IcsDuration?)

/** RFC 5545 dur-value. */
internal class IcsDuration(val negative: Boolean, val weeks: Long, val days: Long, val hours: Long, val minutes: Long, val seconds: Long) {
    fun toMillis(): Long {
        val ms = ((((weeks * 7 + days) * 24 + hours) * 60 + minutes) * 60 + seconds) * 1000
        return if (negative) -ms else ms
    }
}

/** Value parsers. None throws or builds an exception: invalid input gives null (H8's lesson: exceptions cost). */
internal object IcsValues {
    /** DATE (`YYYYMMDD`) or DATE-TIME (`YYYYMMDDTHHMMSS[Z]`); seconds 60 read as 59. Null when invalid. */
    fun dateValue(raw: String, tzid: String?, valueType: String?): DateValue? {
        val s = raw.trim()
        if (s.length == 8) {
            if (valueType == "DATE-TIME") return null
            val y = digits(s, 0, 4)
            val mo = digits(s, 4, 2)
            val d = digits(s, 6, 2)
            if (!validDate(y, mo, d)) return null
            return DateValue(LocalDateTime.of(y, mo, d, 0, 0), isDate = true, utc = false, tzid = null)
        }
        if (s.length != 15 && s.length != 16) return null
        if (valueType == "DATE" || s[8] != 'T') return null
        val utc = s.length == 16
        if (utc && s[15] != 'Z' && s[15] != 'z') return null
        val y = digits(s, 0, 4)
        val mo = digits(s, 4, 2)
        val d = digits(s, 6, 2)
        val h = digits(s, 9, 2)
        val mi = digits(s, 11, 2)
        val se = digits(s, 13, 2)
        if (!validDate(y, mo, d) || h !in 0..23 || mi !in 0..59 || se !in 0..60) return null
        return DateValue(LocalDateTime.of(y, mo, d, h, mi, se.coerceAtMost(59)), isDate = false, utc = utc, tzid = if (utc) null else tzid)
    }

    /** `start/end` or `start/duration`. */
    fun period(raw: String, tzid: String?): PeriodValue? {
        val s = raw.trim()
        val slash = s.indexOf('/')
        if (slash < 0) return null
        val start = dateValue(s.substring(0, slash), tzid, "DATE-TIME") ?: return null
        val rest = s.substring(slash + 1)
        if (rest.startsWith("P") || rest.startsWith("+P") || rest.startsWith("-P")) {
            return PeriodValue(start, null, duration(rest) ?: return null)
        }
        return PeriodValue(start, dateValue(rest, tzid, "DATE-TIME") ?: return null, null)
    }

    /** `[+-]P(nW | [nD][T[nH][nM][nS]])`; each number at most 9 digits. bound: (i) the value. */
    fun duration(s: String): IcsDuration? {
        var i = 0
        val n = s.length
        var neg = false
        if (i < n && (s[i] == '+' || s[i] == '-')) {
            neg = s[i] == '-'
            i++
        }
        if (i >= n || s[i] != 'P') return null
        i++
        var w = 0L
        var d = 0L
        var h = 0L
        var m = 0L
        var sec = 0L
        var inTime = false
        var any = false
        var lastRank = 0
        // bound: (i) the value (at most 8 KiB); each pass consumes at least one char
        while (i < n) {
            if (s[i] == 'T') {
                if (inTime) return null
                inTime = true
                i++
                continue
            }
            val ds = i
            while (i < n && s[i] in '0'..'9') i++ // bound: (i) advances i
            if (i == ds || i - ds > 9 || i >= n) return null
            val v = s.substring(ds, i).toLong()
            val rank = when (s[i]) {
                'W' -> if (inTime) return null else 1
                'D' -> if (inTime) return null else 2
                'H' -> if (!inTime) return null else 3
                'M' -> if (!inTime) return null else 4
                'S' -> if (!inTime) return null else 5
                else -> return null
            }
            if (rank <= lastRank) return null
            lastRank = rank
            when (rank) {
                1 -> w = v
                2 -> d = v
                3 -> h = v
                4 -> m = v
                else -> sec = v
            }
            any = true
            i++
        }
        if (!any) return null
        return IcsDuration(neg, w, d, h, m, sec)
    }

    /** Non-negative integer from [len] ASCII digits at [at], or -1. */
    fun digits(s: String, at: Int, len: Int): Int {
        var v = 0
        for (p in at until at + len) { // bound: (ii) at most 4 digits
            val c = s[p]
            if (c !in '0'..'9') return -1
            v = v * 10 + (c - '0')
        }
        return v
    }

    fun validDate(y: Int, m: Int, d: Int): Boolean {
        if (y !in 0..9999 || m !in 1..12 || d < 1) return false
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val len = when (m) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return d <= len
    }
}

/** TEXT values (RFC 5545 3.3.11): unescaping and the field caps of architecture section 3a. */
internal object IcsText {
    const val MAX_CHARS = 200

    /** `\\`, `\;`, `\,`, `\n`/`\N`; any other backslash is kept as written. bound: (i) the value. */
    fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        // bound: (i) the value, at most 8 KiB; each pass consumes one or two chars
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    '\\', ';', ',' -> sb.append(e)
                    else -> sb.append(c).append(e)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** At most [MAX_CHARS] UTF-16 chars, never splitting a surrogate pair. */
    fun cut(s: String): String {
        if (s.length <= MAX_CHARS) return s
        var end = MAX_CHARS
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }

    /** SUMMARY: unescaped, C0 controls to space, trimmed, cut. */
    fun title(raw: String): String = normalizeTitle(unescape(raw))

    /** An already-unescaped title: C0 controls to space, trimmed, cut. */
    fun normalizeTitle(u: String): String {
        val sb = StringBuilder(u.length)
        for (c in u) sb.append(if (c < ' ') ' ' else c) // bound: (i) the value
        return cut(sb.toString().trim())
    }

    /** UID: unescaped, trimmed, cut. */
    fun uid(raw: String): String = normalizeUid(unescape(raw))

    fun normalizeUid(u: String): String = cut(u.trim())
}

/** An RRULE as read: [Spec] when every part is known, valid and in range; [Unsupported] otherwise (DTSTART only). */
internal sealed interface RuleSpec {
    object Unsupported : RuleSpec

    class Spec(
        val freq: Freq,
        val interval: Int,
        val count: Long?,
        val until: DateValue?,
        val byMonth: List<Int>,
        val byMonthDay: List<Int>,
        val byDay: List<WeekdayNum>,
        val bySetPos: List<Int>,
        val byHour: List<Int>,
        val byMinute: List<Int>,
        val bySecond: List<Int>,
        val wkst: DayOfWeek,
    ) : RuleSpec
}

/**
 * Our RRULE parser (H5, H9). Every BYxxx list becomes a set over its RFC domain, so it cannot exceed BYMONTH 12,
 * BYMONTHDAY 62, BYDAY 749, BYHOUR 24, BYMINUTE 60, BYSECOND 61, BYSETPOS 732. Any out-of-range value, unknown part,
 * repeated part, empty list, SECONDLY/MINUTELY/HOURLY, BYYEARDAY or BYWEEKNO gives [RuleSpec.Unsupported]. The caller
 * has already checked the line is at most 1 KiB.
 */
internal object IcsRules {
    private val DAY_CODES = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
    private val KNOWN = setOf(
        "FREQ", "UNTIL", "COUNT", "INTERVAL", "BYSECOND", "BYMINUTE", "BYHOUR", "BYDAY", "BYMONTHDAY", "BYYEARDAY",
        "BYWEEKNO", "BYMONTH", "BYSETPOS", "WKST",
    )

    fun parse(value: String): RuleSpec {
        val parts = HashMap<String, String>()
        // bound: (i) the RRULE value, at most 1 KiB; each iteration consumes one ';'-separated part
        for (part in value.uppercase().split(';')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq <= 0) return RuleSpec.Unsupported
            val k = part.substring(0, eq).trim()
            if (k !in KNOWN || parts.containsKey(k)) return RuleSpec.Unsupported
            parts[k] = part.substring(eq + 1).trim()
        }
        val freq = when (parts["FREQ"]) {
            "DAILY" -> Freq.DAILY
            "WEEKLY" -> Freq.WEEKLY
            "MONTHLY" -> Freq.MONTHLY
            "YEARLY" -> Freq.YEARLY
            // SECONDLY, MINUTELY, HOURLY: no family calendar needs them and they were the CPU/memory DoS vector.
            else -> return RuleSpec.Unsupported
        }
        if ("BYYEARDAY" in parts || "BYWEEKNO" in parts) return RuleSpec.Unsupported
        val interval = parts["INTERVAL"]?.let { int(it, signed = false) ?: return RuleSpec.Unsupported } ?: 1
        if (interval !in 1..Rule.MAX_INTERVAL) return RuleSpec.Unsupported
        val count = parts["COUNT"]?.let { int(it, signed = false) ?: return RuleSpec.Unsupported }?.toLong()
        if (count != null && count !in 1..Rule.MAX_COUNT) return RuleSpec.Unsupported
        val until = parts["UNTIL"]?.let { IcsValues.dateValue(it, null, null) ?: return RuleSpec.Unsupported }
        val byMonth = ints(parts["BYMONTH"], 1..12, zero = true, signed = false) ?: return RuleSpec.Unsupported
        val byMonthDay = ints(parts["BYMONTHDAY"], -31..31, zero = false, signed = true) ?: return RuleSpec.Unsupported
        val bySetPos = ints(parts["BYSETPOS"], -366..366, zero = false, signed = true) ?: return RuleSpec.Unsupported
        val byHour = ints(parts["BYHOUR"], 0..23, zero = true, signed = false) ?: return RuleSpec.Unsupported
        val byMinute = ints(parts["BYMINUTE"], 0..59, zero = true, signed = false) ?: return RuleSpec.Unsupported
        val bySecond = ints(parts["BYSECOND"], 0..60, zero = true, signed = false) ?: return RuleSpec.Unsupported
        val byDay = days(parts["BYDAY"]) ?: return RuleSpec.Unsupported
        val wkst = parts["WKST"]?.let { DAY_CODES[it] ?: return RuleSpec.Unsupported } ?: DayOfWeek.MONDAY
        return RuleSpec.Spec(freq, interval, count, until, byMonth, byMonthDay, byDay, bySetPos, byHour, byMinute, bySecond, wkst)
    }

    /** Comma list into a sorted set over [range]; null when any value is invalid. Absent: empty. */
    private fun ints(v: String?, range: IntRange, zero: Boolean, signed: Boolean): List<Int>? {
        if (v == null) return emptyList()
        val set = java.util.TreeSet<Int>()
        // bound: (i) the value, at most 1 KiB; the set holds at most the domain size
        for (item in v.split(',')) {
            val x = int(item, signed) ?: return null
            if (x !in range || (!zero && x == 0)) return null
            set += x
        }
        return set.toList()
    }

    /** `[+|-][n]DD` items into a set of at most 7 x 107 entries; ordinals 1..53. */
    private fun days(v: String?): List<WeekdayNum>? {
        if (v == null) return emptyList()
        val set = LinkedHashSet<WeekdayNum>()
        // bound: (i) the value, at most 1 KiB
        for (item in v.split(',')) {
            if (item.length < 2) return null
            val day = DAY_CODES[item.substring(item.length - 2)] ?: return null
            val ord = item.substring(0, item.length - 2)
            val n = if (ord.isEmpty()) 0 else int(ord, signed = true) ?: return null
            if (ord.isNotEmpty() && (n == 0 || n !in -53..53)) return null
            set += WeekdayNum(n, day)
        }
        return set.toList()
    }

    /** Strict integer: optional sign (if [signed]), 1 to 10 ASCII digits, fits an Int. */
    private fun int(s: String, signed: Boolean): Int? {
        var i = 0
        var neg = false
        if (signed && s.isNotEmpty() && (s[0] == '+' || s[0] == '-')) {
            neg = s[0] == '-'
            i = 1
        }
        if (i >= s.length || s.length - i > 10) return null
        var v = 0L
        // bound: (ii) at most 10 digits
        while (i < s.length) {
            val c = s[i]
            if (c !in '0'..'9') return null
            v = v * 10 + (c - '0')
            i++
        }
        if (v > Int.MAX_VALUE) return null
        return if (neg) -v.toInt() else v.toInt()
    }
}
