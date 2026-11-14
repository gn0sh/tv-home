package tvhome.core.ui

import tvhome.core.feeds.GameDay
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.Pick
import tvhome.core.feeds.TeamConfig
import tvhome.core.feeds.TeamGame
import tvhome.core.feeds.Teams
import tvhome.core.feeds.Venue
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The game-day scoreboard (docs/design/tv-home-game-day-scoreboard.md): the playing surface seen from above with both
 * teams' abbreviations on it. The followed team ([own]) is always on the left. [meta] is the one line beside the pick
 * stub; [live] puts the red lamp before it. Pure: the app only draws it.
 */
data class Scoreboard(
    val surface: Surface,
    val own: String,
    val opp: String,
    val center: Center,
    val meta: String,
    val live: Boolean,
) {
    enum class Surface { RINK, FIELD }

    sealed interface Center {
        /** The start time: "9:00" and "PM", or "21:00" and null on the 24-hour clock. */
        data class Time(val time: String, val ampm: String?) : Center

        data class Score(val own: Int, val opp: Int) : Center
    }

    companion object {
        private val H12 = DateTimeFormatter.ofPattern("h:mm", Locale.US)
        private val AMPM = DateTimeFormatter.ofPattern("a", Locale.US)
        private val H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        const val LIVE_UNKNOWN = "live"
        const val FINAL = "Final"

        /**
         * The board for today's [tg] ([GameDay.select]'s game), or null when either abbreviation is unknown: the text
         * card then shows as before. [tv] is the network text to append (the mapped network, or [GameDay.tvText]) and
         * must be null when a Watch pill names it already; it is never shown for a final. Never throws.
         */
        fun of(tg: TeamGame, now: Instant, zone: ZoneId, is24h: Boolean, tv: String?): Scoreboard? = runCatching {
            val (team, g) = tg
            val own = team.abbr ?: return null
            val opp = g.opponentAbbr ?: return null
            val a = g.ownScore
            val b = g.oppScore
            val start = g.start.atZone(zone)
            val time = if (is24h) Center.Time(H24.format(start), null) else Center.Time(H12.format(start), AMPM.format(start))
            val center = if (g.phase != GamePhase.UPCOMING && a != null && b != null) Center.Score(a, b) else time
            val base = when (g.phase) {
                GamePhase.UPCOMING -> (if (g.home) "Home" else "Away") + " · " + GameDay.countdown(Duration.between(now, g.start))
                GamePhase.LIVE -> g.period ?: LIVE_UNKNOWN
                GamePhase.FINAL -> g.period?.let { "$FINAL ($it)" } ?: FINAL
            }
            val meta = if (tv != null && g.phase != GamePhase.FINAL) "$base · $tv" else base
            val surface = if (team.venue == Venue.ARENA) Surface.RINK else Surface.FIELD
            Scoreboard(surface, own, opp, center, meta, g.phase == GamePhase.LIVE)
        }.getOrNull()
    }
}

/**
 * The full ticket behind OK on the pick stub (design section 5). [paragraphs] is the body, one or two; [stakes] is the
 * footer ("Pretend stakes: ..."), cut from the body.
 */
data class Ticket(
    val title: String,
    val big: String,
    val line: String?,
    val short: String?,
    val paragraphs: List<String>,
    val stakes: String?,
)

/** The ticket's words, as pure functions (table-tested). */
object TicketText {
    const val LABEL = "Claude's pick"
    const val CLOSE = "Back to close"
    const val STAKES = "Pretend stakes:"

    /** From this local hour a game today is "Tonight". */
    const val TONIGHT_FROM = 17

    /** A body shorter than this stays one paragraph. */
    const val SPLIT_MIN = 200

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val TIME_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val SENTENCE_END = Regex("[.!?] (?=[A-Z])")

    /** "Predators at Golden Knights · Tonight, 9:00 PM": "Tonight" when the game starts from 5 PM local, else "Today". */
    fun title(tg: TeamGame, zone: ZoneId, is24h: Boolean = false): String {
        val (team, g) = tg
        val start = g.start.atZone(zone)
        val day = if (start.hour >= TONIGHT_FROM) "Tonight" else "Today"
        return "${team.name} ${if (g.home) "vs" else "at"} ${g.opponent} · $day, ${(if (is24h) TIME_24 else TIME).format(start)}"
    }

    /** The pick, large: "NSH". */
    fun big(p: Pick): String = p.pick

    /** The line with the pick's name stripped ("+1.5"), or the predicted score when the file has no line, or null. */
    fun line(p: Pick): String? = p.line?.removePrefix(p.pick + " ")?.trim()?.ifEmpty { null } ?: p.score

    fun short(p: Pick): String? = p.short

    /**
     * The write-up ([Pick.analysis], else [Pick.reason]) as body and stakes: everything from the last [STAKES] is the
     * stakes footer and is cut from the body (not when the text starts with it: a body is never empty).
     */
    fun bodyAndStakes(p: Pick): Pair<String, String?> {
        val full = (p.analysis ?: p.reason).trim()
        val k = full.lastIndexOf(STAKES)
        return if (k > 0) full.substring(0, k).trim() to full.substring(k).trim() else full to null
    }

    /**
     * [body] as two paragraphs, split at the sentence end (". ", "! " or "? " followed by a capital) nearest its
     * middle; one paragraph when there is no such point or the body is under [SPLIT_MIN] characters.
     */
    fun paragraphs(body: String): List<String> {
        if (body.length < SPLIT_MIN) return listOf(body)
        val mid = body.length / 2.0
        val cut = SENTENCE_END.findAll(body).map { it.range.first + 1 }.minByOrNull { kotlin.math.abs(it - mid) } ?: return listOf(body)
        return listOf(body.substring(0, cut), body.substring(cut + 1))
    }

    fun ticket(p: Pick, tg: TeamGame, zone: ZoneId, is24h: Boolean = false): Ticket {
        val (body, stakes) = bodyAndStakes(p)
        return Ticket(title(tg, zone, is24h), big(p), line(p), short(p), paragraphs(body), stakes)
    }
}

/**
 * The board's colours for one team (0xRRGGBB; docs/design/game-day-scoreboard/mockup.html). Surface paint that is the
 * same for every team is in [BoardTokens]. GlanceTokensTest checks the lettering and stub text contrast.
 */
data class BoardPalette(
    /** Own abbreviation on the surface (gold for the Predators, white for Ohio State) and the opponent's. */
    val own: Int,
    val opp: Int,
    /** The rink's boards or the field's outline. */
    val boards: Int,
    /** The field's left end zone (team primary). */
    val endZone: Int,
    /** The stub at rest: fill, outline and label, pick text. */
    val stubFill: Int,
    val stubAccent: Int,
    val stubText: Int,
    /** The focused stub: fill and outline, and the ink on it. */
    val focusFill: Int,
    val focusInk: Int,
    /** The full ticket: stub fill and its ink, body fill, the stakes and the footer rule. */
    val ticketStub: Int,
    val ticketInk: Int,
    val ticketBody: Int,
    val ticketAccent: Int,
    /** The backdrop behind the board: top, middle (at [artMidAt] of its height), bottom; the glow and the light cones. */
    val artTop: Int,
    val artMid: Int,
    val artMidAt: Float,
    val artBottom: Int,
    val glow: Int,
    val cone: Int,
    val coneAlpha: Float,
) {
    companion object {
        val PREDATORS = BoardPalette(
            own = 0xFFB81C, opp = 0xE9F0FF, boards = 0xFFB81C, endZone = 0xFFB81C,
            stubFill = 0x06183A, stubAccent = 0xFFB81C, stubText = 0xF2F4F8, focusFill = 0xFFB81C, focusInk = 0x041E42,
            ticketStub = 0xFFB81C, ticketInk = 0x041E42, ticketBody = 0x06142E, ticketAccent = 0xFFB81C,
            artTop = 0x041E42, artMid = 0x072856, artMidAt = 0.70f, artBottom = 0x041A3A,
            glow = 0x3C6EC8, cone = 0xA0C3FF, coneAlpha = 0.07f,
        )
        val OHIO_STATE = BoardPalette(
            own = 0xFFFFFF, opp = 0xE4E6EA, boards = 0xE4E6EA, endZone = 0xBB0000,
            stubFill = 0x16090A, stubAccent = 0xC4C4C4, stubText = 0xF2F4F8, focusFill = 0xFFFFFF, focusInk = 0x1A0505,
            ticketStub = 0xBB0000, ticketInk = 0xFFFFFF, ticketBody = 0x120A0B, ticketAccent = 0xFFFFFF,
            artTop = 0x2A0707, artMid = 0x4A0A0A, artMidAt = 0.55f, artBottom = 0x0B0D12,
            glow = 0xBB0000, cone = 0xFFC8C8, coneAlpha = 0.06f,
        )

        /** The designed palettes; any other team gets one built from its own colours (white lettering, accent trim). */
        fun of(team: TeamConfig): BoardPalette = when (team.key) {
            Teams.PREDATORS.key -> PREDATORS
            Teams.OHIO_STATE.key -> OHIO_STATE
            else -> OHIO_STATE.copy(
                boards = team.accent, endZone = team.primary, stubFill = team.backdropTop, stubAccent = team.accent,
                focusInk = team.backdropTop, ticketStub = team.primary, ticketBody = team.backdropTop,
                artTop = team.backdropTop, artMid = team.backdropTop, artBottom = team.screen ?: Tokens.BACKGROUND, glow = team.primary,
            )
        }

        fun of(teamKey: String): BoardPalette? = Teams.ALL.firstOrNull { it.key == teamKey }?.let(::of)
    }
}

/** Surface geometry (viewBox [W] x [H]) and paint shared by every team. The app scales it to the surface zone. */
object BoardTokens {
    const val W = 676f
    const val H = 150f

    // Rink.
    const val RINK_RADIUS = 48f
    const val RINK_TOP = 0x21498C
    const val RINK_MID = 0x153668
    const val RINK_MID_AT = 0.55f
    const val RINK_BOTTOM = 0x0E2A55
    const val BLUE_LINE = 0x4E8CFF
    const val RED_LINE = 0xE0453A
    const val CENTRE_CIRCLE_R = 52f
    const val GOAL_LINE_X = 44f
    const val CREASE_R = 18f

    // Field.
    const val FIELD_RADIUS = 10f
    const val FIELD_TOP = 0x1B5A34
    const val FIELD_BOTTOM = 0x0F3B22
    const val END_ZONE_W = 56f
    const val YARD_10 = 56.4f
    const val FAR_END_ZONE = 0x5B6068
    const val CENTRE_BOX = 0x0C2C19
    const val CENTRE_BOX_W = 124f
    const val CENTRE_BOX_H = 80f

    // Lettering, in viewBox units of the surface height (not sp: it is a graphic).
    const val ABBR_SIZE = 88f
    const val TIME_SIZE = 44f
    const val AMPM_SIZE = 22f
    const val SCORE_SIZE = 80f
    const val CENTER_TEXT = 0xFFFFFF

    /** Baselines: abbreviations, the time, AM/PM under it, a 24-hour time alone, scores. */
    const val ABBR_BASELINE = H / 2 + 31
    const val TIME_BASELINE = H / 2 + 6
    const val AMPM_BASELINE = H / 2 + 30
    const val TIME_ALONE_BASELINE = H / 2 + 16
    const val SCORE_BASELINE = H / 2 + 27

    /** Scores sit this far either side of the centre line. */
    const val SCORE_GAP = 22f

    /** The live lamp and the veil behind the full ticket. */
    const val LAMP = 0xFF4438
    const val VEIL_ALPHA = 0.80f

    /** The faint diagonal sheen across the rink (white, at its peak). */
    const val SHEEN_ALPHA = 0.07f

    /**
     * The backdrop glow behind the surface: [GLOW_ALPHA] at its centre (under the surface), falling to nothing at the
     * edge of a 560 x 190 ellipse at (890, 190) of the 1280 x 400 backdrop. No hero text sits where it is stronger
     * than [GLOW_TEXT_MAX] (Now/Next ends at x 520, the chip sits at y 68: about 0.11); the contrast test uses that.
     */
    const val GLOW_ALPHA = 0.30f
    const val GLOW_TEXT_MAX = 0.15f

    /**
     * The highest point the abbreviations reach, as a fraction of the surface height (cap height about 0.72 em above
     * [ABBR_BASELINE]): the lightest part of the top-lit gradient that lettering touches, so where contrast is checked.
     */
    const val LETTER_TOP = (ABBR_BASELINE - 0.72f * ABBR_SIZE) / H

    /** The rink's ice colour at [f] of its height (0 top, 1 bottom), as the gradient draws it. */
    fun rinkAt(f: Float): Int =
        if (f <= RINK_MID_AT) mix(RINK_TOP, RINK_MID, f / RINK_MID_AT) else mix(RINK_MID, RINK_BOTTOM, (f - RINK_MID_AT) / (1 - RINK_MID_AT))

    /** The field's turf colour at [f] of its height, under a light 10-yard band ([band]: white at 3.5%). */
    fun fieldAt(f: Float, band: Boolean = false): Int = mix(FIELD_TOP, FIELD_BOTTOM, f).let { if (band) mix(it, 0xFFFFFF, BAND_ALPHA) else it }

    const val BAND_ALPHA = 0.035f

    /** sRGB mix of two 0xRRGGBB colours, [t] of the way from [a] to [b]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int = kotlin.math.round(((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
