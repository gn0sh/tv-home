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
import tvhome.core.ui.HomeTheme
import tvhome.core.ui.ScreenModel
import tvhome.core.ui.Tokens

/** One tile: the app, and its banner (16:9) or, failing that, its icon, already drawn to a bitmap off the main thread. */
class TileApp(val app: LauncherApp, val image: ImageBitmap?, val isBanner: Boolean)

/** The tile row's data: still loading, loaded (possibly empty), or the package manager query failed. */
sealed interface AppsState {
    data object Loading : AppsState
    data class Loaded(val apps: List<TileApp>) : AppsState
    data object Failed : AppsState
}

private const val FOCUS_SCALE = 1.1f

/**
 * M2 home screen: a row of app tiles on top, the calendar panel below. The two are independent: the tiles come from
 * the package manager and never wait on, or fail with, the calendar. Initial focus goes to the first tile once the row
 * has apps, unless something already has focus; D-pad Down/Up moves between the row and the panel (the panel is
 * focusable itself when it has no rows). Colours and tile size come from [theme].
 */
@Composable
fun HomeScreen(apps: AppsState, theme: HomeTheme, onLaunch: (LauncherApp) -> Unit, agenda: ScreenModel) {
    var childFocused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(color(theme.background))
            .focusable() // somewhere for D-pad presses to land before the row has loaded
            .padding(horizontal = Tokens.MARGIN_H_DP.dp, vertical = Tokens.MARGIN_V_DP.dp),
    ) {
        Column(Modifier.fillMaxSize().onFocusChanged { childFocused = it.hasFocus }) {
            AppRow(apps, theme, mayTakeFocus = { !childFocused }, onLaunch)
            Spacer(Modifier.height(16.dp))
            AgendaPanel(agenda, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun AppRow(state: AppsState, theme: HomeTheme, mayTakeFocus: () -> Boolean, onLaunch: (LauncherApp) -> Unit) {
    val apps = (state as? AppsState.Loaded)?.apps.orEmpty()
    val first = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(apps.isNotEmpty()) {
        if (focusedOnce || apps.isEmpty()) return@LaunchedEffect
        focusedOnce = true
        withFrameNanos { }
        // Never steal focus: if the user already moved into the calendar panel while the row loaded, leave it there.
        if (!mayTakeFocus()) return@LaunchedEffect
        if (runCatching { first.requestFocus() }.isFailure) {
            withFrameNanos { }
            if (mayTakeFocus()) runCatching { first.requestFocus() }
        }
    }
    if (apps.isEmpty()) {
        // Not a dead end: the root and the calendar panel are still focusable.
        val msg = when (state) {
            AppsState.Loading -> "Loading apps…"
            AppsState.Failed -> "Couldn't list apps"
            is AppsState.Loaded -> "No TV apps found"
        }
        Text(msg, color = color(theme.secondaryText), fontSize = Tokens.SECONDARY_SP.sp, modifier = Modifier.height((theme.tileHeightDp + 56).dp))
        return
    }
    // Content padding leaves room for the 1.1x focus scale inside the safe area.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = (theme.tileWidthDp / 16).dp, vertical = (theme.tileHeightDp / 13).dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        items(apps, key = { it.app.packageName }) { t ->
            AppTile(t, theme, if (t === apps.first()) first else null) { onLaunch(t.app) }
        }
    }
}

@Composable
private fun AppTile(t: TileApp, theme: HomeTheme, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(theme.tileWidthDp.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(theme.tileWidthDp.dp, theme.tileHeightDp.dp)
                .graphicsLayer {
                    val s = if (focused) FOCUS_SCALE else 1f
                    scaleX = s; scaleY = s
                }
                .clip(shape)
                .background(color(if (focused) theme.focusFill else theme.tile), shape)
                .then(if (focused) Modifier.border(Tokens.RING_DP.dp, color(theme.focusRing), shape) else Modifier)
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
                    modifier = if (t.isBanner) Modifier.fillMaxSize() else Modifier.size((minOf(theme.tileWidthDp, theme.tileHeightDp) * 3 / 5).dp),
                )
            } else {
                Text(t.app.label.take(1).uppercase(), color = color(theme.text), fontSize = 48.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            t.app.label,
            color = color(if (focused) theme.text else theme.secondaryText),
            fontSize = Tokens.SECONDARY_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

