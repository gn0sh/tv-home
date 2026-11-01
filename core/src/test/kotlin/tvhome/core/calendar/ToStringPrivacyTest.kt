package tvhome.core.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import java.time.Instant

class ToStringPrivacyTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")

    /** pii-in-data-class-tostring: results and agenda values print counts, never titles or uids. */
    @Test fun toStringsCarryNoTitles() {
        val ev = EventInstance("secret-uid@example.test", "Dr. Smith, oncology", EventTime.At(now), null)
        val ok = FetchResult.Ok(listOf(ev, ev), now)
        for (text in listOf(ev.toString(), ok.toString(), listOf(ev).toString(),
            AgendaDay(java.time.LocalDate.of(2026, 10, 31), listOf(AgendaItem(ev.title, TimeLabel.AllDay))).toString(),
            AgendaItem(ev.title, TimeLabel.Continues).toString())) {
            assertFalse(text.contains("Smith") || text.contains("oncology") || text.contains("secret-uid"), text)
        }
        assertEquals("Ok(2 events, fetchedAt=$now)", ok.toString())
        assertTrue(ev.toString().contains("19 chars"))
        // equality is unchanged (data classes): the cache and the state reducer compare values
        assertEquals(FetchResult.Ok(listOf(ev, ev), now), ok)
    }
}
