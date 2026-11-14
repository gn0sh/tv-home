package tvhome.core.feeds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Claude's game-day pick for a followed team (Brian 2026-11-13: "a fun bit of conversation before each game").
 *
 * The NHL picks thread writes one small JSON file per team 1.5 to 2 hours before the game and commits it to the `picks`
 * branch of this (public) repo (the picks thread's own branch, claude/project-thread-3aau8u: the only one it can push to), so the TV reads it from raw.githubusercontent.com over HTTPS like any other public
 * feed: no PC, no key, no account. Shape (v 1):
 * `{"v":1,"gameId":2025020245,"date":"2026-11-13","opponent":"DAL","pick":"NSH","score":"4-2","puckLine":"NSH -1.5",
 *   "reason":"one line","at":"2026-11-13T23:15:00Z"}`; the Buckeyes file has `"spread":"OSU -24.5"` instead of
 * `puckLine`. Everything but v, date, pick, reason and at is optional.
 *
 * The file is untrusted text: every field is length-capped and limited to plain printable characters, and a file that
 * breaks any rule is ignored whole (no pick is better than a wrong-looking one).
 */
data class Pick(
    val date: LocalDate,
    val opponent: String?,
    val pick: String,
    val score: String?,
    /** The puck line or the spread, whichever the file gave. */
    val line: String?,
    val reason: String,
    val at: Instant,
    /** The card's version of [reason] (pick-cut-off): at most [Picks.MAX_CARD_REASON] characters, or null. */
    val short: String? = null,
)

object Picks {
    /** The data branch and the one file per team key. Only these URLs are fetched ([HttpFeedFetcher.isAllowed]). */
    const val HOST = "raw.githubusercontent.com"
    const val PATH_PREFIX = "/gn0sh/tv-home/refs/heads/claude/project-thread-3aau8u/"
    val FILES: Map<String, String> = mapOf(
        Teams.PREDATORS.key to "preds-latest.json",
        Teams.OHIO_STATE.key to "buckeyes-latest.json",
    )

    /** At most 4 KB: the real file is about 300 bytes. */
    const val MAX_BODY = 4L * 1024
    const val MAX_REASON = 160
    const val MAX_SHORT = 24

    /**
     * The card has two lines of about 37 characters for the pick (Brian's TV, 2026-11-13: a 120-character reason was
     * cut off mid-sentence). The heading takes about 30, so the reason on the card is at most this long: the file's
     * `short`, else its `reason` when that is already short enough, else none (the pick alone is still shown).
     */
    const val MAX_CARD_REASON = 40

    /** A pick older than this is not shown, whatever its date says (the thread stopped writing). */
    val MAX_AGE: Duration = Duration.ofHours(36)

    fun url(teamKey: String): HttpUrl? = FILES[teamKey]?.let { "https://$HOST$PATH_PREFIX$it".toHttpUrl() }

    internal fun isPickUrl(url: HttpUrl): Boolean =
        url.host == HOST && url.encodedPath.startsWith(PATH_PREFIX) &&
            url.encodedPath.removePrefix(PATH_PREFIX) in FILES.values && url.query == null

    private val json = Json { ignoreUnknownKeys = true }
    private val PRINTABLE = Regex("^[\\p{L}\\p{N} .,;:!?'()+\\-/&%#@\$–—’]*\$")

    /** Null for anything that is not a well-formed v1 pick. Never throws. */
    fun parse(raw: String): Pick? = runCatching {
        if (raw.length > MAX_BODY) return null
        val o = json.parseToJsonElement(raw) as? JsonObject ?: return null
        if ((o["v"] as? JsonPrimitive)?.content != "1") return null
        fun str(key: String, max: Int): String? {
            val p = o[key] as? JsonPrimitive ?: return null
            if (!p.isString) return null
            val t = p.content.trim()
            return t.takeIf { it.isNotEmpty() && it.length <= max && PRINTABLE.matches(it) }
        }
        fun required(key: String, max: Int): String = str(key, max) ?: throw IllegalArgumentException(key)
        fun optional(key: String, max: Int): String? = if (o[key] == null) null else required(key, max)
        Pick(
            date = LocalDate.parse(required("date", 10)),
            opponent = optional("opponent", MAX_SHORT),
            pick = required("pick", MAX_SHORT),
            score = optional("score", MAX_SHORT),
            line = optional("puckLine", MAX_SHORT) ?: optional("spread", MAX_SHORT),
            reason = required("reason", MAX_REASON),
            at = Instant.parse((o["at"] as? JsonPrimitive)?.content ?: return null),
            short = optional("short", MAX_CARD_REASON),
        )
    }.getOrNull()

    /**
     * The pick for [game], or null: same local date in [zone] as the game, written no later than a little after the
     * start (a pick written after the game began is not a prediction), and not older than [MAX_AGE].
     */
    fun forGame(pick: Pick?, game: TeamGame, now: Instant, zone: ZoneId): Pick? {
        pick ?: return null
        if (pick.date != game.game.start.atZone(zone).toLocalDate()) return null
        if (pick.at.isAfter(now.plusSeconds(300))) return null // stamped in the future: clock or file is wrong
        if (Duration.between(pick.at, now) > MAX_AGE) return null
        return pick
    }

    /**
     * "Claude's pick: NSH 4-2 (+1.5) · Saros is sharper": the line loses the team when it repeats the pick, and the
     * reason is [Pick.short] or a [reason] of at most [MAX_CARD_REASON] characters, else left out, so it fits two lines.
     */
    /** Everything the file says, for the pop-up that OK on the card opens (Brian: "when i select it, the full quote
     * pops out"): the heading line, then the full reason. */
    fun full(p: Pick): String {
        val head = listOfNotNull(p.pick, p.score).joinToString(" ")
        val line = p.line?.let { " ($it)" } ?: ""
        val vs = p.opponent?.let { " vs $it" } ?: ""
        return "Claude's pick$vs: $head$line

${p.reason}"
    }

    fun text(p: Pick): String {
        val head = listOfNotNull(p.pick, p.score).joinToString(" ")
        val line = p.line?.let { " (" + it.removePrefix(p.pick + " ").trim() + ")" } ?: ""
        val reason = (p.short ?: p.reason.takeIf { it.length <= MAX_CARD_REASON })?.trimEnd('.', ' ')
        return "Claude's pick: $head$line" + (reason?.let { " · $it" } ?: "")
    }
}
