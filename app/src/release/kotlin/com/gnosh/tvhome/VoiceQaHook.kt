package com.gnosh.tvhome

import android.content.Context

/** The voice overlay's QA hook. In this build type it does nothing: never a planted exchange (see src/qa). */
internal object VoiceQaHook {
    @Suppress("UNUSED_PARAMETER")
    fun planted(context: Context): QaPlanted? = null
}
