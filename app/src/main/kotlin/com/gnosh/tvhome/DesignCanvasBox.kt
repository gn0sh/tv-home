package com.gnosh.tvhome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import tvhome.core.ui.DesignCanvas

/**
 * Lays [content] out on the 1280 x 720 dp design canvas whatever the screen's own density ([DesignCanvas]): dp are
 * rescaled so the canvas fills the screen (centred, letterboxed if the screen is not 16:9). The TV emulator is 1920x1080
 * at 2.0 (960 x 540 dp), where the hero ran off the right edge and the dock pills overlapped (emulator tour 2026-11-03).
 * The user's font scale is kept. Dialogs do not inherit the density: a Dialog is a new window, and its composition
 * provides LocalDensity again from the real window. Each dialog captures `LocalDensity.current` before `Dialog(...)` and
 * provides it again around its content (TicketDialog, PickDialog, TileMenu, HiddenAppsDialog).
 *
 * Cold start (AC8): the size comes from the configuration (screen dp times the window's density), not from a
 * BoxWithConstraints. That one wrapped the whole home screen in a subcomposition, so the first frame composed every
 * tile inside the measure pass, with no composition before it; PR #25's emulator job measured about +600 ms. A plain Box
 * composes the home screen in the normal composition pass, as before the canvas.
 */
@Composable
fun DesignCanvasBox(content: @Composable () -> Unit) {
    val outer = LocalDensity.current
    // Assumes window == screen: a full-screen launcher activity, no multi-window or freeform on the TV.
    val cfg = LocalConfiguration.current
    val scaled = remember(cfg.screenWidthDp, cfg.screenHeightDp, outer.density, outer.fontScale) {
        val w = (cfg.screenWidthDp * outer.density).toInt()
        val h = (cfg.screenHeightDp * outer.density).toInt()
        Density(DesignCanvas.density(w, h), outer.fontScale)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalDensity provides scaled) {
            Box(Modifier.size(DesignCanvas.WIDTH_DP.dp, DesignCanvas.HEIGHT_DP.dp)) { content() }
        }
    }
}
