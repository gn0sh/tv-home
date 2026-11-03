package tvhome.core.ui

/**
 * Quiet / guest mode (spec M7-7): in Quiet every event title the home screen draws becomes "Busy"; times stay. Weather,
 * the game card and the apps are untouched. [homeGlance] and [calendarTile] take the flag and call [title] for every
 * calendar title they render, so this is the one place the rule lives. (Discarding a cached brief that held titles is
 * the brief task, B10.)
 */
object QuietMode {
    const val BUSY = "Busy"

    /** [raw] cut to [max] chars and trimmed, [fallback] when nothing is left; "Busy" when [quiet]. */
    fun title(raw: String, quiet: Boolean, max: Int = NowNextCopy.MAX_TITLE, fallback: String = Copy.NO_TITLE): String =
        if (quiet) BUSY else raw.trim().take(max).trim().ifEmpty { fallback }
}
