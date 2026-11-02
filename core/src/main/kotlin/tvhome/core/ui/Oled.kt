package tvhome.core.ui

import java.time.Duration
import kotlin.math.PI
import kotlin.math.sin

/**
 * Which burn-in rules apply (ADR 0008 section 4). LG OLED: drift on, dim at 10 min, near-black at 30 min.
 * Vizio LED: drift off, dim at 30 min, no near-black.
 */
enum class TvProfile(val drift: Boolean, val dimAfter: Duration, val nearBlackAfter: Duration?) {
    LG_OLED(true, Duration.ofMinutes(10), Duration.ofMinutes(30)),
    VIZIO_LED(false, Duration.ofMinutes(30), null);

    companion object {
        /** The safer profile when nothing says otherwise. */
        val DEFAULT = LG_OLED

        /** adb setting `tv_profile` (`lg` | `vizio`) if valid, else the EDID PnP id (`GSM` LG, `VIZ` Vizio), else LG. */
        fun resolve(setting: String?, pnpId: String?): TvProfile =
            when (setting?.trim()?.lowercase()) {
                "lg" -> LG_OLED
                "vizio" -> VIZIO_LED
                else -> when (pnpId?.trim()?.uppercase()) {
                    "GSM" -> LG_OLED
                    "VIZ" -> VIZIO_LED
                    else -> DEFAULT
                }
            }
    }
}

/** A drift offset in dp. */
data class DriftOffset(val dx: Float, val dy: Float) {
    companion object { val ZERO = DriftOffset(0f, 0f) }
}

/**
 * Slow pixel drift for the static elements (top strip, hero text). A closed Lissajous path that repeats every hour:
 * x period 30 min, y period 20 min, within ±[MAX_X_DP] / ±[MAX_Y_DP]. Peak speed is about 0.03 dp/s, so it moves at
 * most about 0.6 dp per 20 s: no visible jump.
 */
object Drift {
    const val MAX_X_DP = 6f
    const val MAX_Y_DP = 4f
    private const val X_PERIOD_MS = 30 * 60_000.0
    private const val Y_PERIOD_MS = 20 * 60_000.0

    fun offset(elapsed: Duration): DriftOffset {
        val ms = elapsed.toMillis().toDouble()
        return DriftOffset(
            (MAX_X_DP * sin(2 * PI * ms / X_PERIOD_MS)).toFloat(),
            (MAX_Y_DP * sin(2 * PI * ms / Y_PERIOD_MS)).toFloat(),
        )
    }

    /** Zero when the profile has drift off. */
    fun offsetFor(profile: TvProfile, elapsed: Duration): DriftOffset =
        if (profile.drift) offset(elapsed) else DriftOffset.ZERO
}

enum class IdleState { ACTIVE, DIM, NEAR_BLACK }

/**
 * Idle state from the time of the last key press, on a monotonic millisecond clock (the app passes
 * `SystemClock.elapsedRealtime`). Pure apart from the remembered key time.
 */
class IdleClock(private val profile: TvProfile, private val clockMs: () -> Long) {
    private var lastKeyMs: Long = clockMs()

    /** Call on every key event (any key wakes and resets). */
    fun onKey() { lastKeyMs = clockMs() }

    fun state(): IdleState = stateFor(profile, Duration.ofMillis((clockMs() - lastKeyMs).coerceAtLeast(0)))

    /** Time until the next state change, or null when none is coming (already at the last state). */
    fun nextChangeIn(): Duration? {
        val idle = Duration.ofMillis((clockMs() - lastKeyMs).coerceAtLeast(0))
        return listOfNotNull(profile.dimAfter, profile.nearBlackAfter).firstOrNull { it > idle }?.minus(idle)
    }

    companion object {
        fun stateFor(profile: TvProfile, idle: Duration): IdleState = when {
            profile.nearBlackAfter != null && idle >= profile.nearBlackAfter -> IdleState.NEAR_BLACK
            idle >= profile.dimAfter -> IdleState.DIM
            else -> IdleState.ACTIVE
        }
    }
}

/**
 * Idle-state colours (ADR 0008 section 4). DIM is not a uniform scrim: backdrop and tile fills go to [DIM_FIELD_ALPHA]
 * and text to [DIM_TEXT] (8.1:1 even over a white pixel at 15%). NEAR_BLACK is black with only the clock in
 * [NEAR_BLACK_CLOCK] (7.5:1), drifting. F1 tests every text token's contrast; these are the idle ones.
 */
object OledTokens {
    const val DIM_FIELD_ALPHA = 0.15f
    const val DIM_TEXT = 0xBEBEBE
    const val NEAR_BLACK_BG = 0x000000
    const val NEAR_BLACK_CLOCK = 0x9A9A9A

    /** The backdrop's brightness in DIM (black scrim at 1 - this), against the active [GlanceTokens.BACKDROP_BRIGHTNESS]. */
    const val DIM_BACKDROP_BRIGHTNESS = DIM_FIELD_ALPHA

    /** Banner and icon images on the tiles in DIM. */
    const val DIM_IMAGE_ALPHA = DIM_FIELD_ALPHA

    /** Focus ring in DIM: the focused tile sits still for minutes, so its ring is not left at full brightness. */
    const val DIM_FOCUS_RING = 0x5B7AA8

    /** Near-black clock size (sp): the one thing left on screen. */
    const val NEAR_BLACK_CLOCK_SP = 40
}

/**
 * The home screen's theme in DIM: every text colour goes to [OledTokens.DIM_TEXT] and the focus ring to
 * [OledTokens.DIM_FOCUS_RING]. Fills stay (they are already dark); sizes are unchanged, so nothing moves.
 */
fun HomeTheme.dimmed(): HomeTheme = copy(text = OledTokens.DIM_TEXT, secondaryText = OledTokens.DIM_TEXT, focusRing = OledTokens.DIM_FOCUS_RING)
