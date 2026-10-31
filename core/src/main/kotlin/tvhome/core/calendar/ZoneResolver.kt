package tvhome.core.calendar

import java.time.DateTimeException
import java.time.ZoneId

/**
 * Maps an ICS TZID to a [ZoneId] without ever reading VTIMEZONE (the parser drops those: a VTIMEZONE with a hostile
 * RRULE made biweekly run out of memory while parsing). Order: IANA id (also with a "/mozilla.org/..."-style prefix),
 * then the Windows names Outlook/Exchange write, then [fallback] (the device zone). Never throws.
 */
internal object ZoneResolver {

    fun resolve(tzid: String?, fallback: ZoneId): ZoneId {
        val raw = tzid?.trim()?.trim('"')?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return fallback
        WINDOWS[raw.lowercase()]?.let { return it }
        ofOrNull(raw)?.let { return it }
        // Global ids like "/mozilla.org/20070129_1/Europe/London" or "/Europe/London": try each suffix.
        val parts = raw.split('/').filter { it.isNotEmpty() }
        for (i in parts.indices) {
            ofOrNull(parts.subList(i, parts.size).joinToString("/"))?.let { return it }
        }
        return fallback
    }

    private fun ofOrNull(id: String): ZoneId? = try {
        // Region ids and offsets only (no SHORT_IDS: "EST" and friends are ambiguous).
        if (id.any { it.isWhitespace() }) null else ZoneId.of(id)
    } catch (e: DateTimeException) {
        null
    }

    /** CLDR windowsZones.xml, territory "001" entries (the ones Outlook writes), subset. Keys are lower case. */
    internal val WINDOWS_IDS: Map<String, String> = mapOf(
        "Dateline Standard Time" to "Etc/GMT+12",
        "UTC-11" to "Etc/GMT+11",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Alaskan Standard Time" to "America/Anchorage",
        "Pacific Standard Time (Mexico)" to "America/Tijuana",
        "Pacific Standard Time" to "America/Los_Angeles",
        "US Mountain Standard Time" to "America/Phoenix",
        "Mountain Standard Time (Mexico)" to "America/Mazatlan",
        "Mountain Standard Time" to "America/Denver",
        "Central America Standard Time" to "America/Guatemala",
        "Central Standard Time" to "America/Chicago",
        "Central Standard Time (Mexico)" to "America/Mexico_City",
        "Canada Central Standard Time" to "America/Regina",
        "SA Pacific Standard Time" to "America/Bogota",
        "Eastern Standard Time (Mexico)" to "America/Cancun",
        "Eastern Standard Time" to "America/New_York",
        "US Eastern Standard Time" to "America/Indiana/Indianapolis",
        "Venezuela Standard Time" to "America/Caracas",
        "Atlantic Standard Time" to "America/Halifax",
        "SA Western Standard Time" to "America/La_Paz",
        "Pacific SA Standard Time" to "America/Santiago",
        "Newfoundland Standard Time" to "America/St_Johns",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "Argentina Standard Time" to "America/Argentina/Buenos_Aires",
        "SA Eastern Standard Time" to "America/Cayenne",
        "Greenland Standard Time" to "America/Godthab",
        "Azores Standard Time" to "Atlantic/Azores",
        "Cape Verde Standard Time" to "Atlantic/Cape_Verde",
        "UTC" to "UTC",
        "Coordinated Universal Time" to "UTC",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Romance Standard Time" to "Europe/Paris",
        "Central European Standard Time" to "Europe/Warsaw",
        "W. Central Africa Standard Time" to "Africa/Lagos",
        "GTB Standard Time" to "Europe/Bucharest",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "FLE Standard Time" to "Europe/Kiev",
        "Israel Standard Time" to "Asia/Jerusalem",
        "Egypt Standard Time" to "Africa/Cairo",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Arab Standard Time" to "Asia/Riyadh",
        "Russian Standard Time" to "Europe/Moscow",
        "E. Africa Standard Time" to "Africa/Nairobi",
        "Iran Standard Time" to "Asia/Tehran",
        "Arabian Standard Time" to "Asia/Dubai",
        "Pakistan Standard Time" to "Asia/Karachi",
        "India Standard Time" to "Asia/Kolkata",
        "Nepal Standard Time" to "Asia/Kathmandu",
        "Bangladesh Standard Time" to "Asia/Dhaka",
        "SE Asia Standard Time" to "Asia/Bangkok",
        "China Standard Time" to "Asia/Shanghai",
        "Singapore Standard Time" to "Asia/Singapore",
        "Taipei Standard Time" to "Asia/Taipei",
        "W. Australia Standard Time" to "Australia/Perth",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "Korea Standard Time" to "Asia/Seoul",
        "Cen. Australia Standard Time" to "Australia/Adelaide",
        "AUS Central Standard Time" to "Australia/Darwin",
        "E. Australia Standard Time" to "Australia/Brisbane",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "Tasmania Standard Time" to "Australia/Hobart",
        "West Pacific Standard Time" to "Pacific/Port_Moresby",
        "New Zealand Standard Time" to "Pacific/Auckland",
        "Tonga Standard Time" to "Pacific/Tongatapu",
    )

    private val WINDOWS: Map<String, ZoneId> =
        WINDOWS_IDS.entries.mapNotNull { (k, v) -> ofOrNull(v)?.let { k.lowercase() to it } }.toMap()
}
