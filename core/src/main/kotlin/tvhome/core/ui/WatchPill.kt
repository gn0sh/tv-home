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
        if (app.isNullOrEmpty() || app.length > MAX_LABEL || app.any { it.isISOControl() }) return PLAIN
        val text = "Watch on $app"
        return if (runCatching { fits(text) }.getOrDefault(false)) text else PLAIN
    }
}
