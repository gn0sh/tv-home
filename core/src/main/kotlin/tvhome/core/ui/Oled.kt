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

/** When the idle states begin. [of] is the profile's; [test] is the adb QA override (M7-F4 emulator check). */
data class IdleTimings(val dimAfter: Duration, val nearBlackAfter: Duration?) {
    companion object {
        fun of(p: TvProfile) = IdleTimings(p.dimAfter, p.nearBlackAfter)

        /** DIM after [seconds], NEAR_BLACK after twice that (only if the profile has a near-black state). */
        fun test(p: TvProfile, seconds: Int) =
            IdleTimings(Duration.ofSeconds(seconds.toLong()), p.nearBlackAfter?.let { Duration.ofSeconds(2L * seconds) })
    }
}

/**
 * Idle state from the time of the last key press, on a monotonic millisecond clock (the app passes
 * `SystemClock.elapsedRealtime`). Pure apart from the remembered key time.
 */
class IdleClock(private val timings: IdleTimings, private val clockMs: () -> Long) {
    constructor(profile: TvProfile, clockMs: () -> Long) : this(IdleTimings.of(profile), clockMs)

    private var lastKeyMs: Long = clockMs()

    /** Call on every key event (any key wakes and resets). */
    fun onKey() { lastKeyMs = clockMs() }

    fun state(): IdleState = stateFor(timings, Duration.ofMillis((clockMs() - lastKeyMs).coerceAtLeast(0)))

    /** Time until the next state change, or null when none is coming (already at the last state). */
    fun nextChangeIn(): Duration? {
        val idle = Duration.ofMillis((clockMs() - lastKeyMs).coerceAtLeast(0))
        return listOfNotNull(timings.dimAfter, timings.nearBlackAfter).firstOrNull { it > idle }?.minus(idle)
    }

    companion object {
        fun stateFor(profile: TvProfile, idle: Duration): IdleState = stateFor(IdleTimings.of(profile), idle)

        fun stateFor(t: IdleTimings, idle: Duration): IdleState = when {
            t.nearBlackAfter != null && idle >= t.nearBlackAfter -> IdleState.NEAR_BLACK
            idle >= t.dimAfter -> IdleState.DIM
            else -> IdleState.ACTIVE
        }
    }
}

/** What the home screen does with one key event while idle states apply. */
data class KeyDecision(val consume: Boolean, val wake: Boolean)

/**
 * The idle key rules (ADR 0008 section 4), pure. ACTIVE: pass. DIM: wake and pass. NEAR_BLACK: wake; with
 * [wakesOnly] the first key's press is consumed and the gate holds that key, so its repeats and its release are
 * consumed too (OK would otherwise launch the invisible focused tile). While holding, only the held key's repeats
 * (repeatCount > 0) and its release are consumed: a fresh press of it, or any other key, ends the hold and is handled
 * normally, so a lost release can never swallow later keys. [clear] (on a wake from outside: resume, Home) ends it.
 */
class IdleKeyGate(private val wakesOnly: Boolean = true) {
    private var held: Long? = null

    val holding: Boolean get() = held != null

    fun clear() { held = null }

    fun onKey(state: IdleState, keyCode: Long, down: Boolean, repeatCount: Int): KeyDecision {
        val h = held
        if (h != null) {
            if (keyCode == h && !down) { held = null; return KeyDecision(consume = true, wake = false) }
            if (keyCode == h && repeatCount > 0) return KeyDecision(consume = true, wake = false)
            held = null
        }
        if (state == IdleState.ACTIVE) return KeyDecision(consume = false, wake = false)
        if (state == IdleState.NEAR_BLACK && wakesOnly && down) {
            held = keyCode
            return KeyDecision(consume = true, wake = true)
        }
        return KeyDecision(consume = false, wake = true)
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
