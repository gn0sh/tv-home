package tvhome.core.assistant

/**
 * overlay-open-fix: what OpenTrampolineActivity accepts and how it cleans the intent. Pure, so it is JVM-tested.
 * Accepted only when the intent names another app: a package or a component, and neither of them is ours.
 */
object OpenFilter {
    // android.content.Intent flag values (stable public API).
    const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
    const val FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002
    const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040
    const val FLAG_GRANT_PREFIX_URI_PERMISSION = 0x00000080
    const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    private const val GRANTS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION or
        FLAG_GRANT_PERSISTABLE_URI_PERMISSION or FLAG_GRANT_PREFIX_URI_PERMISSION

    fun accepts(intentPackage: String?, componentPackage: String?, ownPackage: String): Boolean {
        val named = listOfNotNull(intentPackage?.takeIf { it.isNotBlank() }, componentPackage?.takeIf { it.isNotBlank() })
        return named.isNotEmpty() && named.none { it == ownPackage }
    }

    /** No URI grants ever pass through; always a new task. */
    fun flags(original: Int): Int = (original and GRANTS.inv()) or FLAG_ACTIVITY_NEW_TASK
}
