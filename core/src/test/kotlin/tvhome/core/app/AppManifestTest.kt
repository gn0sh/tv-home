package tvhome.core.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * :app cannot be compiled where it was written (no Android SDK), so its security-relevant static facts are checked here
 * as plain files: manifest hardening (T5, `#10`, AC7), the release rules that strip Log (`#3`), and that every
 * resource the manifest names exists. CI's `aapt` checks (T6) then confirm the same on the built APK.
 */
class AppManifestTest {
    private val app = File("../app").canonicalFile
    private val ns = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        val f = File(app, "src/main/AndroidManifest.xml")
        assertTrue(f.isFile, "missing $f")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f).documentElement
    }

    private fun Element.attr(name: String): String? = getAttributeNS(ns, name).takeIf { it.isNotEmpty() }
    private fun Element.kids(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tag }.map { it as Element }

    private val application get() = manifest.kids("application").single()
    private fun component(tag: String, name: String) = application.kids(tag).single { it.attr("name") == name }

    @Test fun applicationIsHardened() {
        assertEquals("false", application.attr("allowBackup"))
        assertEquals("false", application.attr("usesCleartextTraffic"))
        assertNotNull(application.attr("dataExtractionRules"))
        assertNotNull(application.attr("fullBackupContent"))
        assertNotNull(application.attr("networkSecurityConfig"))
        assertNull(application.attr("debuggable"), "debuggable comes from the build type, never the manifest")
        assertNull(application.attr("requestLegacyExternalStorage"))
    }

    @Test fun onlyTheTwoNetworkPermissions() {
        val perms = manifest.kids("uses-permission").map { it.attr("name") }.toSet()
        assertEquals(setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"), perms)
    }

    /** M2: package visibility is only "apps with a TV launcher entry"; no <package> or <provider> entries. */
    @Test fun queriesOnlyLeanbackLauncherIntent() {
        val q = manifest.kids("queries").single()
        assertTrue(q.kids("package").isEmpty() && q.kids("provider").isEmpty())
        val intent = q.kids("intent").single()
        assertEquals(listOf("android.intent.action.MAIN"), intent.kids("action").map { it.attr("name") })
        assertEquals(listOf("android.intent.category.LEANBACK_LAUNCHER"), intent.kids("category").map { it.attr("name") })
        assertTrue(intent.kids("data").isEmpty())
    }

    /**
     * M3 (a hard stop): Home reaches the app only through HomeEntry, an alias that is OFF as installed, so an install
     * changes nothing until Brian runs the takeover script. HOME appears there and nowhere else.
     */
    @Test fun homeEntryIsADisabledAlias() {
        val alias = application.kids("activity-alias").single()
        assertEquals(".HomeEntry", alias.attr("name"))
        assertEquals(".AgendaActivity", alias.attr("targetActivity"))
        assertEquals("false", alias.attr("enabled"))
        assertEquals("true", alias.attr("exported"))
        val home = alias.kids("intent-filter").single()
        assertEquals(listOf("android.intent.action.MAIN"), home.kids("action").map { it.attr("name") })
        assertEquals(
            setOf("android.intent.category.HOME", "android.intent.category.DEFAULT"),
            home.kids("category").map { it.attr("name") }.toSet(),
        )
        val text = File(app, "src/main/AndroidManifest.xml").readText()
        assertEquals(1, Regex("android\\.intent\\.category\\.HOME").findAll(text).count())
    }

    /** M3 mic: key filtering only. Bound by the system only (BIND_ACCESSIBILITY_SERVICE), no window content. */
    @Test fun micServiceOnlyFiltersKeys() {
        val svc = application.kids("service").single()
        assertEquals(".MicKeyService", svc.attr("name"))
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", svc.attr("permission"))
        assertEquals(
            listOf("android.accessibilityservice.AccessibilityService"),
            svc.kids("intent-filter").single().kids("action").map { it.attr("name") },
        )
        assertEquals("@xml/mic_key_filter", svc.kids("meta-data").single().attr("resource"))
        val cfg = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(app, "src/main/res/xml/mic_key_filter.xml")).documentElement
        assertEquals("false", cfg.attr("canRetrieveWindowContent"))
        assertEquals("true", cfg.attr("canRequestFilterKeyEvents"))
        assertEquals("flagRequestFilterKeyEvents", cfg.attr("accessibilityFlags"))
        assertNull(cfg.attr("canPerformGestures"))
        assertNull(cfg.attr("canRequestTouchExplorationMode"))
    }

    @Test fun agendaIsAOnlyLeanbackLauncherEntry() {
        val a = component("activity", ".AgendaActivity")
        assertEquals("true", a.attr("exported"))
        val filter = a.kids("intent-filter").single()
        assertEquals(listOf("android.intent.action.MAIN"), filter.kids("action").map { it.attr("name") })
        assertEquals(listOf("android.intent.category.LEANBACK_LAUNCHER"), filter.kids("category").map { it.attr("name") })
        assertNotNull(application.attr("banner"))
        val leanback = manifest.kids("uses-feature").single { it.attr("name") == "android.software.leanback" }
        assertEquals("true", leanback.attr("required"))
    }

    /** `#10`: no intent-filter (nothing can reach it implicitly), DUMP guard, no recents entry, no history. */
    @Test fun provisionActivityIsLockedDown() {
        val p = component("activity", ".ProvisionActivity")
        assertTrue(p.kids("intent-filter").isEmpty())
        assertEquals("android.permission.DUMP", p.attr("permission"))
        assertEquals("true", p.attr("excludeFromRecents"))
        assertEquals("true", p.attr("noHistory"))
    }

    @Test fun provisionProviderIsGuardedByDump() {
        val p = component("provider", ".ProvisionProvider")
        assertEquals("android.permission.DUMP", p.attr("permission"))
        assertEquals("com.gnosh.tvhome.provision", p.attr("authorities"))
        assertEquals("false", p.attr("grantUriPermissions"))
    }

    @Test fun everyComponentIsListedAndAllElseIsAbsent() {
        assertEquals(
            setOf(".AgendaActivity", ".ProvisionActivity"),
            application.kids("activity").map { it.attr("name") }.toSet(),
        )
        assertEquals(listOf(".ProvisionProvider"), application.kids("provider").map { it.attr("name") })
        assertEquals(listOf(".MicKeyService"), application.kids("service").map { it.attr("name") })
        assertTrue(application.kids("receiver").isEmpty())
        assertEquals(listOf(".HomeEntry"), application.kids("activity-alias").map { it.attr("name") })
    }

    @Test fun referencedResourcesExist() {
        val res = File(app, "src/main/res")
        fun exists(ref: String?): Boolean {
            val r = ref ?: return false
            val (type, name) = r.removePrefix("@").split("/")
            return res.listFiles { f -> f.isDirectory && (f.name == type || f.name.startsWith("$type-")) }!!
                .any { d -> d.listFiles()!!.any { it.nameWithoutExtension == name } }
        }
        for (a in listOf("dataExtractionRules", "fullBackupContent", "networkSecurityConfig", "icon", "roundIcon", "banner")) {
            assertTrue(exists(application.attr(a)), "resource for $a: ${application.attr(a)}")
        }
        val banner = File(res, "drawable-xhdpi/banner.png").readBytes()
        assertEquals(320, java.nio.ByteBuffer.wrap(banner, 16, 4).int, "banner width")
        assertEquals(180, java.nio.ByteBuffer.wrap(banner, 20, 4).int, "banner height")
    }

    @Test fun networkConfigForbidsCleartext() {
        val text = File(app, "src/main/res/xml/network_security_config.xml").readText()
        assertTrue(text.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(text.contains("cleartextTrafficPermitted=\"true\""))
        assertFalse(text.contains("user\"")) // no user-installed CAs
    }

    @Test fun backupRulesExcludeEveryDomain() {
        for (f in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val text = File(app, "src/main/res/xml/$f").readText()
            for (d in listOf("root", "file", "database", "sharedpref", "external")) assertTrue(text.contains("domain=\"$d\""), "$f $d")
            assertFalse(text.contains("<include"), f)
        }
    }

    @Test fun releaseBuildStripsLogAndIsNotDebuggable() {
        val rules = File(app, "proguard-rules.pro").readText()
        assertTrue(rules.contains("-assumenosideeffects class android.util.Log"))
        for (m in listOf("v", "d", "i", "w", "e", "wtf", "println")) assertTrue(rules.contains(" $m(...)"), "Log.$m")
        val gradle = File(app, "build.gradle.kts").readText()
        assertTrue(gradle.contains("isDebuggable = false"))
        assertTrue(gradle.contains("isMinifyEnabled = true"))
        assertTrue(gradle.contains("minSdk = 31") && gradle.contains("targetSdk = 34"))
        assertFalse(gradle.contains("-P"), "no signing password on a command line")
    }

    /** No android.util.Log in app code (belt and braces with R8), and no secret-bearing text in app sources. */
    @Test fun appSourcesDoNotLogOrPrintValues() {
        val files = File(app, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size >= 7)
        for (f in files) {
            val t = f.readText()
            assertFalse(t.contains("android.util.Log"), "${f.name} uses Log")
            assertFalse(Regex("""\b(println|printStackTrace)\(""").containsMatchIn(t.replace("System.err.println(CrashSummary.of(t))", "")), "${f.name} prints")
            assertFalse(Regex("private-[0-9a-f]|sk-ant-").containsMatchIn(t), "${f.name} holds a secret pattern")
        }
    }

    @Test fun manifestDoesNotUseCommandLinePasswords() {
        assertFalse(File(app, "build.gradle.kts").readText().contains("storePassword = \""))
    }
}
