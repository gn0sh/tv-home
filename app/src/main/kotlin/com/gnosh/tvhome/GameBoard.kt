package com.gnosh.tvhome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tvhome.core.ui.BoardPalette
import tvhome.core.ui.BoardTokens
import tvhome.core.ui.ContextCard
import tvhome.core.ui.GlanceTokens
import tvhome.core.ui.HeroZones
import tvhome.core.ui.OledTokens
import tvhome.core.ui.Scoreboard
import tvhome.core.ui.Ticket
import tvhome.core.ui.TicketText

/** Test tags (uiautomator resource-ids on the home screen) of the game-day surface and the pick stub. */
const val HERO_BOARD_TAG = "hero_board"
const val HERO_STUB_TAG = "hero_pick_stub"

/** Content description of the full ticket's dialog, read by the emulator QA check. */
const val PICK_TICKET_DESC = "Claude's pick, full ticket"

/** The platform's condensed sans (no font file in the app), at the weights the board uses. */
private val CONDENSED: FontFamily by lazy {
    val name = DeviceFontFamilyName("sans-serif-condensed")
    FontFamily(Font(name, FontWeight.Bold), Font(name, FontWeight.ExtraBold), Font(name, FontWeight.Black))
}

private const val OPAQUE = 0xFF000000.toInt()

private fun rgb(c: Int, alpha: Float = 1f) = Color(c or OPAQUE).copy(alpha = alpha)

/** The stub row's text follows the system font scale only this far: the row is 44 dp tall whatever the scale. */
private const val STUB_MAX_FONT_SCALE = 1.15f

/**
 * The game-day board (docs/design/tv-home-game-day-scoreboard.md): the surface, and under it the pick stub and the meta
 * line. It replaces the text card while the card has a [Scoreboard] and the zones have room. The surface and the row
 * are not focusable; the stub is the hero's one focus target, a leaf with no focusable parent (home-back-twice), and
 * the full ticket is a dialog, its own window. Everything drifts with the hero text ([drifting]). In DIM ([dim]) it is
 * outline and lettering only and the stub is not a focus target.
 */
@Composable
internal fun HeroGameBoard(
    card: ContextCard.Game,
    board: Scoreboard,
    palette: BoardPalette,
    zones: HeroZones.GameLayout,
    dim: Boolean,
    textColor: Int,
    drifting: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    // The dialog does not outlive the active state: nothing bright is left up while the screen dims.
    LaunchedEffect(dim) { if (dim) open = false }
    val ticket = card.ticket.takeIf { !dim && card.pick != null }
    if (open && ticket != null) TicketDialog(ticket, palette) { open = false }
    // Not clipped: the surface's shadow and the stub's focus scale and glow reach a little past the zone.
    Column(drifting.offset(zones.board.x.dp, zones.board.y.dp).size(zones.board.w.dp, zones.board.h.dp)) {
        BoardSurface(
            board, palette, dim,
            Modifier
                .fillMaxWidth()
                .height(zones.surface.h.dp)
                .testTag(HERO_BOARD_TAG)
                // The lettering is a graphic: the card's whole sentence is what a screen reader gets.
                .semantics { contentDescription = HERO_CARD_DESC_PREFIX + card.line },
        )
        Spacer(Modifier.height(HeroZones.BOARD_GAP_DP.dp))
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, STUB_MAX_FONT_SCALE))) {
            Row(Modifier.fillMaxWidth().height(HeroZones.BOARD_STUB_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                card.pick?.let { PickStub(it, palette, dim, onOpen = if (ticket != null) ({ open = true }) else null) }
                Row(
                    Modifier.weight(1f).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (board.live && !dim) {
                        Box(Modifier.padding(end = 10.dp).size(14.dp).background(rgb(BoardTokens.LAMP), CircleShape))
                    }
                    Text(
                        board.meta, color = rgb(textColor), fontSize = 26.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// --- The surface ---------------------------------------------------------------------------------------------------

/** Horizontal room for one abbreviation and one score, in viewBox x units (0..[BoardTokens.W]) from the left edge. */
private class Lettering(val abbrFrom: Float, val abbrTo: Float, val scoreFrom: Float, val timeWidth: Float)

/** Rink: between the goal line and the blue line; the score between the blue line and the centre line. */
private val RINK_LETTERING = Lettering(52f, 233f, 249f, 92f)

/** Field: between the end zone and the centre box. */
private val FIELD_TIME_LETTERING = Lettering(62f, 270f, 276f, 112f)

/** Field with a score (no centre box): the abbreviation gives the score room beside the midfield line. */
private val FIELD_SCORE_LETTERING = Lettering(62f, 236f, 244f, 112f)

/**
 * The playing surface from above, drawn in one pass (geometry: mockup.html `rinkSvg` and `field`, viewBox 676 x 150).
 * Horizontal positions scale with the width, line weights and type with the height, so a shorter zone keeps the
 * proportions of the lettering. Type sizes ignore the system font scale: it is a graphic. Not focusable.
 */
@Composable
private fun BoardSurface(b: Scoreboard, p: BoardPalette, dim: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val fixed = remember(density) { Density(density, 1f) }
    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val sx = w / BoardTokens.W
            val u = h / BoardTokens.H
            val score = b.center as? Scoreboard.Center.Score
            val rink = b.surface == Scoreboard.Surface.RINK
            val box = if (rink) RINK_LETTERING else if (score != null) FIELD_SCORE_LETTERING else FIELD_TIME_LETTERING

            fun measure(text: String, sizeU: Float, weight: FontWeight, spacingU: Float): TextLayoutResult = measurer.measure(
                text = text,
                style = TextStyle(
                    fontFamily = CONDENSED, fontWeight = weight,
                    fontSize = (sizeU * u / density).sp, letterSpacing = (spacingU * u / density).sp,
                ),
                softWrap = false, maxLines = 1, density = fixed,
            )
            /** Shrinks to fit [maxPx] (a four-letter abbreviation, a three-digit score). */
            fun fitted(text: String, sizeU: Float, weight: FontWeight, spacingU: Float, maxPx: Float): TextLayoutResult {
                val full = measure(text, sizeU, weight, spacingU)
                if (full.size.width <= maxPx || full.size.width == 0) return full
                return measure(text, sizeU * maxPx / full.size.width * 0.98f, weight, spacingU * maxPx / full.size.width)
            }
            val abbrMax = (box.abbrTo - box.abbrFrom) * sx
            val own = fitted(b.own, BoardTokens.ABBR_SIZE, FontWeight.Black, 3f, abbrMax)
            val opp = fitted(b.opp, BoardTokens.ABBR_SIZE, FontWeight.Black, 3f, abbrMax)
            val scoreMax = (BoardTokens.W / 2 - BoardTokens.SCORE_GAP - box.scoreFrom) * sx
            val ownScore = score?.let { fitted(it.own.toString(), BoardTokens.SCORE_SIZE, FontWeight.Black, 0f, scoreMax) }
            val oppScore = score?.let { fitted(it.opp.toString(), BoardTokens.SCORE_SIZE, FontWeight.Black, 0f, scoreMax) }
            val time = (b.center as? Scoreboard.Center.Time)?.let { fitted(it.time, BoardTokens.TIME_SIZE, FontWeight.ExtraBold, 0f, box.timeWidth * sx) }
            val ampm = (b.center as? Scoreboard.Center.Time)?.ampm?.let { measure(it, BoardTokens.AMPM_SIZE, FontWeight.Bold, 2f) }

            val inset = 2f * u
            val radius = minOf((if (rink) BoardTokens.RINK_RADIUS else BoardTokens.FIELD_RADIUS) * u, h / 2 - inset)
            val outline = Path().apply { addRoundRect(RoundRect(inset, inset, w - inset, h - inset, CornerRadius(radius))) }
            val marks = if (rink && !dim) iceMarks(sx, u) else null
            val paint = if (rink) {
                Brush.verticalGradient(0f to rgb(BoardTokens.RINK_TOP), BoardTokens.RINK_MID_AT to rgb(BoardTokens.RINK_MID), 1f to rgb(BoardTokens.RINK_BOTTOM))
            } else {
                Brush.verticalGradient(listOf(rgb(BoardTokens.FIELD_TOP), rgb(BoardTokens.FIELD_BOTTOM)))
            }
            val centre = Offset(w / 2, h / 2)
            val vignetteTo = if (rink) rgb(0x020C22, 0.45f) else rgb(0x050A06, 0.5f)
            val vignette = Brush.radialGradient(0.6f to Color.Transparent, 1f to vignetteTo, center = centre, radius = 0.75f * h)
            val sheen = Brush.linearGradient(
                0f to Color.Transparent, 0.42f to Color.Transparent, 0.5f to Color.White.copy(alpha = BoardTokens.SHEEN_ALPHA),
                0.58f to Color.Transparent, 1f to Color.Transparent, start = Offset.Zero, end = Offset(w, h),
            )
            val ink = if (dim) rgb(OledTokens.DIM_TEXT) else null

            onDrawBehind {
                if (dim) {
                    // DIM: the outline and the lettering in the dim grey; no fills, no glow.
                    drawPath(outline, rgb(OledTokens.DIM_TEXT), style = Stroke(2f * u))
                } else {
                    drawSurfaceShadow(inset, radius, u)
                    drawPath(outline, paint)
                    clipPath(outline) {
                        if (rink) drawRinkLines(sx, u, circle = score == null) else drawFieldLines(p, sx, u)
                        // Edge vignette: an ellipse 0.75 of the width and height.
                        scale(w / h, 1f, pivot = centre) { drawCircle(vignette, 0.75f * h, centre) }
                        if (rink) {
                            drawRect(sheen)
                            if (marks != null) drawPath(marks, Color.White.copy(alpha = 0.05f), style = Stroke(1.5f * u))
                        }
                    }
                    drawPath(outline, rgb(p.boards), style = Stroke(3.5f * u))
                    if (rink) {
                        val i = 7.5f * u
                        drawRoundRect(
                            Color.White.copy(alpha = 0.16f), Offset(i, i), Size(w - 2 * i, h - 2 * i),
                            CornerRadius((radius - 5.5f * u).coerceAtLeast(0f)), style = Stroke(1f * u),
                        )
                    } else if (score == null) {
                        val bw = BoardTokens.CENTRE_BOX_W * sx
                        val bh = BoardTokens.CENTRE_BOX_H * u
                        val at = Offset(centre.x - bw / 2, centre.y - bh / 2)
                        drawRoundRect(rgb(BoardTokens.CENTRE_BOX), at, Size(bw, bh), CornerRadius(12f * u))
                        drawRoundRect(Color.White.copy(alpha = 0.5f), at, Size(bw, bh), CornerRadius(12f * u), style = Stroke(2f * u))
                    }
                }
                fun text(l: TextLayoutResult, x: Float, baselineU: Float, colour: Color) =
                    drawText(l, color = ink ?: colour, topLeft = Offset(x, baselineU * u - l.firstBaseline))
                val abbrMid = (box.abbrFrom + box.abbrTo) / 2 * sx
                text(own, abbrMid - own.size.width / 2f, BoardTokens.ABBR_BASELINE, rgb(p.own))
                text(opp, w - abbrMid - opp.size.width / 2f, BoardTokens.ABBR_BASELINE, rgb(p.opp))
                val white = rgb(BoardTokens.CENTER_TEXT)
                if (ownScore != null && oppScore != null) {
                    text(ownScore, centre.x - BoardTokens.SCORE_GAP * sx - ownScore.size.width, BoardTokens.SCORE_BASELINE, white)
                    text(oppScore, centre.x + BoardTokens.SCORE_GAP * sx, BoardTokens.SCORE_BASELINE, white)
                } else if (time != null) {
                    text(time, centre.x - time.size.width / 2f, if (ampm != null) BoardTokens.TIME_BASELINE else BoardTokens.TIME_ALONE_BASELINE, white)
                    if (ampm != null) text(ampm, centre.x - ampm.size.width / 2f, BoardTokens.AMPM_BASELINE, white)
                }
            }
        },
    )
}

/**
 * A soft shadow under the surface (the mockup's 14 px drop shadow), as a few stacked translucent shapes. The outer ring
 * grows no more than the inset plus 6 dp, so it reaches at most 6 dp past the board zone's sides and stays inside the
 * safe area with drift (safe-area/board-shadow).
 */
private fun DrawScope.drawSurfaceShadow(inset: Float, radius: Float, u: Float) {
    val colour = Color(0xFF010A1C).copy(alpha = 0.08f)
    val step = minOf(2.5f * u, (inset + 6.dp.toPx()) / 6)
    for (i in 1..6) {
        val grow = i * step
        drawRoundRect(
            colour, Offset(inset - grow, inset + 10f * u - grow), Size(size.width - 2 * inset + 2 * grow, size.height - 2 * inset + 2 * grow),
            CornerRadius(radius + grow),
        )
    }
}

/** Blue lines at 36% and 64%, the red centre line (broken by the centre circle while no score shows), goal lines and creases. */
private fun DrawScope.drawRinkLines(sx: Float, u: Float, circle: Boolean) {
    val w = size.width
    val h = size.height
    val blue = rgb(BoardTokens.BLUE_LINE, 0.75f)
    val red = rgb(BoardTokens.RED_LINE, 0.9f)
    val goal = rgb(BoardTokens.RED_LINE, 0.6f)
    drawLine(blue, Offset(w * 0.36f, 0f), Offset(w * 0.36f, h), strokeWidth = 5f * u)
    drawLine(blue, Offset(w * 0.64f, 0f), Offset(w * 0.64f, h), strokeWidth = 5f * u)
    if (circle) {
        val r = BoardTokens.CENTRE_CIRCLE_R * u
        drawLine(red, Offset(w / 2, 0f), Offset(w / 2, h / 2 - r), strokeWidth = 4f * u)
        drawLine(red, Offset(w / 2, h / 2 + r), Offset(w / 2, h), strokeWidth = 4f * u)
        drawCircle(blue, r, Offset(w / 2, h / 2), style = Stroke(3f * u))
    } else {
        drawLine(red, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 4f * u)
    }
    val gx = BoardTokens.GOAL_LINE_X * sx
    val cr = BoardTokens.CREASE_R * u
    drawLine(goal, Offset(gx, 0f), Offset(gx, h), strokeWidth = 2f * u)
    drawLine(goal, Offset(w - gx, 0f), Offset(w - gx, h), strokeWidth = 2f * u)
    // The creases open towards centre ice.
    drawArc(goal, -90f, 180f, false, Offset(gx - cr, h / 2 - cr), Size(2 * cr, 2 * cr), style = Stroke(2f * u))
    drawArc(goal, 90f, 180f, false, Offset(w - gx - cr, h / 2 - cr), Size(2 * cr, 2 * cr), style = Stroke(2f * u))
}

/** Three faint skate marks on the ice (the mockup's 5% white curves). */
private fun iceMarks(sx: Float, u: Float): Path = Path().apply {
    moveTo(120f * sx, 150f * u); quadraticBezierTo(210f * sx, 80f * u, 350f * sx, 110f * u)
    moveTo(380f * sx, 0f); quadraticBezierTo(440f * sx, 60f * u, 570f * sx, 44f * u)
    moveTo(470f * sx, 150f * u); quadraticBezierTo(510f * sx, 100f * u, 620f * sx, 90f * u)
}

/** Alternating 10-yard bands, the end zones (ours left, in the team colour), yard lines (midfield heavier) and hash marks. */
private fun DrawScope.drawFieldLines(p: BoardPalette, sx: Float, u: Float) {
    val w = size.width
    val h = size.height
    val ez = BoardTokens.END_ZONE_W * sx
    val yard = BoardTokens.YARD_10 * sx
    for (i in 0..8 step 2) drawRect(Color.White.copy(alpha = BoardTokens.BAND_ALPHA), Offset(ez + i * yard, 0f), Size(yard, h))
    drawRect(rgb(p.endZone), Offset.Zero, Size(ez, h))
    drawRect(rgb(BoardTokens.FAR_END_ZONE), Offset(w - ez, 0f), Size(ez, h))
    for (i in 0..10) {
        val x = ez + i * yard
        val mid = i == 5
        drawLine(Color.White.copy(alpha = if (mid) 0.8f else 0.4f), Offset(x, 0f), Offset(x, h), strokeWidth = (if (mid) 3f else 1.5f) * u)
    }
    val hash = Color.White.copy(alpha = 0.3f)
    for (i in 1..49) {
        val x = ez + i * yard / 5
        drawLine(hash, Offset(x, h * 0.36f), Offset(x, h * 0.36f + 6f * u), strokeWidth = 1f * u)
        drawLine(hash, Offset(x, h * 0.64f - 6f * u), Offset(x, h * 0.64f), strokeWidth = 1f * u)
    }
}

// --- The stub ------------------------------------------------------------------------------------------------------

/**
 * The pick as a ticket stub: [text] is exactly `Picks.text`, split at its first ": " into the label and the pick. A
 * leaf focus target while [onOpen] is set (never in DIM): focused, it fills with the accent, grows 1.07 from its left
 * edge and glows; OK calls [onOpen]. No press or focus overlay of its own (the fill is the indication).
 */
@Composable
private fun PickStub(text: String, p: BoardPalette, dim: Boolean, onOpen: (() -> Unit)?) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(onOpen == null) { if (onOpen == null) focused = false }
    val lit = focused && !dim && onOpen != null
    val cut = text.indexOf(": ")
    val label = if (cut >= 0) text.substring(0, cut + 1) else null
    val pick = if (cut >= 0) text.substring(cut + 2) else text
    val accent = rgb(if (dim) OledTokens.DIM_TEXT else if (lit) p.focusInk else p.stubAccent)
    val ink = rgb(if (dim) OledTokens.DIM_TEXT else if (lit) p.focusInk else p.stubText)
    val fill = if (dim) null else rgb(if (lit) p.focusFill else p.stubFill)
    val edge = rgb(if (dim) OledTokens.DIM_TEXT else if (lit) p.focusFill else p.stubAccent)
    val glow = if (lit) rgb(p.focusFill, 0.09f) else null
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .widthIn(max = 520.dp)
            .graphicsLayer {
                val s = if (lit) 1.07f else 1f
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .testTag(HERO_STUB_TAG)
            .then(
                if (onOpen == null) Modifier
                else Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
            )
            .semantics { contentDescription = text }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val half = 1.dp.toPx()
                val r = 9.dp.toPx()
                val body = Path().apply { addRoundRect(RoundRect(half, half, w - half, h - half, CornerRadius(r))) }
                val notches = Path().apply {
                    addOval(Rect(Offset(0f, h / 2), r))
                    addOval(Rect(Offset(w, h / 2), r))
                }
                val shape = Path.combine(PathOperation.Difference, body, notches)
                val step = 3.dp.toPx()
                onDrawBehind {
                    if (glow != null) {
                        for (i in 1..6) {
                            drawRoundRect(glow, Offset(-i * step, -i * step), Size(w + 2 * i * step, h + 2 * i * step), CornerRadius(r + i * step))
                        }
                    }
                    if (fill != null) drawPath(shape, fill)
                    drawPath(shape, edge, style = Stroke(2.dp.toPx()))
                }
            }
            .height(HeroZones.BOARD_STUB_DP.dp)
            .padding(start = 22.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("✦", color = accent, fontSize = 24.sp, maxLines = 1)
        if (label != null) {
            Text(label, color = accent, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            // The dashed tear line between the label and the pick.
            Box(
                Modifier.padding(start = 4.dp).width(2.dp).fillMaxHeight().drawWithCache {
                    val pad = 3.dp.toPx()
                    val dashes = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
                    val tear = accent.copy(alpha = 0.6f)
                    onDrawBehind {
                        drawLine(
                            tear, Offset(size.width / 2, pad), Offset(size.width / 2, size.height - pad),
                            strokeWidth = size.width, pathEffect = dashes,
                        )
                    }
                },
            )
        }
        Text(
            pick, color = ink, fontFamily = CONDENSED, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp),
        )
    }
}

// --- The full ticket -----------------------------------------------------------------------------------------------

private const val TICKET_W_DP = 1060
private const val TICKET_STUB_DP = 286

/** The ticket never leaves the 5% safe area: 720 less 36 above and below. */
private const val TICKET_MAX_H_DP = HeroZones.SCREEN_H - 2 * HeroZones.SAFE_Y
private const val TICKET_BODY_PAD_H_DP = 44
private const val TICKET_PARA_GAP_DP = 14

/**
 * Body type, largest first: the design's 24 sp on 34, then tighter steps down to the 22 sp floor. The first that lets
 * the whole ticket fit [TICKET_MAX_H_DP] is used, so the longest pick needs no scrolling at font scale 1.0; when none
 * fits (a large font scale), the last is used and the body scrolls with the D-pad.
 */
private val TICKET_BODY_STYLES = listOf(
    TextStyle(fontSize = 24.sp, lineHeight = 34.sp),
    TextStyle(fontSize = 24.sp, lineHeight = 31.sp),
    TextStyle(fontSize = GlanceTokens.MIN_SP.sp, lineHeight = 30.sp),
    TextStyle(fontSize = GlanceTokens.MIN_SP.sp, lineHeight = 28.sp),
)
private val TICKET_TITLE_STYLE = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
private val TICKET_STAKES_STYLE = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
private val TICKET_CLOSE_STYLE = TextStyle(fontSize = GlanceTokens.MIN_SP.sp, lineHeight = 32.sp)

/** The largest size up to [maxSp] at which [text] fits [maxPx] on one line (never under a third of it). */
private fun fitSp(measurer: TextMeasurer, text: String, style: TextStyle, maxSp: Float, maxPx: Float, density: Density): TextUnit {
    val width = measurer.measure(text, style.copy(fontSize = maxSp.sp), softWrap = false, maxLines = 1, density = density).size.width
    return if (width <= maxPx || width == 0) maxSp.sp else (maxSp * maxPx / width * 0.98f).coerceAtLeast(maxSp / 3).sp
}

/**
 * The pick as a full ticket (design section 5), in its own window over an 80% veil: the stub in the team colour (the
 * pick, its line, the short reason), a perforation, and the write-up with the stakes as the footer. Back or OK closes;
 * Up and Down scroll the body when it does not fit.
 */
@Composable
private fun TicketDialog(t: Ticket, p: BoardPalette, onDismiss: () -> Unit) {
    // A dialog is a new window whose composition takes LocalDensity from the real window, not from the design canvas:
    // the canvas density is captured here and provided again inside, so the ticket's dp and sp are canvas units.
    val canvas = LocalDensity.current
    // Not the platform default width: the window wraps the content, up to the screen.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides canvas) { TicketContent(t, p, onDismiss) }
    }
}

/** The ticket itself, composed inside the dialog window under the design canvas density. */
@Composable
private fun TicketContent(t: Ticket, p: BoardPalette, onDismiss: () -> Unit) {
    // The veil is the dialog window's own dim, so nothing of the app's is drawn over the home screen.
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(BoardTokens.VEIL_ALPHA) }
    val d = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var okDown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val text = rgb(GlanceTokens.TICKET_TEXT)
    val secondary = rgb(GlanceTokens.HERO_SECONDARY)
    val stubInk = rgb(p.ticketInk)

    val bodyStyle = remember(t, d.density, d.fontScale) {
        with(d) {
            val bodyW = (TICKET_W_DP - TICKET_STUB_DP - 2 * TICKET_BODY_PAD_H_DP).dp.roundToPx()
            fun height(s: String, style: TextStyle, width: Int) =
                measurer.measure(s, style, constraints = Constraints(maxWidth = width.coerceAtLeast(1)), density = d).size.height
            val close = measurer.measure(TicketText.CLOSE, TICKET_CLOSE_STYLE, softWrap = false, maxLines = 1, density = d).size
            val footer = t.stakes?.let { height(it, TICKET_STAKES_STYLE, bodyW - close.width - 24.dp.roundToPx()) } ?: close.height
            // Padding 32 + 28, the gap under the title 14, the footer's 18 + 2 + 14.
            val room = TICKET_MAX_H_DP.dp.toPx() - (32 + 28 + TICKET_PARA_GAP_DP + 18 + 2 + 14).dp.toPx() -
                height(t.title, TICKET_TITLE_STYLE, bodyW) - footer
            val gaps = (t.paragraphs.size - 1).coerceAtLeast(0) * TICKET_PARA_GAP_DP.dp.toPx()
            TICKET_BODY_STYLES.firstOrNull { style -> t.paragraphs.sumOf { height(it, style, bodyW) } + gaps <= room }
                ?: TICKET_BODY_STYLES.last()
        }
    }
    val stubTextW = with(d) { (TICKET_STUB_DP - 36 - 24).dp.toPx() }
    val bigStyle = TextStyle(fontFamily = CONDENSED, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    val lineStyle = TextStyle(fontFamily = CONDENSED, fontWeight = FontWeight.ExtraBold)
    val bigSp = remember(t.big, d.density, d.fontScale) { fitSp(measurer, t.big, bigStyle, 132f, stubTextW, d) }
    val lineSp = remember(t.line, d.density, d.fontScale) { t.line?.let { fitSp(measurer, it, lineStyle, 60f, stubTextW, d) } }
    val step = with(d) { 96.dp.toPx() }

    Row(
        Modifier
            .width(TICKET_W_DP.dp)
            .heightIn(max = TICKET_MAX_H_DP.dp)
            .height(IntrinsicSize.Min)
            .semantics { contentDescription = PICK_TICKET_DESC }
            .focusRequester(focus)
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                val down = e.type == KeyEventType.KeyDown
                when {
                    // OK closes on its release, and only when its press was seen here (not the press that opened it).
                    ok && down -> { okDown = true; true }
                    ok && e.type == KeyEventType.KeyUp -> { if (okDown) onDismiss(); true }
                    down && e.key == Key.DirectionDown -> { if (scroll.value < scroll.maxValue) scope.launch { scroll.animateScrollBy(step) }; true }
                    down && e.key == Key.DirectionUp -> { if (scroll.value > 0) scope.launch { scroll.animateScrollBy(-step) }; true }
                    else -> false
                }
            }
            .focusable()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val split = TICKET_STUB_DP.dp.toPx()
                val notch = 13.dp.toPx()
                val whole = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(18.dp.toPx()))) }
                val notches = Path().apply {
                    addOval(Rect(Offset(split, 0f), notch))
                    addOval(Rect(Offset(split, h), notch))
                }
                val shape = Path.combine(PathOperation.Difference, whole, notches)
                val perforation = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f)
                onDrawBehind {
                    clipPath(shape) {
                        drawRect(rgb(p.ticketBody))
                        drawRect(rgb(p.ticketStub), Offset.Zero, Size(split, h))
                        drawLine(
                            rgb(p.focusInk, 0.9f), Offset(split, notch), Offset(split, h - notch),
                            strokeWidth = 3.dp.toPx(), pathEffect = perforation,
                        )
                    }
                }
            },
    ) {
        Column(Modifier.width(TICKET_STUB_DP.dp).fillMaxHeight().padding(start = 36.dp, top = 32.dp, end = 24.dp, bottom = 30.dp)) {
            Text("✦ " + TicketText.LABEL, color = stubInk, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            Text(
                t.big, color = stubInk, style = bigStyle.copy(fontSize = bigSp, lineHeight = bigSp * 0.9f),
                maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 14.dp),
            )
            if (t.line != null && lineSp != null) {
                Text(
                    t.line.orEmpty(), color = stubInk, style = lineStyle.copy(fontSize = lineSp, lineHeight = lineSp),
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            t.short?.let { short ->
                Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(2.dp).background(stubInk.copy(alpha = 0.35f)))
                Text(
                    short, color = stubInk, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .padding(start = TICKET_BODY_PAD_H_DP.dp, top = 32.dp, end = TICKET_BODY_PAD_H_DP.dp, bottom = 28.dp),
        ) {
            val cut = t.title.lastIndexOf(" · ")
            Text(
                buildAnnotatedString {
                    append(if (cut >= 0) t.title.substring(0, cut) else t.title)
                    if (cut >= 0) withStyle(SpanStyle(color = secondary, fontWeight = FontWeight.Normal)) { append(t.title.substring(cut)) }
                },
                color = text, style = TICKET_TITLE_STYLE,
            )
            Column(
                Modifier.weight(1f, fill = false).padding(top = TICKET_PARA_GAP_DP.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(TICKET_PARA_GAP_DP.dp),
            ) {
                for (para in t.paragraphs) Text(para, color = text, style = bodyStyle)
            }
            // The footer's dashed rule, then the stakes left and the way out right.
            Box(
                Modifier.padding(top = 18.dp).fillMaxWidth().height(2.dp).drawWithCache {
                    val dashes = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f)
                    val rule = rgb(p.ticketAccent, 0.45f)
                    onDrawBehind {
                        drawLine(
                            rule, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                            strokeWidth = size.height, pathEffect = dashes,
                        )
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Top) {
                Text(t.stakes.orEmpty(), color = rgb(p.ticketAccent), style = TICKET_STAKES_STYLE, modifier = Modifier.weight(1f))
                Text(
                    TicketText.CLOSE, color = secondary, style = TICKET_CLOSE_STYLE, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
        }
    }
}
