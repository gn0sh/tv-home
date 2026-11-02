package com.gnosh.tvhome

import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
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
import androidx.compose.ui.input.key.Key
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
import androidx.tv.material3.Text
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tvhome.core.ui.Drift
import tvhome.core.ui.DriftOffset
import tvhome.core.ui.HeroZones
import tvhome.core.ui.IdleClock
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
    /** Composition state, so the drift loop restarts when the resolved profile arrives. */
    var profile: TvProfile by mutableStateOf(profile)
        private set

    private val _state = mutableStateOf(IdleState.ACTIVE)
    val state: State<IdleState> get() = _state
    val drift: MutableState<DriftOffset> = mutableStateOf(DriftOffset.ZERO)

    /** Bumped when the scheduler must start again (it stops once the last state is reached). */
    internal val restarts = mutableIntStateOf(0)
    private var swallowUp: Key? = null

    /** The resolved profile arrived: start its clock now (counts as a key; it happens just after launch). */
    fun useProfile(p: TvProfile) {
        if (p == profile) return
        profile = p
        clock = IdleClock(p, SystemClock::elapsedRealtime)
        wake()
    }

    /** Any key, a return to the screen, or the M3 Home redirect: full brightness and a fresh idle count. */
    fun wake() {
        clock.onKey()
        if (_state.value != IdleState.ACTIVE) _state.value = IdleState.ACTIVE
        restarts.intValue++
    }

    /** True when the event is consumed (only the first key, down and up, in NEAR_BLACK). */
    fun onPreviewKey(e: KeyEvent): Boolean {
        val held = swallowUp
        if (held != null && e.key == held) {
            if (e.type == KeyEventType.KeyUp) swallowUp = null
            clock.onKey()
            return true
        }
        val was = _state.value
        clock.onKey()
        if (was == IdleState.ACTIVE) return false
        wake()
        if (was == IdleState.NEAR_BLACK && NEAR_BLACK_FIRST_KEY_WAKES_ONLY && e.type == KeyEventType.KeyDown) {
            swallowUp = e.key
            return true
        }
        return false
    }

    /** Runs while composed: moves [state] at each threshold, sleeping until the next one ([IdleClock.nextChangeIn]). */
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
 * (nothing on the first-frame path): the adb `tv_profile` setting, else the TV's EDID PnP id, else LG.
 */
@Composable
fun rememberIdleController(): IdleController {
    val context = LocalContext.current
    val view = LocalView.current
    val idle = remember { IdleController(TvProfile.DEFAULT) }
    LaunchedEffect(Unit) {
        val p = withContext(Dispatchers.IO) { runCatching { resolveProfile(context, view) }.getOrDefault(TvProfile.DEFAULT) }
        idle.useProfile(p)
    }
    LaunchedEffect(idle.restarts.intValue) { idle.schedule() }
    LaunchedEffect(idle.profile) { idle.driftLoop() }
    // Coming back to the screen (Back from an app, the M3 Home redirect) is never met by a dim or black screen.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) idle.wake() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return idle
}

private val pnpLogged = AtomicBoolean(false)

private fun resolveProfile(context: Context, view: View): TvProfile {
    val pnp = runCatching { view.display?.deviceProductInfo?.manufacturerPnpId }.getOrNull()
    // A-M7-6: the EDID manufacturer id, logged once per process at debug level. A three-letter code, never more.
    if (pnpLogged.compareAndSet(false, true)) {
        Log.d("TvHome", "display pnp=" + (pnp?.takeIf { PNP.matches(it) } ?: "none"))
    }
    return TvProfile.resolve(FeedSettings.tvProfile(context), pnp)
}

private val PNP = Regex("[A-Z]{3}")

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
