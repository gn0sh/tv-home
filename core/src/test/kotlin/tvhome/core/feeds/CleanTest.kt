package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CleanTest {
    @Test fun dropsBidiZeroWidthAndLoneSurrogates() {
        assertEquals("Blues", Feeds.clean("‮Bl​u⁦e⁩s‬"))
        assertEquals("ab", Feeds.clean("a\uD800b"))
        assertNull(Feeds.clean("‮​"))
    }

    @Test fun capsCombiningMarks() {
        val zalgo = "a" + "́".repeat(50) + "b"
        assertEquals("á́b", Feeds.clean(zalgo))
    }

    @Test fun cutOffNeverSplitsASurrogatePair() {
        val s = "x".repeat(39) + "😀" + "tail" // emoji straddles the 40-char cut
        val out = Feeds.clean(s)!!
        assertEquals(40, out.codePointCount(0, out.length))
        assertTrue(out.endsWith("😀"))
        assertFalse(out.any { Character.isSurrogate(it) && !(out.codePointAt(out.length - 2) > 0xFFFF) })
    }

    @Test fun staleLiveGameDoesNotPollFast() {
        val now = java.time.Instant.parse("2026-11-01T20:00:00Z")
        val stuck = TeamGame(Teams.PREDATORS, GameInfo(now.minus(java.time.Duration.ofHours(7)), "Blues", true, GamePhase.LIVE, null, null, null))
        assertEquals(FeedTiming.SCHEDULE_MS, FeedTiming.scheduleBase(listOf(stuck), now))
    }
}
