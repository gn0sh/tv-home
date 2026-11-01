package com.gnosh.tvhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tvhome.core.apps.LauncherApp
import tvhome.core.ui.ScreenModel
import tvhome.core.ui.Tokens

/** One tile: the app, and its banner (16:9) or, failing that, its icon, already drawn to a bitmap off the main thread. */
class TileApp(val app: LauncherApp, val image: ImageBitmap?, val isBanner: Boolean)

private val BG = color(Tokens.BACKGROUND)
private val TEXT = color(Tokens.TEXT)
private val SECONDARY = color(Tokens.SECONDARY)
private val FILL = color(Tokens.FOCUS_FILL)
private val RING = color(Tokens.FOCUS_RING)

private const val TILE_W = 240
private const val TILE_H = 135
private const val FOCUS_SCALE = 1.1f

/**
 * M2 home screen: a row of app tiles on top, the calendar panel below. The two are independent: the tiles come from
 * the package manager and never wait on, or fail with, the calendar. Initial focus goes to the first tile once the row
 * has apps; D-pad Down/Up moves between the row and the panel (the panel is focusable itself when it has no rows).
 */
@Composable
fun HomeScreen(apps: List<TileApp>, onLaunch: (LauncherApp) -> Unit, agenda: ScreenModel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
            .focusable() // somewhere for D-pad presses to land before the row has loaded
            .padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            AppRow(apps, onLaunch)
            Spacer(Modifier.height(16.dp))
            AgendaPanel(agenda, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun AppRow(apps: List<TileApp>, onLaunch: (LauncherApp) -> Unit) {
    val first = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(apps.isNotEmpty()) {
        if (focusedOnce || apps.isEmpty()) return@LaunchedEffect
        focusedOnce = true
        withFrameNanos { }
        if (runCatching { first.requestFocus() }.isFailure) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    if (apps.isEmpty()) {
        // Not a dead end: the root and the calendar panel are still focusable.
        Text("No TV apps found", color = SECONDARY, fontSize = Tokens.SECONDARY_SP.sp, modifier = Modifier.height((TILE_H + 56).dp))
        return
    }
    // Content padding leaves room for the 1.1x focus scale inside the safe area.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        items(apps, key = { it.app.packageName }) { t ->
            AppTile(t, if (t === apps.first()) first else null) { onLaunch(t.app) }
        }
    }
}

@Composable
private fun AppTile(t: TileApp, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(TILE_W.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(TILE_W.dp, TILE_H.dp)
                .graphicsLayer {
                    val s = if (focused) FOCUS_SCALE else 1f
                    scaleX = s; scaleY = s
                }
                .clip(shape)
                .background(if (focused) FILL else color(0x1C222B), shape)
                .then(if (focused) Modifier.border(Tokens.RING_DP.dp, RING, shape) else Modifier)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = t.app.label }
                .clickable(onClick = onClick), // focusable on TV; OK/Enter/D-pad centre clicks
            contentAlignment = Alignment.Center,
        ) {
            val img = t.image
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null, // the label below names the tile
                    contentScale = if (t.isBanner) ContentScale.Crop else ContentScale.Fit,
                    modifier = if (t.isBanner) Modifier.fillMaxSize() else Modifier.size(80.dp),
                )
            } else {
                Text(t.app.label.take(1).uppercase(), color = TEXT, fontSize = 48.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            t.app.label,
            color = if (focused) TEXT else SECONDARY,
            fontSize = Tokens.SECONDARY_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

