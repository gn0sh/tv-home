package com.gnosh.tvhome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.sin
import kotlinx.coroutines.delay
import tvhome.core.ui.HeroMotif
import tvhome.core.ui.HeroTheme
import tvhome.core.ui.Tokens

/** Fraction of the screen height the backdrop covers (under the app row, as in the mockup). */
const val HERO_BACKDROP_FRACTION = 0.55f

/** Prefix of the hero title's content description, read by the emulator QA check (scripts/ci/emulator-qa.sh). */
const val HERO_TITLE_DESC_PREFIX = "Hero title: "

/** What the hero shows for the focused tile. */
const val HERO_SUBTITLE = "Press OK to open"

/**
 * The hero layer: backdrop, title, subtitle, theme badge and clock. Not focusable and no focus targets (home-back-twice).
 * It owns the minute clock, so the theme re-evaluates at each tick without recomposing the home screen. [label] is read
 * only inside [HeroText], so a focus change recomposes just the title, not the backdrop or the clock.
 */
@Composable
fun HeroLayer(label: () -> String, modifier: Modifier = Modifier) {
    val now by rememberMinuteClock()
    val theme = remember(now.toLocalDate(), now.hour) { HeroTheme.at(now.toLocalDate(), now.toLocalTime()) }
    Box(modifier.fillMaxSize()) {
        HeroBackdrop(theme, Modifier.fillMaxWidth().fillMaxHeight(HERO_BACKDROP_FRACTION))
        HeroTopBar(theme, now)
        HeroText(theme, label)
    }
}

/**
 * The current local time. Driven by the system's minute tick and by time and time-zone changes (no `delay`), so it stays
 * right across device sleep, a clock change or a zone change (clock-sleep-stale). The receiver is registered while the
 * hero is composed and unregistered when it leaves. All three actions are protected system broadcasts, so no
 * exported/not-exported flag is needed.
 */
@Composable
private fun rememberMinuteClock(): State<LocalDateTime> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(LocalDateTime.now()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { state.value = LocalDateTime.now() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        state.value = LocalDateTime.now() // catch up on anything missed before registering
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

@Composable
private fun HeroTopBar(theme: HeroTheme, now: LocalDateTime) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val badge = theme.badge
        if (badge != null) {
            Text(
                badge,
                color = Color(theme.backdropTop or OPAQUE),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                modifier = Modifier
                    .background(Color(theme.accent or OPAQUE), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        } else {
            Box(Modifier)
        }
        Text(
            CLOCK_FORMAT.format(now),
            color = Color(theme.text or OPAQUE),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HeroText(theme: HeroTheme, label: () -> String) {
    Column(
        Modifier
            .padding(start = Tokens.MARGIN_H_DP.dp, top = (Tokens.MARGIN_V_DP + 36).dp)
            .widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val title = label()
        Text(
            title,
            color = Color(theme.text or OPAQUE),
            fontSize = 48.sp,
            lineHeight = 52.sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = HERO_TITLE_DESC_PREFIX + title },
        )
        Text(HERO_SUBTITLE, color = Color(theme.secondaryText or OPAQUE), fontSize = 20.sp, maxLines = 1)
    }
}

private const val OPAQUE = 0xFF000000.toInt()

private fun rgb(c: Int, alpha: Float = 1f) = Color(c or OPAQUE).copy(alpha = alpha)

/**
 * Canvas shapes only: no images, no network. Takes just the theme, so it is skipped when the focus changes.
 *
 * - Own `graphicsLayer`, so the animation redraws this layer and not the screen (snow-redraw-scope).
 * - `drawWithCache` builds the brushes and the hill and moon paths once per size and theme, not per frame.
 * - First frame stays cheap: only the gradient and the bottom fade are drawn until the first frame has been shown; the
 *   motif and the animation start after that (cold-start budget, AC8).
 * - Only Christmas animates (slow snowfall). The loop ticks about 15 times a second with a delay, not every vsync, and
 *   does not run at all when the system animator scale is 0 (snow-uiautomator-idle: QA sets that so uiautomator can
 *   reach an idle state; it also honours the user's "remove animations" choice).
 */
@Composable
private fun HeroBackdrop(theme: HeroTheme, modifier: Modifier) {
    val context = LocalContext.current
    val frozen = remember(context) { animationsFrozen(context) }
    var ready by remember { mutableStateOf(false) }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        withFrameNanos { } // the first frame has been drawn with the plain gradient
        ready = true
    }
    val animate = ready && !frozen && theme.motif == HeroMotif.SNOW
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            delay(FRAME_MS)
            tick.longValue = (SystemClock.elapsedRealtime() - t0) * 1_000_000L
        }
    }
    Box(
        modifier
            .graphicsLayer { }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val base = Brush.verticalGradient(listOf(rgb(theme.backdropTop), rgb(theme.backdropBottom)))
                // Fade into the screen background so the tile row sits on a calm base.
                val fade = Brush.verticalGradient(listOf(Color.Transparent, rgb(Tokens.BACKGROUND)), startY = h * 0.6f, endY = h)
                val hill = if (theme.motif == HeroMotif.SNOW) hillPath(w, h) else null
                val moon = if (theme.motif == HeroMotif.STARS) moonPath(w, h) else null
                onDrawBehind {
                    drawRect(base)
                    if (ready) {
                        when (theme.motif) {
                            HeroMotif.MOON_BATS -> drawMoonAndBats(theme)
                            HeroMotif.SNOW -> { drawSnow(tick.longValue); hill?.let { drawPath(it, Color.White.copy(alpha = 0.08f)) } }
                            HeroMotif.DAWN -> drawSun(theme, 0.74f, 0.98f, 0.11f, 0.30f)
                            HeroMotif.DAY -> { drawSun(theme, 0.80f, 0.34f, 0.07f, 0.22f); drawClouds(0.22f) }
                            HeroMotif.SUNSET -> drawSun(theme, 0.74f, 0.88f, 0.14f, 0.34f)
                            HeroMotif.STARS -> drawStars(theme, moon)
                        }
                    }
                    drawRect(fade)
                }
            },
    )
}

/** True when the system animator duration scale is 0 (developer option, or QA via `settings put global`). */
private fun animationsFrozen(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

private const val FRAME_MS = 66L

private fun DrawScope.drawSun(theme: HeroTheme, cx: Float, cy: Float, r: Float, glowAlpha: Float) {
    val c = Offset(size.width * cx, size.height * cy)
    val rad = size.height * r
    drawCircle(rgb(theme.accent, glowAlpha * 0.5f), rad * 2.6f, c)
    drawCircle(rgb(theme.accent, glowAlpha), rad * 1.6f, c)
    drawCircle(rgb(theme.accent, 0.9f), rad, c)
}

private fun DrawScope.drawClouds(alpha: Float) {
    val h = size.height
    fun cloud(cx: Float, cy: Float, s: Float) {
        val base = Offset(size.width * cx, h * cy)
        val r = h * 0.05f * s
        drawCircle(Color.White.copy(alpha = alpha), r, base)
        drawCircle(Color.White.copy(alpha = alpha), r * 1.3f, base + Offset(r * 1.2f, -r * 0.4f))
        drawCircle(Color.White.copy(alpha = alpha), r, base + Offset(r * 2.4f, 0f))
        drawRoundRect(Color.White.copy(alpha = alpha), base + Offset(-r * 0.5f, 0f), Size(r * 3.8f, r * 0.9f),
            androidx.compose.ui.geometry.CornerRadius(r * 0.45f))
    }
    cloud(0.58f, 0.52f, 1f)
    cloud(0.90f, 0.62f, 0.8f)
}

private fun DrawScope.drawStars(theme: HeroTheme, moon: Path?) {
    // Deterministic scatter, kept to the right and upper part away from the title; dim for night.
    for (i in 0 until 46) {
        val x = frac(i * 0.6180339f + 0.13f)
        val y = frac(i * 0.7548776f + 0.31f) * 0.8f
        val a = 0.25f + 0.45f * frac(i * 0.3247f)
        drawCircle(rgb(theme.text, a), size.height * (0.004f + 0.004f * frac(i * 0.57f)), Offset(size.width * (0.4f + 0.6f * x), size.height * y))
    }
    // A quiet crescent moon.
    if (moon != null) drawPath(moon, rgb(theme.text, 0.55f))
}

private fun moonPath(w: Float, h: Float): Path {
    val c = Offset(w * 0.82f, h * 0.40f)
    val r = h * 0.09f
    val moon = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.5f, -r * 0.2f), r * 0.9f)) }
    return Path.combine(PathOperation.Difference, moon, bite)
}

private fun DrawScope.drawMoonAndBats(theme: HeroTheme) {
    val c = Offset(size.width * 0.78f, size.height * 0.46f)
    val r = size.height * 0.16f
    drawCircle(rgb(theme.accent, 0.10f), r * 1.9f, c)
    drawCircle(rgb(theme.accent, 0.16f), r * 1.4f, c)
    drawCircle(rgb(0xF2E3B8, 0.92f), r, c)
    drawCircle(rgb(0xD8C792, 0.55f), r * 0.16f, c + Offset(-r * 0.35f, -r * 0.2f))
    drawCircle(rgb(0xD8C792, 0.55f), r * 0.11f, c + Offset(r * 0.30f, r * 0.30f))
    drawBat(Offset(size.width * 0.70f, size.height * 0.38f), size.height * 0.07f)
    drawBat(Offset(size.width * 0.86f, size.height * 0.30f), size.height * 0.05f)
    drawBat(Offset(size.width * 0.63f, size.height * 0.56f), size.height * 0.04f)
}

private fun DrawScope.drawBat(center: Offset, s: Float) {
    val p = Path().apply {
        moveTo(center.x, center.y - s * 0.25f)
        // right wing, scalloped edge
        quadraticBezierTo(center.x + s * 0.6f, center.y - s * 0.9f, center.x + s * 1.6f, center.y - s * 0.4f)
        quadraticBezierTo(center.x + s * 1.3f, center.y - s * 0.1f, center.x + s * 1.2f, center.y + s * 0.2f)
        quadraticBezierTo(center.x + s * 0.9f, center.y, center.x + s * 0.6f, center.y + s * 0.35f)
        quadraticBezierTo(center.x + s * 0.3f, center.y + s * 0.1f, center.x, center.y + s * 0.5f)
        // left wing, mirrored
        quadraticBezierTo(center.x - s * 0.3f, center.y + s * 0.1f, center.x - s * 0.6f, center.y + s * 0.35f)
        quadraticBezierTo(center.x - s * 0.9f, center.y, center.x - s * 1.2f, center.y + s * 0.2f)
        quadraticBezierTo(center.x - s * 1.3f, center.y - s * 0.1f, center.x - s * 1.6f, center.y - s * 0.4f)
        quadraticBezierTo(center.x - s * 0.6f, center.y - s * 0.9f, center.x, center.y - s * 0.25f)
        close()
    }
    drawPath(p, Color(0xFF0A0610))
    // ears
    drawCircle(Color(0xFF0A0610), s * 0.12f, center + Offset(-s * 0.12f, -s * 0.32f))
    drawCircle(Color(0xFF0A0610), s * 0.12f, center + Offset(s * 0.12f, -s * 0.32f))
}

private fun DrawScope.drawSnow(tickNs: Long) {
    val t = tickNs / 1_000_000_000.0
    for (i in 0 until 48) {
        val speed = 0.03 + 0.05 * frac(i * 0.37f)
        val y = ((frac(i * 0.7548776f + 0.2f) + t * speed) % 1.0).toFloat()
        val sway = sin(t * 0.5 + i).toFloat() * size.width * 0.006f
        val x = size.width * frac(i * 0.6180339f + 0.05f) + sway
        val r = size.height * (0.004f + 0.007f * frac(i * 0.53f))
        drawCircle(Color.White.copy(alpha = 0.35f + 0.4f * frac(i * 0.29f)), r, Offset(x, size.height * y))
    }
}

/** A few static hills along the bottom of the backdrop. */
private fun hillPath(w: Float, h: Float): Path = Path().apply {
    moveTo(0f, h)
    lineTo(0f, h * 0.86f)
    cubicTo(w * 0.25f, h * 0.78f, w * 0.45f, h * 0.92f, w * 0.7f, h * 0.84f)
    cubicTo(w * 0.85f, h * 0.80f, w * 0.95f, h * 0.86f, w, h * 0.83f)
    lineTo(w, h)
    close()
}

private fun frac(x: Float): Float = x - kotlin.math.floor(x)
