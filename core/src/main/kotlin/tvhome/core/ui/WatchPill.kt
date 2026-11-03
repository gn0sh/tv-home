package tvhome.core.ui

/**
 * The Watch pill's text (watch-pill-truncated): the game card already names the network, so the pill names the app it
 * opens, "Watch on CDE" or "Watch on ESPN", when that fits the pill whole; otherwise plain "Watch". Never an ellipsis:
 * [fits] is the app's measurement of one line at the pill's type size against its text width.
 */
object WatchPill {
    const val PLAIN = "Watch"

    /** Labels longer than this are not tried at all (a launcher label can be any length). */
    const val MAX_LABEL = 40

    fun label(appLabel: String?, fits: (String) -> Boolean): String {
        val app = appLabel?.trim()?.replace(Regex("\\s+"), " ")
        // watch-label-bidi: control and format characters (bidi overrides like U+202E, zero-width marks) are refused.
        if (app.isNullOrEmpty() || app.length > MAX_LABEL || app.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            return PLAIN
        }
        val text = "Watch on $app"
        return if (runCatching { fits(text) }.getOrDefault(false)) text else PLAIN
    }

    /**
     * TalkBack's sentence for the pill (watch-desc-mismatch): "Watch the game on SCRIPPS", plus ", opens CDE" when the
     * pill shows the app ([label] gave "Watch on CDE"). Starts with [descPrefix] so the emulator QA still matches.
     */
    fun description(descPrefix: String, network: String, shownLabel: String): String {
        val app = shownLabel.removePrefix("Watch on ").takeIf { shownLabel != PLAIN && shownLabel.startsWith("Watch on ") }
        return descPrefix + network + (app?.let { ", opens $it" } ?: "")
    }
}
