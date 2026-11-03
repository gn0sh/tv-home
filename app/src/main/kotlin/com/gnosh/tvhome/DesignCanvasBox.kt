package com.gnosh.tvhome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import tvhome.core.ui.DesignCanvas

/**
 * Lays [content] out on the 1280 x 720 dp design canvas whatever the screen's own density ([DesignCanvas]): dp are
 * rescaled so the canvas fills the screen (centred, letterboxed if the screen is not 16:9). The TV emulator is 1920x1080
 * at 2.0 (960 x 540 dp), where the hero ran off the right edge and the dock pills overlapped (emulator tour 2026-11-03).
 * The user's font scale is kept. Dialogs inherit the density through the composition.
 */
@Composable
fun DesignCanvasBox(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val outer = LocalDensity.current
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val scaled = remember(w, h, outer.fontScale) { Density(DesignCanvas.density(w, h), outer.fontScale) }
        CompositionLocalProvider(LocalDensity provides scaled) {
            Box(Modifier.size(DesignCanvas.WIDTH_DP.dp, DesignCanvas.HEIGHT_DP.dp)) { content() }
        }
    }
}
