package tvhome.core.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tvhome.core.feeds.GameDay
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.PanelPolicies
import tvhome.core.feeds.PanelView
import tvhome.core.feeds.Stamped
import tvhome.core.feeds.WeatherNow
import tvhome.core.feeds.view
import tvhome.core.state.AgendaState
import tvhome.core.ui.GlanceInputs
import tvhome.core.ui.QuietMode
import tvhome.core.ui.activeAlerts
import tvhome.core.ui.formatTime
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The one-line Claude brief (spec M7-6, ADR 0008 section 5 as amended by the security design review).
 *
 * No event title ever leaves the TV: [briefFacts] sends events as placeholders (`E1`..`E3` with a time only) and Claude
 * writes `{E1}` where an event goes; [fillPlaceholders] puts the title (or "Busy" in Quiet) back locally, in one pass,
 * at draw time. So the cached line holds no title, and Quiet needs no cache discard.
 */
object BriefConfig {
    const val MAX_TOKENS = 1024
    const val MAX_LINE = 90
    const val MAX_RAW_LINE = 200
    const val MAX_EVENTS = 3
    const val TITLE_CUT = 24
    const val MONTHLY_LIMIT = 62
    const val MORNING_SLOT_HOUR = 7
    const val AFTERNOON_SLOT_HOUR = 16
    const val MAX_CACHE_BYTES = 2048
}

/** An event offered to the brief. [title] stays on the TV; only [id], [whenText] and [allDay] are sent. */
data class BriefEvent(val id: String, val title: String, val whenText: String, val allDay: Boolean) {
    override fun toString(): String = "BriefEvent($id, $whenText, allDay=$allDay)"
}

/**
 * The facts the brief may use. Only the fields named in [sentJson] leave the TV. [weather] is the condition word,
 * [rain] the next rain or snow window, [temps] a "now/high/low" triple; none carries a location.
 */
data class BriefFacts(
    val date: String,
    val weather: String?,
    val rain: String?,
    val nowF: Int?,
    val highF: Int?,
    val lowF: Int?,
    val alert: String?,
    val game: String?,
    val events: List<BriefEvent>,
) {
    /** The value of each nameable fact as canonical text, or null when it is not available now. */
    internal fun fact(name: String): String? = when (name) {
        "weather" -> weather
        "rain" -> rain
        "temps" -> if (nowF == null && highF == null && lowF == null) null else "now=$nowF,high=$highF,low=$lowF"
        "alert" -> alert
        "game" -> game
        else -> events.firstOrNull { it.id == name }?.let { "${it.whenText}|${it.allDay}" }
    }

    /** What is sent. No titles, no location. */
    fun sentJson(): JsonObject = buildJsonObject {
        put("date", date)
        weather?.let { put("weather", it) }
        rain?.let { put("rain", it) }
        if (nowF != null || highF != null || lowF != null) putJsonObject("temps") {
            nowF?.let { put("now", it) }; highF?.let { put("high", it) }; lowF?.let { put("low", it) }
        }
        alert?.let { put("alert", it) }
        game?.let { put("game", it) }
        putJsonArray("events") {
            for (e in events) add(buildJsonObject { put("id", e.id); put("when", e.whenText); put("allDay", e.allDay) })
        }
    }

    override fun toString(): String = "BriefFacts(${events.size} events)"
}

/** Names Claude may list in `uses`: facts it relied on. */
internal val BRIEF_USES: List<String> = listOf("weather", "rain", "temps", "alert", "game", "E1", "E2", "E3")

private val PLACEHOLDER = Regex("""\{[^{}]*\}""")
private val EVENT_PLACEHOLDER = Regex("""\{(E[1-3])\}""")

/**
 * The facts for "now", from the same inputs [tvhome.core.ui.homeGlance] reads. Events are today's remaining timed events,
 * then today's all-day ones, then tomorrow's, at most [BriefConfig.MAX_EVENTS], each with a relative time ("today
 * 5:30 PM", "tomorrow 9:00 AM", "tomorrow, all day"). A calendar 3 h stale or unavailable offers none.
 */
fun briefFacts(i: GlanceInputs): BriefFacts {
    val local = i.now.atZone(i.zone)
    val weather: WeatherNow? = (view(i.feeds.weather, PanelPolicies.WEATHER, i.switches.weather, i.now) as? PanelView.Shown)?.value
    val alertsView = view(i.feeds.alerts, PanelPolicies.ALERTS, i.switches.alerts && i.hasLocation, i.now)
    val alerts = (alertsView as? PanelView.Shown)?.value?.let { activeAlerts(it, i.now) }
    val game = if (i.switches.games) GameDay.select(i.feeds.games?.value.orEmpty(), i.now, i.zone) else null
    val gameText = game?.takeIf { it.game.phase != GamePhase.FINAL }?.let { tg ->
        val vs = if (tg.game.home) "vs" else "at"
        val net = i.watchMap.network(tg)?.let { " on $it" }.orEmpty()
        "${tg.team.name} $vs ${tg.game.opponent}, " + formatTime(tg.game.start.atZone(i.zone).toLocalTime().withSecond(0).withNano(0), i.is24h) + net
    }
    return BriefFacts(
        date = local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", " + local.toLocalDate(),
        weather = weather?.condition?.ifEmpty { null },
        rain = weather?.nextPrecip,
        nowF = weather?.tempF,
        highF = weather?.highF,
        lowF = weather?.lowF,
        alert = alerts?.alerts?.firstOrNull()?.event,
        game = gameText,
        events = briefEvents(i.agenda, i.now, i.zone, i.is24h),
    )
}

private fun briefEvents(state: AgendaState, now: Instant, zone: ZoneId, is24h: Boolean): List<BriefEvent> {
    val (today, tomorrow, updatedAt) = when (state) {
        is AgendaState.Ok -> Triple(state.today, state.tomorrow, state.updatedAt)
        is AgendaState.NothingScheduled -> Triple(state.today, state.tomorrow, state.updatedAt)
        is AgendaState.Stale -> Triple(state.today, state.tomorrow, state.updatedAt)
        is AgendaState.Unavailable -> return emptyList()
    }
    if (view(Stamped(Unit, updatedAt), PanelPolicies.CALENDAR, true, now) !is PanelView.Shown) return emptyList()
    val date = now.atZone(zone).toLocalDate()
    fun time(i: Instant) = formatTime(i.atZone(zone).toLocalTime().withSecond(0).withNano(0), is24h)
    val out = ArrayList<Triple<String, String, Boolean>>() // title, when, allDay
    val oneMinute = java.time.Duration.ofMinutes(1)
    for (day in listOf(today, tomorrow)) {
        val label = when (day.date) { date -> "today"; date.plusDays(1) -> "tomorrow"; else -> continue }
        val timed = day.items.filter { it.startsAt != null && it.label != tvhome.core.agenda.TimeLabel.AllDay }
            .filter { it.startsAt!!.plus(oneMinute).isAfter(now) }.sortedBy { it.startsAt }
        timed.forEach { out += Triple(it.title, "$label ${time(it.startsAt!!)}", false) }
        day.items.filter { it.label == tvhome.core.agenda.TimeLabel.AllDay }.forEach { out += Triple(it.title, "$label, all day", true) }
    }
    return out.take(BriefConfig.MAX_EVENTS).mapIndexed { n, (t, w, a) -> BriefEvent("E${n + 1}", t, w, a) }
}

// --- request -------------------------------------------------------------------------------------------------------

private const val SYSTEM_PROMPT = "You write one short line summing up the day for a TV home screen, from the facts in the user message. " +
    "At most 80 characters, plain text, no markdown, no links, no emoji. Use only the facts given; invent nothing. " +
    "Events have no titles: write {E1}, {E2} or {E3} where an event goes (for example \"Rain after 4, Preds 7 on ESPN+, " +
    "{E1} tomorrow 9am\"); each is replaced on the TV by a title of up to 24 characters, so leave room. Never write any " +
    "other braces. In \"uses\" list every fact you relied on."

/** The brief's schema: the line, and which facts it names (the fingerprint covers exactly those). */
private fun replySchema() = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("line") { put("type", "string") }
        putJsonObject("uses") {
            put("type", "array")
            putJsonObject("items") { put("type", "string"); putJsonArray("enum") { BRIEF_USES.forEach { add(it) } } }
        }
    }
    putJsonArray("required") { add("line"); add("uses") }
    put("additionalProperties", false)
}

/**
 * The request body: the Ask model, effort low, [BriefConfig.MAX_TOKENS], structured output, no tools, no history. There
 * is deliberately no `fallbacks` field (and the client sends no fallback beta header): a refusal is "no line", so the
 * per-call cost bound holds. The facts are the only user content.
 */
fun briefRequestBody(f: BriefFacts): String = buildJsonObject {
    put("model", AskConfig.MODEL)
    put("max_tokens", BriefConfig.MAX_TOKENS)
    put("system", SYSTEM_PROMPT)
    putJsonObject("output_config") {
        put("effort", AskConfig.EFFORT)
        putJsonObject("format") { put("type", "json_schema"); put("schema", replySchema()) }
    }
    putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", f.sentJson().toString()) }) }
}.toString()

// --- cleaning and filling --------------------------------------------------------------------------------------------

object BriefText {
    private val URL = Regex("""(?i)(?:\b[a-z][a-z0-9+.-]{1,12}://|\bwww\.)\S*|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|io|tv|gov|edu|co|app|dev|me|info)\b\S*""")

    /** One line: control, format and separator characters dropped, URLs removed, spaces collapsed, trimmed. */
    fun clean(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); i += Character.charCount(cp)
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> if (cp == '\n'.code || cp == '\t'.code) sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        return URL.replace(sb.toString(), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** A title for the line: cleaned, cut to [BriefConfig.TITLE_CUT] at a word boundary. "Busy" in Quiet. */
    fun title(raw: String, quiet: Boolean): String {
        if (quiet) return QuietMode.BUSY
        val c = clean(raw)
        if (c.isEmpty()) return "an event"
        if (c.length <= BriefConfig.TITLE_CUT) return c
        val cut = c.substring(0, BriefConfig.TITLE_CUT)
        val sp = cut.lastIndexOf(' ')
        return (if (sp >= BriefConfig.TITLE_CUT / 2) cut.substring(0, sp) else cut).trimEnd()
    }
}

/**
 * Replaces `{E1}`..`{E3}` with each event's title (or "Busy" in Quiet) in ONE pass: the replacement text is never
 * scanned again, so a title that itself contains `{E2}` is not expanded (brief-fill-single-pass). A placeholder with
 * no matching event becomes "an event" (the line was already validated, this is only defence).
 */
fun fillPlaceholders(line: String, events: List<BriefEvent>, quiet: Boolean): String =
    EVENT_PLACEHOLDER.replace(line) { m ->
        events.firstOrNull { it.id == m.groupValues[1] }?.let { BriefText.title(it.title, quiet) } ?: "an event"
    }

/** The free local line, from the same facts: "Rain after 4 PM · Predators vs Blues, 7:00 PM · Dentist tomorrow 9:00 AM". */
fun templateLine(f: BriefFacts, quiet: Boolean): String? {
    val parts = buildList {
        f.alert?.let { add(it) }
        f.rain?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
        f.game?.let { add(it) }
        f.events.firstOrNull()?.let { add("${BriefText.title(it.title, quiet)} ${it.whenText}") }
    }.map(BriefText::clean).filter { it.isNotEmpty() }
    var keep = parts
    while (keep.isNotEmpty() && keep.joinToString(" · ").length > BriefConfig.MAX_LINE) keep = keep.dropLast(1)
    if (keep.isEmpty()) return parts.firstOrNull()?.take(BriefConfig.MAX_LINE)
    return keep.joinToString(" · ")
}

// --- reply ---------------------------------------------------------------------------------------------------------

/** A validated reply: the cleaned [line] (placeholders still in it, no titles) and the facts it names. */
class BriefReply(val line: String, val uses: List<String>) {
    override fun toString(): String = "BriefReply(${line.length} chars, $uses)"

    /** Everything the line depends on: `uses` plus any placeholder it contains. */
    fun named(): List<String> = (uses + EVENT_PLACEHOLDER.findAll(line).map { it.groupValues[1] }).distinct().sorted()

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        /**
         * Strict: one JSON object with a string `line` and an array `uses` of known names. The line is cleaned and must be
         * non-empty and at most [BriefConfig.MAX_RAW_LINE]; any `{...}` that is not an `{E1}`..`{E3}` sent in [f], and any
         * `uses` entry naming an event not sent, rejects the whole reply (null: template).
         */
        fun parse(text: String, f: BriefFacts): BriefReply? = runCatching {
            val o = json.parseToJsonElement(text) as? JsonObject ?: return null
            val line = BriefText.clean((o["line"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null)
            val uses = (o["uses"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null } ?: return null
            if (line.isEmpty() || line.length > BriefConfig.MAX_RAW_LINE) return null
            if (uses.any { it !in BRIEF_USES } || uses.size > BRIEF_USES.size) return null
            val sent = f.events.map { it.id }.toSet()
            if (uses.any { it.startsWith("E") && it !in sent }) return null
            for (m in PLACEHOLDER.findAll(line)) {
                val id = EVENT_PLACEHOLDER.matchEntire(m.value)?.groupValues?.get(1) ?: return null
                if (id !in sent) return null
            }
            if (line.replace(PLACEHOLDER, "").let { '{' in it || '}' in it }) return null
            BriefReply(line, uses.distinct())
        }.getOrNull()
    }
}

/** Fingerprint over only the facts a reply names (canonical text, SHA-256), so unrelated changes keep the line. */
internal fun fingerprint(named: List<String>, f: BriefFacts): String {
    val canonical = named.sorted().joinToString("\n") { "$it=${f.fact(it)}" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

// --- ledger --------------------------------------------------------------------------------------------------------

/**
 * Two slots a day (07:00 and 16:00 local), at most [BriefConfig.MONTHLY_LIMIT] attempts a month, in its own file
 * `<yyyy-mm> <count> <last slot>` (last slot `yyyy-mm-ddThh`, or `-`). Fail closed (brief-cap-fail-open,
 * brief-slot-attempt):
 * - the slot is recorded as attempted BEFORE the call; if that write fails there is no call;
 * - a file that exists but is unreadable or malformed counts as exhausted for the current month and is rewritten so;
 * - a stored month or slot later than now (clock moved back) means no call and no rewrite;
 * - no file at all is a fresh month.
 * One attempt per slot whatever the outcome; no refunds.
 */
class BriefLedger(
    private val store: CountStore,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    private val limit: Int = BriefConfig.MONTHLY_LIMIT,
) {
    /** True when this call has the slot: the attempt is written and the caller may make exactly one request. */
    @Synchronized
    fun tryBegin(): Boolean {
        val local = clock().atZone(zone())
        val slot = slotOf(local.toLocalDate().toString(), local.hour) ?: return false
        val month = slot.substring(0, 7)
        val raw = try { store.read() } catch (_: Throwable) { return exhaust(month) }
        var count = 0
        if (raw != null) {
            val p = raw.trim().split(' ')
            val storedMonth = p.getOrNull(0)
            val storedCount = p.getOrNull(1)?.toIntOrNull()
            val last = p.getOrNull(2)
            val ok = p.size == 3 && storedMonth != null && Regex("""\d{4}-\d{2}""").matches(storedMonth) && storedCount != null &&
                storedCount in 0..10_000 && (last == "-" || (last != null && Regex("""\d{4}-\d{2}-\d{2}T\d{2}""").matches(last)))
            if (!ok) return exhaust(month)
            if (storedMonth!! > month) return false
            if (last != "-" && last!! > slot) return false
            if (last == slot) return false
            count = if (storedMonth == month) storedCount!! else 0
        }
        if (count >= limit) return false
        return runCatching { store.write("$month ${count + 1} $slot") }.isSuccess
    }

    private fun exhaust(month: String): Boolean {
        runCatching { store.write("$month $limit -") }
        return false
    }

    private fun slotOf(date: String, hour: Int): String? = when {
        hour >= BriefConfig.AFTERNOON_SLOT_HOUR -> "${date}T%02d".format(BriefConfig.AFTERNOON_SLOT_HOUR)
        hour >= BriefConfig.MORNING_SLOT_HOUR -> "${date}T%02d".format(BriefConfig.MORNING_SLOT_HOUR)
        else -> null
    }
}

// --- engine --------------------------------------------------------------------------------------------------------

/**
 * Generates and serves the brief. [refreshIfDue] is called on the home screen's minute tick; it does nothing unless the
 * switch is on, a key exists and [BriefLedger] grants the slot, and then makes exactly one request. [line] is what the
 * dock band draws: the cached line while the facts it names are unchanged, else the free [templateLine]; it never throws
 * and never shows error text. [cache] holds `{line, uses, fingerprint, at}` (no titles) in its own small file.
 */
class BriefEngine(
    private val client: BriefClient,
    private val ledger: BriefLedger,
    private val cache: CountStore,
    private val clock: () -> Instant,
    private val enabled: () -> Boolean,
    private val hasKey: () -> Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun refreshIfDue(facts: BriefFacts) {
        val go = withContext(Dispatchers.IO) { runCatching { enabled() && hasKey() && ledger.tryBegin() }.getOrDefault(false) }
        if (!go) return
        val result = try {
            client.post(briefRequestBody(facts))
        } catch (e: CancellationException) {
            throw e // the slot stays spent: no refund, no retry
        } catch (_: Throwable) {
            return
        }
        val reply = (result as? ClaudeResult.Text)?.let { BriefReply.parse(it.text, facts) } ?: return
        val entry = buildJsonObject {
            put("line", reply.line)
            put("uses", buildJsonArray { reply.uses.forEach { add(it) } })
            put("fingerprint", fingerprint(reply.named(), facts))
            put("at", clock().toString())
        }.toString()
        if (entry.length <= BriefConfig.MAX_CACHE_BYTES) withContext(Dispatchers.IO) { runCatching { cache.write(entry) } }
    }

    /** The line to draw now, or null when there is nothing to say. Placeholders are filled here, with "Busy" in Quiet. */
    fun line(facts: BriefFacts, quiet: Boolean): String? {
        val cached = runCatching { cachedFor(facts) }.getOrNull()
        val filled = cached?.let { BriefText.clean(fillPlaceholders(it, facts.events, quiet)) }?.takeIf { it.isNotEmpty() && it.length <= BriefConfig.MAX_LINE }
        return filled ?: templateLine(facts, quiet)
    }

    private fun cachedFor(facts: BriefFacts): String? {
        val text = cache.read() ?: return null
        if (text.length > BriefConfig.MAX_CACHE_BYTES) return null
        val o = json.parseToJsonElement(text) as? JsonObject ?: return null
        val line = (o["line"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val uses = (o["uses"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: return null } ?: return null
        val fp = (o["fingerprint"] as? JsonPrimitive)?.content ?: return null
        val at = (o["at"] as? JsonPrimitive)?.content?.let { Instant.parse(it) } ?: return null
        if (at.isAfter(clock().plusSeconds(300))) return null
        // Re-validate: the file is not trusted (same rules as a fresh reply, against the facts as they are now).
        val reply = BriefReply.parse(buildJsonObject { put("line", line); put("uses", buildJsonArray { uses.forEach { add(it) } }) }.toString(), facts) ?: return null
        if (reply.line != line) return null
        return if (fingerprint(reply.named(), facts) == fp) reply.line else null
    }

    override fun toString(): String = "BriefEngine"
}
