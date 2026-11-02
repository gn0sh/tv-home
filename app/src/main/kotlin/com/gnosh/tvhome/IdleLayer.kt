package com.gnosh.tvhome

import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tvhome.core.ui.Drift
import tvhome.core.ui.DriftOffset
import tvhome.core.ui.HeroZones
import tvhome.core.ui.IdleClock
import tvhome.core.ui.IdleKeyGate
import tvhome.core.ui.IdleTimings
import tvhome.core.ui.IdleState
import tvhome.core.ui.OledTokens
import tvhome.core.ui.TvProfile
import tvhome.core.ui.formatTime

/** Content description of the near-black clock, read by the emulator QA check. */
const val NEAR_BLACK_DESC = "Screen resting"

/**
 * M7-F4 burn-in protection on the home screen (ADR 0008 section 4). [state] follows [IdleClock] on
 * `SystemClock.elapsedRealtime`; [drift] is the slow offset for the top strip and the hero text. All composition
 * state: nothing here holds the screen on or touches the M3 Home redirect (HomeWatchService watches activities).
 *
 * Keys arrive through [onPreviewKey] on the home screen's root (a key handler, not a focus target, so no
 * home-back-twice). ACTIVE and DIM: the key resets the clock and passes through (in DIM it also restores). NEAR_BLACK:
 * the first key only wakes the screen and is consumed, with its release, because the focused tile is invisible and OK
 * would otherwise launch an app blind ([NEAR_BLACK_FIRST_KEY_WAKES_ONLY], flagged to the PM and ux).
 */
class IdleController(profile: TvProfile) {
    private var clock = IdleClock(profile, SystemClock::elapsedRealtime)
    private val gate = IdleKeyGate(NEAR_BLACK_FIRST_KEY_WAKES_ONLY)

    /** Composition state, so the drift loop restarts when the resolved profile arrives. */
    var profile: TvProfile by mutableStateOf(profile)
        private set

    private val _state = mutableStateOf(IdleState.ACTIVE)
    val state: State<IdleState> get() = _state
    val drift: MutableState<DriftOffset> = mutableStateOf(DriftOffset.ZERO)

    /** Bumped when the scheduler must start again (it stops once the last state is reached). */
    internal val restarts = mutableIntStateOf(0)

    /** The resolved profile (and the QA override, if set) arrived: start its clock now. */
    fun useProfile(p: TvProfile, testSeconds: Int?) {
        profile = p
        clock = IdleClock(if (testSeconds != null) IdleTimings.test(p, testSeconds) else IdleTimings.of(p), SystemClock::elapsedRealtime)
        wake()
    }

    /** A return to the screen or the M3 Home redirect: full brightness, a fresh idle count, no key held. */
    fun wake() {
        gate.clear()
        restore()
    }

    private fun restore() {
        clock.onKey()
        if (_state.value != IdleState.ACTIVE) _state.value = IdleState.ACTIVE
        restarts.intValue++
    }

    /** True when the event is consumed ([IdleKeyGate]: only the first key in NEAR_BLACK, its repeats and release). */
    fun onPreviewKey(e: KeyEvent): Boolean {
        val d = gate.onKey(_state.value, e.key.keyCode, e.type == KeyEventType.KeyDown, e.nativeKeyEvent.repeatCount)
        if (d.wake) restore() else clock.onKey()
        return d.consume
    }

    /** Moves [state] at each threshold, sleeping until the next one ([IdleClock.nextChangeIn]). */
    internal suspend fun schedule() {
        while (true) {
            val s = clock.state()
            if (_state.value != s) _state.value = s
            val wait = clock.nextChangeIn() ?: return
            delay(wait.toMillis() + SCHEDULE_SLACK_MS)
        }
    }

    internal suspend fun driftLoop() {
        if (!profile.drift) { drift.value = DriftOffset.ZERO; return }
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            drift.value = Drift.offsetFor(profile, Duration.ofMillis(SystemClock.elapsedRealtime() - t0))
            delay(DRIFT_STEP_MS)
        }
    }

    companion object {
        /** ADR 0008 section 4 deviation from spec M7-5.2: one constant to flip. */
        const val NEAR_BLACK_FIRST_KEY_WAKES_ONLY = true

        /** Drift is redrawn every 20 s: at most about 0.6 dp per step (Drift), so no visible jump. */
        const val DRIFT_STEP_MS = 20_000L
        private const val SCHEDULE_SLACK_MS = 50L
    }
}

/**
 * The idle controller for the home screen. The profile is resolved off the main thread after the first composition
 * (nothing on the first-frame path): the adb `tv_profile` setting, else the TV's EDID PnP id, else LG. The scheduler and
 * the drift loop run only while the activity is STARTED (ADR 0008 section 1: nothing runs while Brian watches another
 * app), and a resume wakes the screen.
 */
@Composable
fun rememberIdleController(): IdleController {
    val context = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val idle = remember { IdleController(TvProfile.DEFAULT) }
    LaunchedEffect(Unit) {
        val (p, test) = withContext(Dispatchers.IO) {
            runCatching { resolveProfile(context, view) to FeedSettings.idleTestSeconds(context) }
                .getOrDefault(TvProfile.DEFAULT to null)
        }
        idle.useProfile(p, test)
    }
    LaunchedEffect(idle.restarts.intValue, owner) { owner.repeatOnLifecycle(Lifecycle.State.STARTED) { idle.schedule() } }
    LaunchedEffect(idle.profile, owner) { owner.repeatOnLifecycle(Lifecycle.State.STARTED) { idle.driftLoop() } }
    // Coming back to the screen (Back from an app, the M3 Home redirect) is never met by a dim or black screen.
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) idle.wake() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return idle
}

private fun resolveProfile(context: Context, view: View): TvProfile {
    val pnp = runCatching { view.display?.deviceProductInfo?.manufacturerPnpId }.getOrNull()
    // A-M7-6: the EDID manufacturer id picks the profile. Not logged (app code has no logging); check it on the TV with `adb shell dumpsys display`.
    return TvProfile.resolve(FeedSettings.tvProfile(context), pnp)
}


/**
 * NEAR_BLACK: the whole screen black, with only the clock in [OledTokens.NEAR_BLACK_CLOCK], drifting, at the top strip's
 * place. Drawn over the tiles; not focusable, so focus stays on the tile under it.
 */
@Composable
fun NearBlackLayer(drift: State<DriftOffset>) {
    val now by rememberMinuteClock()
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    val clock = formatTime(now.toLocalTime().withSecond(0).withNano(0), is24h)
    Box(Modifier.fillMaxSize().background(Color(0xFF000000.toInt() or OledTokens.NEAR_BLACK_BG))) {
        Text(
            clock,
            color = Color(0xFF000000.toInt() or OledTokens.NEAR_BLACK_CLOCK),
            fontSize = OledTokens.NEAR_BLACK_CLOCK_SP.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .offset(HeroZones.TOP_STRIP.x.dp, HeroZones.TOP_STRIP.y.dp)
                .graphicsLayer {
                    val o = drift.value
                    translationX = o.dx.dp.toPx()
                    translationY = o.dy.dp.toPx()
                }
                .semantics { contentDescription = NEAR_BLACK_DESC },
        )
    }
}
