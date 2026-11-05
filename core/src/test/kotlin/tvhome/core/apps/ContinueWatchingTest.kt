package tvhome.core.apps

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.assistant.AskApp
import tvhome.core.assistant.CountStore
import tvhome.core.assistant.OpenTarget

class ContinueWatchingTest {
    private class Mem(var text: String? = null) : CountStore {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }
    private var now = 1_000L
    private val netflix = AskApp("Netflix", "com.netflix.ninja")
    private val yt = AskApp("YouTube", "com.google.android.youtube.tv")
    private fun pick(title: String, app: AskApp = netflix, link: String? = null) = OpenTarget(app, title, link)
    private val own = "com.gnosh.tvhome"
    private val launcher = setOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.android.tv.settings", own)

    @Test fun recordsNewestFirstKeepsThreeAndDedupes() {
        val p = ContinuePicks(Mem()) { now++ }
        p.record(pick("A")); p.record(pick("B")); p.record(pick("C", yt)); p.record(pick("A")); p.record(pick("D"))
        assertEquals(listOf("D", "A", "C"), p.read().map { it.target.title })
    }

    @Test fun roundTripsTheDeepLinkAndDropsInvalidOnes() {
        val m = Mem()
        val p = ContinuePicks(m) { now }
        p.record(pick("Show", link = "https://www.netflix.com/title/80057281"))
        assertEquals("https://www.netflix.com/title/80057281", p.read().single().target.deepLink)
        m.text = """[{"t":"X","p":"com.netflix.ninja","l":"Netflix","d":"intent://evil#Intent;end","at":1}]"""
        assertTrue(p.read().isEmpty(), "a non-https link drops the entry")
    }

    @Test fun unreadableOrHostileFilesAreEmpty() {
        val m = Mem()
        val p = ContinuePicks(m) { now }
        for (bad in listOf("", "not json", "{}", """[{"t":"X"}]""", """[{"t":" ","p":"com.a.b","l":"A"}]""",
            """[{"t":"X","p":"no package","l":"A"}]""", "[" + "1,".repeat(3000) + "1]")) {
            m.text = bad
            assertTrue(p.read().isEmpty(), bad.take(30))
        }
        m.text = """[{"t":"Ti\u0007tle","p":"com.netflix.ninja","l":"Netflix","at":5}]"""
        assertEquals("Title", p.read().single().target.title)
    }

    @Test fun aFailingStoreNeverThrows() {
        val broken = object : CountStore {
            override fun read(): String? = throw java.io.IOException("x")
            override fun write(text: String) = throw java.io.IOException("x")
        }
        val p = ContinuePicks(broken) { now }
        p.record(pick("A"))
        assertTrue(p.read().isEmpty())
    }

    @Test fun pickWinsWhenItsAppIsInstalled() {
        val picks = listOf(ContinuePicks.Entry(pick("Gone", AskApp("Old", "com.old.app")), 2), ContinuePicks.Entry(pick("Stranger Things"), 1))
        val t = ContinueChoice.choose(picks, recent = null, launcherPkgs = launcher, ownPackage = own)
        assertEquals("Stranger Things", (t as ContinueTile.Pick).target.title)
    }

    @Test fun noPickFallsBackToTheLastStreamingApp() {
        val recent = listOf(
            RecentUse(own, 900), RecentUse("com.android.tv.settings", 800),
            RecentUse("com.google.android.youtube.tv", 500), RecentUse("com.netflix.ninja", 300), RecentUse("com.not.in.launcher", 950),
        )
        assertEquals(ContinueTile.App("com.google.android.youtube.tv"), ContinueChoice.choose(emptyList(), recent, launcher, own))
    }

    /** No UsageStats access (recent == null) and no pick: no tile, never an error tile. */
    @Test fun nothingKnownMeansNoTile() {
        assertNull(ContinueChoice.choose(emptyList(), null, launcher, own))
        assertNull(ContinueChoice.choose(emptyList(), emptyList(), launcher, own))
        assertNull(ContinueChoice.choose(emptyList(), listOf(RecentUse(own, 5), RecentUse("com.android.tv.settings", 4)), launcher, own))
    }

    @Test fun descriptionsAndNoTitleInToString() {
        val p = ContinueTile.Pick(pick("Severance"))
        assertEquals("Continue: Severance, in Netflix", ContinueChoice.description(p) { null })
        assertEquals("Continue in YouTube", ContinueChoice.description(ContinueTile.App(yt.packageName)) { "YouTube" })
        assertEquals("Continue in Netflix", ContinueChoice.description(p, hideTitle = true) { null })
        assertFalse(p.toString().contains("Severance"))
        assertFalse(ContinuePicks.Entry(pick("Severance"), 1).toString().contains("Severance"))
    }
}
