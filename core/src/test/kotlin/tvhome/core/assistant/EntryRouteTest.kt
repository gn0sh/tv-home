package tvhome.core.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tvhome.core.assistant.EntryRoute.Target

class EntryRouteTest {
    private val main = "com.gnosh.tvhome.AgendaActivity"

    @Test fun micAndAssistOpenAskClaude() {
        assertEquals(Target.ASK, EntryRoute.target("android.intent.action.ASSIST", main))
        assertEquals(Target.ASK, EntryRoute.target("android.intent.action.VOICE_COMMAND", main))
        assertEquals(Target.ASK, EntryRoute.target(EntryRoute.ACTION_ASK, main))
    }

    /** The star button opens the "Ask Claude" launcher entry: MAIN on the alias. */
    @Test fun askClaudeLauncherEntryOpensAsk() {
        assertEquals(Target.ASK, EntryRoute.target("android.intent.action.MAIN", EntryRoute.ASK_ENTRY))
        assertEquals(Target.ASK, EntryRoute.target(null, EntryRoute.ASK_ENTRY))
    }

    @Test fun boardAndEverythingElse() {
        assertEquals(Target.BOARD, EntryRoute.target(EntryRoute.ACTION_BOARD, main))
        assertNull(EntryRoute.target("android.intent.action.MAIN", main), "the Agenda launcher entry shows the row")
        assertNull(EntryRoute.target(null, null))
        assertNull(EntryRoute.target("android.intent.action.SEARCH", main), "search is not claimed")
        assertNull(EntryRoute.target("com.gnosh.tvhome.action.HOME", main))
    }
}
