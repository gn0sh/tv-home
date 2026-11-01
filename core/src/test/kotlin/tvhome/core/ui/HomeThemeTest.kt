package tvhome.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeThemeTest {
    private val d = HomeTheme.DEFAULT

    @Test fun missingFileGivesDefaults() = assertEquals(d, HomeTheme.parse(null))
    @Test fun emptyObjectGivesDefaults() = assertEquals(d, HomeTheme.parse("{}"))

    @Test fun fullThemeIsApplied() {
        val t = HomeTheme.parse(
            """{"background":"#000000","tile":"#111111","focusFill":"#222222","focusRing":"#FFaa00",
               "text":"#FFFFFF","secondaryText":"#cccccc","tileWidthDp":300,"tileHeightDp":169}""",
        )
        assertEquals(HomeTheme(0x000000, 0x111111, 0x222222, 0xFFAA00, 0xFFFFFF, 0xCCCCCC, 300, 169), t)
    }

    @Test fun partialThemeKeepsOtherDefaults() {
        assertEquals(d.copy(focusRing = 0x00FF00), HomeTheme.parse("""{"focusRing":"#00ff00"}"""))
    }

    @Test fun anyInvalidValueRejectsTheWholeFile() {
        for (bad in listOf(
            """{"focusRing":"#00ff00","background":"red"}""",
            """{"background":"#FFF"}""",
            """{"background":"#FFFFFFFF"}""",
            """{"background":"FFFFFF"}""",
            """{"background":"#GGGGGG"}""",
            """{"background":16777215}""",
            """{"background":null}""",
            """{"background":["#FFFFFF"]}""",
            """{"tileWidthDp":"300"}""",
            """{"tileWidthDp":300.5}""",
            """{"tileWidthDp":119}""",
            """{"tileWidthDp":481}""",
            """{"tileHeightDp":67}""",
            """{"tileHeightDp":true}""",
            """{"surprise":"#FFFFFF"}""",
        )) assertEquals(d, HomeTheme.parse(bad), bad)
    }

    @Test fun malformedJsonGivesDefaults() {
        for (bad in listOf("", "not json", "[]", "\"#FFFFFF\"", "{\"background\":", "{background:\"#FFFFFF\"}")) {
            assertEquals(d, HomeTheme.parse(bad), bad)
        }
    }

    @Test fun oversizeInputIsNotParsed() {
        val big = """{"focusRing":"#00ff00"}""" + " ".repeat(HomeTheme.MAX_BYTES)
        assertEquals(d, HomeTheme.parse(big))
    }

    @Test fun boundsAreInclusive() {
        assertEquals(d.copy(tileWidthDp = 120, tileHeightDp = 270), HomeTheme.parse("""{"tileWidthDp":120,"tileHeightDp":270}"""))
    }
}
