package com.gnosh.tvhome

import android.content.Context
import tvhome.core.feeds.Location

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
}
