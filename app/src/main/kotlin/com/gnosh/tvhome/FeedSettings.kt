package com.gnosh.tvhome

import android.content.Context
import tvhome.core.feeds.Location
import tvhome.core.feeds.PanelId
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.SettingsValidator
import tvhome.core.feeds.TravelRules
import tvhome.core.feeds.WatchMap
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
    private const val IDLE_TEST = "idle_test_s"
    private const val IDLE_TEST_AT = "idle_test_at"
    private const val WATCH_NETWORKS = "watch_networks"
    private const val WATCH_DEFAULTS = "watch_defaults"
    private const val QUIET = "quiet"
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

    /** Emulator QA only: idle thresholds in seconds (DIM after it, NEAR_BLACK after twice it), or null (the profile's). */
    /** Lasts [SettingsValidator.IDLE_TEST_TTL] from when it was set; an expired or unstamped one is cleared here. */
    fun idleTestSeconds(context: Context): Int? = runCatching {
        val p = prefs(context)
        if (!p.contains(IDLE_TEST) && !p.contains(IDLE_TEST_AT)) return@runCatching null
        val s = if (p.contains(IDLE_TEST)) p.getInt(IDLE_TEST, 0) else null
        val at = if (p.contains(IDLE_TEST_AT)) p.getLong(IDLE_TEST_AT, 0L) else null
        SettingsValidator.idleTestActive(s, at, System.currentTimeMillis()) ?: run { clearIdleTestSeconds(context); null }
    }.getOrNull()

    fun setIdleTestSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(IDLE_TEST, seconds).putLong(IDLE_TEST_AT, System.currentTimeMillis()).commit()
    }

    fun clearIdleTestSeconds(context: Context) { prefs(context).edit().remove(IDLE_TEST).remove(IDLE_TEST_AT).commit() }

    /** M7-F5: the watch map (validated on insert, re-parsed with WatchMap's limits here). Off the main thread. */
    fun watchMap(context: Context): WatchMap = runCatching {
        val p = prefs(context)
        WatchMap.parse(p.getString(WATCH_NETWORKS, null), p.getString(WATCH_DEFAULTS, null))
    }.getOrDefault(WatchMap.EMPTY)

    /** The valid package names the stored watch map names (for the installed check). */
    fun watchPackages(context: Context): Set<String> =
        runCatching { SettingsValidator.watchPackages(prefs(context).getString(WATCH_NETWORKS, null)) }.getOrDefault(emptySet())

    fun setWatchMap(context: Context, networks: String, defaults: String) {
        prefs(context).edit().putString(WATCH_NETWORKS, networks).putString(WATCH_DEFAULTS, defaults).commit()
    }

    fun clearWatchMap(context: Context) { prefs(context).edit().remove(WATCH_NETWORKS).remove(WATCH_DEFAULTS).commit() }

    /** M7-F6: Quiet mode (survives restart). Off the main thread. */
    fun quiet(context: Context): Boolean = runCatching { prefs(context).getBoolean(QUIET, false) }.getOrDefault(false)

    fun setQuiet(context: Context, on: Boolean) { prefs(context).edit().putBoolean(QUIET, on).apply() }

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
