package tvhome.core.apps

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContinueWatchingTest {
    private val own = "com.gnosh.tvhome"
    private val netflix = "com.netflix.ninja"
    private val yt = "com.google.android.youtube.tv"
    private val launcher = setOf(netflix, yt, "com.android.tv.settings", own)

    @Test fun theLastUsedStreamingAppIsTheTile() {
        val recent = listOf(
            RecentUse(own, 900), RecentUse("com.android.tv.settings", 800),
            RecentUse(yt, 500), RecentUse(netflix, 300), RecentUse("com.not.in.launcher", 950),
        )
        assertEquals(ContinueTile(yt), ContinueChoice.choose(recent, launcher, own))
        assertEquals(ContinueTile(netflix), ContinueChoice.choose(recent + RecentUse(netflix, 600), launcher, own))
    }

    /** No UsageStats access (recent == null), or no qualifying app: no tile, never an error tile. */
    @Test fun nothingKnownMeansNoTile() {
        assertNull(ContinueChoice.choose(null, launcher, own))
        assertNull(ContinueChoice.choose(emptyList(), launcher, own))
        assertNull(ContinueChoice.choose(listOf(RecentUse(own, 5), RecentUse("com.android.tv.settings", 4), RecentUse(yt, 0)), launcher, own))
    }

    /** continue-hidden-app: an app Brian hid is never the Continue tile. */
    @Test fun hiddenAppsAreSkipped() {
        val recent = listOf(RecentUse(netflix, 400), RecentUse(yt, 300))
        assertEquals(ContinueTile(yt), ContinueChoice.choose(recent, launcher, own, hidden = setOf(netflix)))
        assertNull(ContinueChoice.choose(recent, launcher, own, hidden = setOf(netflix, yt)))
    }

    @Test fun description() {
        assertEquals("Continue in Netflix", ContinueChoice.description(ContinueTile(netflix)) { "Netflix" })
        assertEquals("Continue in the app you used last", ContinueChoice.description(ContinueTile(netflix)) { null })
    }
}
