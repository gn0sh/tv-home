package com.gnosh.tvhome

import android.content.Context
import tvhome.core.feeds.Pick

/** The hero's pick QA hook. In this build type it does nothing: never a planted pick (see src/qa). */
internal object PickQaHook {
    @Suppress("UNUSED_PARAMETER")
    fun planted(context: Context): Map<String, Pick> = emptyMap()
}
