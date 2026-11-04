package tvhome.core.assistant

/**
 * Which screen an intent that reaches the app opens (mic-to-claude). Pure, so the routing is tested on the JVM; the
 * activity only passes the intent's action and component class name.
 *
 * - [ACTION_ASK] (our own: the PC remote, adb, and the assistant paths below forward to it), the assist and voice
 *   command actions, and the "Ask Claude" launcher entry ([ASK_ENTRY], which the Streamer's star button can open):
 *   Ask Claude, listening.
 * - [ACTION_BOARD]: the Board.
 * - Anything else (Home, the "Agenda" launcher entry): the home row.
 */
object EntryRoute {
    enum class Target { ASK, BOARD }

    const val ACTION_ASK = "com.gnosh.tvhome.action.ASK"
    const val ACTION_BOARD = "com.gnosh.tvhome.action.BOARD"

    /** The second launcher entry, an activity-alias of the main activity named "Ask Claude". */
    const val ASK_ENTRY = "com.gnosh.tvhome.AskClaudeEntry"

    /** Intent.ACTION_ASSIST and Intent.ACTION_VOICE_COMMAND (the mic button once this app is the digital assistant). */
    val ASSIST_ACTIONS = setOf("android.intent.action.ASSIST", "android.intent.action.VOICE_COMMAND")

    fun target(action: String?, componentClass: String?): Target? = when {
        action == ACTION_ASK || action in ASSIST_ACTIONS -> Target.ASK
        action == ACTION_BOARD -> Target.BOARD
        componentClass == ASK_ENTRY || componentClass == ".AskClaudeEntry" -> Target.ASK
        else -> null
    }
}
