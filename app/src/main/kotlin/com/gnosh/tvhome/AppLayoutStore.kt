package com.gnosh.tvhome

import android.content.Context
import tvhome.core.apps.AppLayout

/** The saved app-row customisation (M2g): the order and the hidden set, both by package name. */
data class SavedLayout(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/**
 * [SavedLayout] in app-private SharedPreferences. Blocking: call [load] and [save] off the main thread. Nothing here
 * is secret (package names of apps on Brian's TV), but it is not logged either.
 */
class AppLayoutStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app-layout", Context.MODE_PRIVATE)

    fun load(): SavedLayout = SavedLayout(
        order = AppLayout.decodeOrder(prefs.getString(KEY_ORDER, null)),
        hidden = prefs.getStringSet(KEY_HIDDEN, null)?.toSet().orEmpty(),
    )

    fun save(layout: SavedLayout) {
        prefs.edit()
            .putString(KEY_ORDER, AppLayout.encodeOrder(layout.order))
            .putStringSet(KEY_HIDDEN, layout.hidden.toSet()) // a copy: the set passed in must not be kept by the prefs
            .apply()
    }

    private companion object {
        const val KEY_ORDER = "order"
        const val KEY_HIDDEN = "hidden"
    }
}
