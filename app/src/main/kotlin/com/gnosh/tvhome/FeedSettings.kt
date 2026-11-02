package com.gnosh.tvhome

import android.content.Context
import tvhome.core.feeds.Location
import tvhome.core.feeds.PanelId
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.TravelRules
import tvhome.core.feeds.panelSwitches
import tvhome.core.ui.TravelRule

/**
 * The weather location (latitude and longitude, two decimals), the one setting the hero feeds need. Plain
 * SharedPreferences in app-private storage (not a secret, and `allowBackup` is off). Set over adb through the provision
 * provider (docs/adr/0005-tv-home-live-feeds.md); with nothing set the hero shows no weather.
 * [location] reads from disk on first use, so call it off the main thread (the feeds do).
 */
object FeedSettings {
    private const val FILE = "feeds"

    fun location(context: Context): Location? {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        return Location.of(lat, lon)
    }

    /** False (nothing stored) when the pair is out of range. */
    fun setLocation(context: Context, lat: Double, lon: Double): Boolean {
        val loc = Location.of(lat, lon) ?: return false
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("lat", "%.2f".format(java.util.Locale.ROOT, loc.lat))
            .putString("lon", "%.2f".format(java.util.Locale.ROOT, loc.lon))
            .apply()
        return true
    }

    fun clearLocation(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove("lat").remove("lon").apply()
    }

    // ---- M7-F2: panel switches, TV profile, travel rules (all non-secret, validated in :core before they get here) ----

    private const val TV_PROFILE = "tv_profile"
    private const val TRAVEL = "travel"
    private val lock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Reads from disk on first use: call off the main thread. A missing or wrongly typed value is the default. */
    fun switches(context: Context): PanelSwitches {
        val p = prefs(context)
        return panelSwitches { id -> if (p.contains(id.prefKey)) p.getBoolean(id.prefKey, false) else null }
    }

    fun setPanel(context: Context, id: PanelId, on: Boolean) {
        prefs(context).edit().putBoolean(id.prefKey, on).apply()
    }

    /** Every panel back to its default (the brief off). */
    fun clearPanels(context: Context) {
        val e = prefs(context).edit()
        for (id in PanelId.entries) e.remove(id.prefKey)
        e.apply()
    }

    /** `lg`, `vizio` or null (not set: the EDID id, else LG). */
    fun tvProfile(context: Context): String? = runCatching { prefs(context).getString(TV_PROFILE, null) }.getOrNull()

    fun setTvProfile(context: Context, value: String) { prefs(context).edit().putString(TV_PROFILE, value).apply() }

    fun clearTvProfile(context: Context) { prefs(context).edit().remove(TV_PROFILE).apply() }

    fun travelRules(context: Context): List<TravelRule> =
        TravelRules.decode(runCatching { prefs(context).getString(TRAVEL, null) }.getOrNull())

    /** False (nothing stored) when the list already holds the maximum and [rule] is a new keyword. */
    fun addTravelRule(context: Context, rule: TravelRule): Boolean = synchronized(lock) {
        val next = TravelRules.add(travelRules(context), rule) ?: return false
        prefs(context).edit().putString(TRAVEL, TravelRules.encode(next)).commit()
        true
    }

    fun clearTravelRules(context: Context) { synchronized(lock) { prefs(context).edit().remove(TRAVEL).commit() } }
}
