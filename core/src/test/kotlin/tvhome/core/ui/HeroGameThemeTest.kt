package tvhome.core.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.feeds.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class HeroGameThemeTest {
    private fun game(team: TeamConfig, phase: GamePhase = GamePhase.UPCOMING) =
        TeamGame(team, GameInfo(Instant.parse("2026-10-31T23:00:00Z"), "Blues", true, phase, null, null, null))

    @Test fun textAndAccentKeepContrastOnEveryTeamTheme() {
        for (team in Teams.ALL) for (phase in GamePhase.values()) {
            val t = HeroTheme.forGame(game(team, phase))
            val name = "${team.key}/$phase"
            for (bg in listOf(t.backdropTop, t.backdropBottom)) {
                assertTrue(Tokens.contrast(t.text, bg) >= 4.5, "$name text on ${bg.toString(16)}: ${Tokens.contrast(t.text, bg)}")
                assertTrue(Tokens.contrast(t.secondaryText, bg) >= 4.5, "$name secondary on ${bg.toString(16)}: ${Tokens.contrast(t.secondaryText, bg)}")
            }
            assertTrue(Tokens.contrast(t.accent, t.backdropTop) >= 4.5, "$name accent vs top")
            // The badge text is drawn in backdropTop on an accent pill.
            assertEquals(HeroMotif.GAME, t.motif)
        }
    }

    @Test fun brandColoursArePresentInTheConfig() {
        assertEquals(0xFFB81C, Teams.PREDATORS.primary); assertEquals(0x041E42, Teams.PREDATORS.secondary)
        assertEquals(0xBB0000, Teams.OHIO_STATE.primary); assertEquals(0x666666, Teams.OHIO_STATE.secondary)
        assertEquals(Teams.PREDATORS.primary, Teams.PREDATORS.accent) // gold is used as is: it passes
    }

    @Test fun gameDayBeatsHolidayBeatsTimeOfDay() {
        val halloween = LocalDate.of(2026, 10, 31); val noon = LocalTime.NOON
        assertEquals("HALLOWEEN", HeroTheme.resolve(halloween, noon, null).badge)
        val g = HeroTheme.resolve(halloween, noon, game(Teams.PREDATORS))
        assertEquals("GAME DAY", g.badge); assertEquals(Teams.PREDATORS.backdropTop, g.backdropTop)
        assertEquals("LIVE", HeroTheme.resolve(halloween, noon, game(Teams.OHIO_STATE, GamePhase.LIVE)).badge)
        assertNull(HeroTheme.resolve(LocalDate.of(2026, 3, 10), noon, null).badge)
        assertEquals(HeroTheme.at(halloween, noon), HeroTheme.resolve(halloween, noon, null))
    }
}
