package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tvhome.core.ui.TravelRule

/** M7-F2 (sec): the adb-set panel switches, TV profile and travel rules are checked here before anything is stored. */
class PanelSettingsTest {
    @Test fun panelIdsAreExactAndPrefixed() {
        assertEquals(PanelId.ALERTS, PanelId.of("alerts"))
        assertEquals("panel.alerts", PanelId.ALERTS.prefKey)
        for (bad in listOf(null, "", "Alerts", " alerts", "alerts ", "alerts\n", "panel.alerts", "calendar", "location", "ALERTS")) {
            assertNull(PanelId.of(bad), "'$bad'")
        }
        assertEquals(listOf("weather", "alerts", "games", "brief"), PanelId.entries.map { it.key })
    }

    @Test fun panelAcceptsOnlyAnIdAndABoolean() {
        assertEquals(PanelId.ALERTS to false, SettingsValidator.panel(mapOf("id" to "alerts", "on" to false)))
        assertEquals(PanelId.BRIEF to true, SettingsValidator.panel(mapOf("id" to "brief", "on" to true)))
        val refused = listOf(
            mapOf("id" to "alerts", "on" to "false"),           // a string, not a boolean
            mapOf("id" to "alerts", "on" to 0),                 // an int
            mapOf("id" to "alerts", "on" to null),
            mapOf("id" to "alerts"),                            // missing on
            mapOf("on" to true),                                // missing id
            mapOf("id" to "nope", "on" to true),                // unknown panel
            mapOf("id" to 1, "on" to true),                     // id not a string
            mapOf("id" to "alerts", "on" to true, "x" to 1),    // an extra key
            emptyMap(),
        )
        for (v in refused) assertNull(SettingsValidator.panel(v), "$v")
    }

    @Test fun tvProfileIsLgOrVizioExactly() {
        assertEquals("lg", SettingsValidator.tvProfile(mapOf("value" to "lg")))
        assertEquals("vizio", SettingsValidator.tvProfile(mapOf("value" to "vizio")))
        for (v in listOf<Map<String, Any?>>(
            mapOf("value" to "LG"), mapOf("value" to " lg"), mapOf("value" to "samsung"), mapOf("value" to ""),
            mapOf("value" to null), mapOf("value" to 1), mapOf("profile" to "lg"), mapOf("value" to "lg", "x" to "y"), emptyMap(),
        )) assertNull(SettingsValidator.tvProfile(v), "$v")
    }

    @Test fun travelRuleBoundsAndTypes() {
        assertEquals(TravelRule("Dentist", 20), SettingsValidator.travelRule(mapOf("keyword" to "Dentist", "minutes" to 20)))
        assertEquals(TravelRule("a", 1), SettingsValidator.travelRule(mapOf("keyword" to "a", "minutes" to 1)))
        assertEquals(180, SettingsValidator.travelRule(mapOf("keyword" to "x".repeat(40), "minutes" to 180))?.minutes)
        val refused = listOf<Map<String, Any?>>(
            mapOf("keyword" to "x".repeat(41), "minutes" to 20),
            mapOf("keyword" to "", "minutes" to 20),
            mapOf("keyword" to "   ", "minutes" to 20),
            mapOf("keyword" to "a\nb", "minutes" to 20),
            mapOf("keyword" to "a\u0000", "minutes" to 20),
            mapOf("keyword" to "Dentist", "minutes" to 0),
            mapOf("keyword" to "Dentist", "minutes" to 181),
            mapOf("keyword" to "Dentist", "minutes" to -5),
            mapOf("keyword" to "Dentist", "minutes" to 20L),     // a Long (l:), not an Int
            mapOf("keyword" to "Dentist", "minutes" to "20"),
            mapOf("keyword" to "Dentist"),
            mapOf("keyword" to "Dentist", "minutes" to 20, "x" to 1),
        )
        for (v in refused) assertNull(SettingsValidator.travelRule(v), "$v")
    }

    @Test fun travelRulesRoundTripAndRejectTampering() {
        val rules = listOf(TravelRule("Dentist", 20), TravelRule("Gym", 15))
        assertEquals(rules, TravelRules.decode(TravelRules.encode(rules)))
        assertEquals(emptyList<TravelRule>(), TravelRules.decode(null))
        assertEquals(emptyList<TravelRule>(), TravelRules.decode("not json"))
        assertEquals(emptyList<TravelRule>(), TravelRules.decode("""[{"keyword":"Dentist","minutes":999}]"""))
        assertEquals(emptyList<TravelRule>(), TravelRules.decode("""[{"keyword":"Dentist","minutes":20,"extra":1}]"""))
        assertEquals(emptyList<TravelRule>(), TravelRules.decode("""[{"keyword":"Dentist","minutes":20},{"keyword":"","minutes":5}]"""))
        val eleven = (1..11).map { TravelRule("k$it", 5) }
        assertEquals(emptyList<TravelRule>(), TravelRules.decode(TravelRules.encode(eleven)))
        assertEquals(emptyList<TravelRule>(), TravelRules.decode("[" + "{\"keyword\":\"a\",\"minutes\":1},".repeat(400) + "]"))
    }

    @Test fun addReplacesSameKeywordAndRefusesAnEleventh() {
        val one = listOf(TravelRule("Dentist", 20))
        assertEquals(listOf(TravelRule("dentist", 30)), TravelRules.add(one, TravelRule("dentist", 30)))
        assertEquals(one + TravelRule("Gym", 10), TravelRules.add(one, TravelRule("Gym", 10)))
        val ten = (1..10).map { TravelRule("k$it", 5) }
        assertNull(TravelRules.add(ten, TravelRule("new", 5)))
        assertEquals(10, TravelRules.add(ten, TravelRule("K3", 9))?.size, "replacing one still fits")
    }

    @Test fun switchesDefaultWhenNothingStoredAndBriefShipsOff() {
        assertEquals(PanelSwitches(), panelSwitches { null })
        assertEquals(false, panelSwitches { null }.brief)
        val s = panelSwitches { if (it == PanelId.ALERTS) false else null }
        assertEquals(PanelSwitches(alerts = false), s)
        // A store that throws (a bad stored type) gives the default, never a crash.
        assertEquals(PanelSwitches(), panelSwitches { throw ClassCastException() })
        assertTrue(panelSwitches { true }.brief)
    }
}
