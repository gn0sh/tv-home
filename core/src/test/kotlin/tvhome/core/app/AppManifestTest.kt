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

    /**
     * M2: package visibility is "apps with a TV launcher entry", plus (M3) the TV's home activities for
     * HomeWatchService; no <package> or <provider> entries.
     */
    @Test fun queriesOnlyLeanbackLauncherAndHomeIntents() {
        val q = manifest.kids("queries").single()
        assertTrue(q.kids("package").isEmpty() && q.kids("provider").isEmpty())
        val intents = q.kids("intent")
        intents.forEach {
            assertEquals(listOf("android.intent.action.MAIN"), it.kids("action").map { a -> a.attr("name") })
            assertTrue(it.kids("data").isEmpty())
        }
        assertEquals(
            listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.HOME"),
            intents.map { it.kids("category").single().attr("name") },
        )
    }

    /** No HOME intent filter on any component (home-role-priority): HOME appears only in <queries>. */
    @Test fun noHomeCategory() {
        assertEquals(1, Regex("android\\.intent\\.category\\.HOME").findAll(File(app, "src/main/AndroidManifest.xml").readText()).count())
        (application.kids("activity") + application.kids("activity-alias")).forEach { c ->
            c.kids("intent-filter").forEach { f ->
                assertFalse(f.kids("category").any { it.attr("name") == "android.intent.category.HOME" })
            }
        }
    }

    /** M3 Home watch: window-state events only, bound by the system only, no window content, no key filtering. */
    @Test fun homeWatchOnlySeesWindowChanges() {
        val svc = component("service", ".HomeWatchService")
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", svc.attr("permission"))
        assertEquals(
            listOf("android.accessibilityservice.AccessibilityService"),
            svc.kids("intent-filter").single().kids("action").map { it.attr("name") },
        )
        assertEquals("@xml/home_watch", svc.kids("meta-data").single().attr("resource"))
        val cfg = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(app, "src/main/res/xml/home_watch.xml")).documentElement
        assertEquals("false", cfg.attr("canRetrieveWindowContent"))
        assertNull(cfg.attr("canRequestFilterKeyEvents"))
        assertNull(cfg.attr("accessibilityFlags"))
        assertNull(cfg.attr("canPerformGestures"))
        assertEquals("typeWindowStateChanged", cfg.attr("accessibilityEventTypes"))
        assertEquals(":mic", svc.attr("process"))
        assertNull(cfg.attr("canRequestTouchExplorationMode"))
    }

    /** a11y-event-breadth: the Home watch reads only an event's type, package and class, never its text or source. */
    @Test fun homeWatchReadsNoEventContent() {
        val src = File(app, "src/main/kotlin/com/gnosh/tvhome/HomeWatchService.kt").readText()
        for (bad in listOf(".text", "getText", ".source", "getSource", "contentDescription", "rootInActiveWindow", "windows", "Log.")) {
            assertFalse(src.contains(bad), "HomeWatchService must not use $bad")
        }
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
            setOf(".AgendaActivity", ".ProvisionActivity", ".AssistActivity"),
            application.kids("activity").map { it.attr("name") }.toSet(),
        )
        assertEquals(listOf(".ProvisionProvider"), application.kids("provider").map { it.attr("name") })
        assertEquals(
            setOf(".HomeWatchService", ".ClaudeVoiceService", ".ClaudeSessionService", ".ClaudeRecognitionService"),
            application.kids("service").map { it.attr("name") }.toSet(),
        )
        assertTrue(application.kids("receiver").isEmpty())
        assertEquals(listOf(".AskClaudeEntry"), application.kids("activity-alias").map { it.attr("name") })
    }

    // ---- mic-to-claude ---------------------------------------------------------------------------------------------

    /** The "Ask Claude" launcher entry: an alias of the main activity, its own label and 320x180 banner. */
    @Test fun askClaudeEntryIsALauncherAlias() {
        val a = component("activity-alias", ".AskClaudeEntry")
        assertEquals(".AgendaActivity", a.attr("targetActivity"))
        assertEquals("true", a.attr("exported"))
        val f = a.kids("intent-filter").single()
        assertEquals(listOf("android.intent.action.MAIN"), f.kids("action").map { it.attr("name") })
        assertEquals(listOf("android.intent.category.LEANBACK_LAUNCHER"), f.kids("category").map { it.attr("name") })
        assertEquals("@drawable/banner_ask", a.attr("banner"))
        val png = File(app, "src/main/res/drawable-xhdpi/banner_ask.png").readBytes()
        assertEquals(320, java.nio.ByteBuffer.wrap(png, 16, 4).int); assertEquals(180, java.nio.ByteBuffer.wrap(png, 20, 4).int)
        val strings = File(app, "src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"ask_entry_label\">Ask Claude</string>"))
        // The alias name EntryRoute routes to Ask.
        assertEquals("com.gnosh.tvhome" + a.attr("name"), tvhome.core.assistant.EntryRoute.ASK_ENTRY)
    }

    /** The assist entry: only ASSIST and VOICE_COMMAND, no UI, no recents, no history. */
    @Test fun assistActivityOnlyTakesAssistAndVoiceCommand() {
        val a = component("activity", ".AssistActivity")
        assertEquals(
            tvhome.core.assistant.EntryRoute.ASSIST_ACTIONS,
            a.kids("intent-filter").flatMap { it.kids("action") }.map { it.attr("name") }.toSet(),
        )
        a.kids("intent-filter").forEach { assertEquals(listOf("android.intent.category.DEFAULT"), it.kids("category").map { c -> c.attr("name") }) }
        assertEquals("@android:style/Theme.NoDisplay", a.attr("theme"))
        assertEquals("true", a.attr("excludeFromRecents")); assertEquals("true", a.attr("noHistory"))
    }

    /** The voice services: bindable only by the system, assist only (no hotword), pointing at declared classes. */
    @Test fun voiceServicesAreSystemBoundAndAssistOnly() {
        for (n in listOf(".ClaudeVoiceService", ".ClaudeSessionService")) {
            assertEquals("android.permission.BIND_VOICE_INTERACTION", component("service", n).attr("permission"), n)
        }
        val v = component("service", ".ClaudeVoiceService")
        assertEquals(listOf("android.service.voice.VoiceInteractionService"), v.kids("intent-filter").single().kids("action").map { it.attr("name") })
        assertEquals("@xml/voice_interaction", v.kids("meta-data").single().attr("resource"))
        val cfg = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(app, "src/main/res/xml/voice_interaction.xml")).documentElement
        assertEquals("com.gnosh.tvhome.ClaudeSessionService", cfg.attr("sessionService"))
        assertEquals("com.gnosh.tvhome.ClaudeRecognitionService", cfg.attr("recognitionService"))
        assertEquals("true", cfg.attr("supportsAssist"))
        assertEquals("false", cfg.attr("supportsLaunchVoiceAssistFromKeyguard"))
        assertEquals("false", cfg.attr("supportsLocalInteraction"))
        for (bad in listOf("settingsActivity", "supportsHotwordDetection")) assertNull(cfg.attr(bad), bad)
        val r = component("service", ".ClaudeRecognitionService")
        assertEquals(listOf("android.speech.RecognitionService"), r.kids("intent-filter").single().kids("action").map { it.attr("name") })
        assertEquals("@xml/recognition_service", r.kids("meta-data").single().attr("resource"))
        // No hotword or audio capture anywhere in the app code.
        val src = File(app, "src/main/kotlin/com/gnosh/tvhome/ClaudeVoiceService.kt").readText()
        for (bad in listOf("AlwaysOnHotwordDetector", "createAlwaysOnHotwordDetector", "AudioRecord", "MediaRecorder")) assertFalse(src.contains(bad), bad)
        for (x in listOf("voice_interaction", "recognition_service")) assertTrue(File(app, "src/main/res/xml/$x.xml").isFile)
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
