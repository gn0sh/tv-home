package tvhome.core.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.apps.LauncherApp
import tvhome.core.runSuspend
import java.time.Instant
import java.time.ZoneId

internal fun fixture(name: String): String =
    AskTest::class.java.getResource("/ask/$name")!!.readText()

class AskTest {
    private val apps = askAppsOf(
        listOf(
            LauncherApp("com.netflix.ninja", "a", "Netflix"),
            LauncherApp("com.apple.atve.androidtv.appletv", "b", "Apple TV"),
            LauncherApp("com.google.android.youtube.tv", "c", "YouTube\u0007"),
        ),
    )

    // ---- the fake client and clock ----

    private class FakeClient(var replies: MutableList<ClaudeResult>) : ClaudeClient {
        val sent = ArrayList<List<Turn>>()
        val sentApps = ArrayList<List<AskApp>>()
        override suspend fun send(apps: List<AskApp>, turns: List<Turn>): ClaudeResult {
            sent += turns; sentApps += apps
            return replies.removeAt(0)
        }
    }

    private class MemCount : CountStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private var now = Instant.parse("2026-11-01T15:00:00Z")
    private val zone = ZoneId.of("America/New_York")
    private fun cap(store: CountStore = MemCount(), limit: Int = 20) = DailyCap(store, limit, { now }, { zone })

    private fun engine(vararg replies: ClaudeResult, hasKey: Boolean = true, cap: DailyCap = cap()) =
        FakeClient(replies.toMutableList()).let { it to AskEngine(it, { hasKey }, cap, { apps }) }

    private fun text(name: String) = ClaudeResult.Text(fixture(name))

    // ---- spec AC3 fixtures ----

    @Test fun exactTitleOpensWithDeepLink() {
        val (_, e) = engine(text("exact-title.json"))
        val a = runSuspend { e.ask("the new Stranger Things") } as AskOutcome.Answer
        assertTrue(a.reply.answer.startsWith("The new season"))
        assertEquals("com.netflix.ninja", a.reply.open!!.app.packageName)
        assertEquals("Stranger Things", a.reply.open!!.title)
        assertEquals("https://www.netflix.com/title/80057281", a.reply.open!!.deepLink)
        assertTrue(a.canFollowUp)
    }

    @Test fun vagueRequestOpensWithoutDeepLink() {
        val (_, e) = engine(text("vague.json"))
        val a = runSuspend { e.ask("something funny under 30 minutes") } as AskOutcome.Answer
        assertEquals("Ted Lasso", a.reply.open!!.title)
        assertNull(a.reply.open!!.deepLink)
    }

    @Test fun notInstalledServiceSaysSoAndOffersAnInstalledAlternative() {
        val (_, e) = engine(text("not-installed.json"))
        val a = runSuspend { e.ask("play The Bear") } as AskOutcome.Answer
        assertTrue("isn't on your TV" in a.reply.answer)
        assertEquals("com.netflix.ninja", a.reply.open!!.app.packageName)
    }

    @Test fun aPackageThatIsNotInstalledIsDroppedAndNothingCanLaunch() {
        val r = AskReply.parse(fixture("uninstalled-package.json"), apps)!!
        assertEquals("The Bear is on Hulu.", r.answer)
        assertNull(r.open)
    }

    @Test fun malformedOrEmptyReplyIsUnworkable() {
        for (f in listOf("malformed.txt", "empty.txt")) {
            val (_, e) = engine(text(f))
            assertEquals(AskOutcome.Unworkable, runSuspend { e.ask("anything") }, f)
        }
        assertNull(AskReply.parse("[]", apps))
        assertNull(AskReply.parse("""{"answer": 3, "open": null}""", apps))
        assertNull(AskReply.parse("""{"answer": "   ", "open": null}""", apps))
        assertNull(AskReply.parse("""{"open": null}""", apps))
    }

    @Test fun generalQuestionHasNoOpen() {
        val r = AskReply.parse(fixture("general.json"), apps)!!
        assertNull(r.open)
        assertTrue(r.answer.contains("metres"))
    }

    @Test fun invalidDeepLinkFallsBackToSearchNotLaunch() {
        val r = AskReply.parse(fixture("bad-link.json"), apps)!!
        assertNotNull(r.open)
        assertNull(r.open!!.deepLink)
        assertNull(AskReply.validDeepLink("http://www.netflix.com/title/1"))
        assertNull(AskReply.validDeepLink("javascript:alert(1)"))
        assertNull(AskReply.validDeepLink("https://www.netflix.com/title/1\nx"))
        assertNull(AskReply.validDeepLink("https://" + "a".repeat(600)))
        assertEquals("https://tv.apple.com/show/ted-lasso", AskReply.validDeepLink(" https://tv.apple.com/show/ted-lasso "))
    }

    @Test fun badOpenShapesAreDropped() {
        val base = """{"answer":"ok","open":%s}"""
        for (open in listOf(
            "\"com.netflix.ninja\"",
            """{"app_package":"com.netflix.ninja"}""",
            """{"app_package":"com.netflix.ninja","title":"  ","deep_link":null}""",
            """{"app_package":"com.netflix.ninja","title":"X","deep_link":5}""",
            """{"app_package":5,"title":"X","deep_link":null}""",
        )) {
            val r = AskReply.parse(base.format(open), apps)
            assertNotNull(r, open)
            assertNull(r!!.open, open)
        }
    }

    @Test fun refusalIsUnworkableAndErrorsAreCantReach() {
        val (_, e1) = engine(ClaudeResult.Failed(ClaudeError.REFUSED))
        assertEquals(AskOutcome.Unworkable, runSuspend { e1.ask("x") })
        for (err in listOf(ClaudeError.NETWORK, ClaudeError.HTTP_STATUS, ClaudeError.TIMEOUT)) {
            val (_, e) = engine(ClaudeResult.Failed(err))
            assertEquals(AskOutcome.CantReach, runSuspend { e.ask("x") }, err.name)
        }
    }

    @Test fun httpErrorsMapToTheirOwnCopyAndRefusedRequestsAreNotCharged() {
        val cases = mapOf(
            ClaudeError.KEY_REJECTED to (AskOutcome.KeyRejected to 0),
            ClaudeError.BAD_REQUEST to (AskOutcome.RequestRejected to 0),
            ClaudeError.NO_KEY to (AskOutcome.NotSetUp to 0),
            ClaudeError.BUSY to (AskOutcome.Busy to 1),
            ClaudeError.HTTP_STATUS to (AskOutcome.CantReach to 1),
            ClaudeError.NETWORK to (AskOutcome.CantReach to 1),
            ClaudeError.TIMEOUT to (AskOutcome.CantReach to 1),
        )
        for ((err, want) in cases) {
            val c = cap(MemCount())
            val (_, e) = engine(ClaudeResult.Failed(err), cap = c)
            assertEquals(want.first, runSuspend { e.ask("x") }, err.name)
            assertEquals(want.second, c.used(), "cap charged for $err")
        }
    }

    @Test fun refundNeverGoesBelowZeroAndOnlyTouchesToday() {
        val store = MemCount().apply { text = "2026-10-31 5" }
        val c = cap(store)
        c.refund()
        assertEquals("2026-10-31 5", store.text)
        assertTrue(c.tryConsume())
        c.refund()
        assertEquals(0, c.used())
        c.refund()
        assertEquals("2026-11-01 0", store.text)
    }

    @Test fun theKeyIsCheckedOncePerVisitOffTheCallingThread() {
        var checks = 0
        var on: Thread? = null
        val client = FakeClient(mutableListOf(text("vague.json"), text("general.json")))
        val e = AskEngine(client, { checks++; on = Thread.currentThread(); true }, cap(), { apps })
        val caller = Thread.currentThread()
        assertTrue(runSuspend { e.checkKey() })
        runSuspend { e.ask("one") }
        runSuspend { e.ask("two") }
        assertEquals(1, checks)
        assertNotSame(caller, on)
    }

    @Test fun noKeyIsNotSetUpAndNothingIsSentOrCounted() {
        val store = MemCount()
        val (client, e) = engine(hasKey = false, cap = cap(store))
        assertEquals(AskOutcome.NotSetUp, runSuspend { e.ask("hello") })
        assertTrue(client.sent.isEmpty())
        assertNull(store.text)
    }

    @Test fun emptyQuestionIsRefusedBeforeSending() {
        val (client, e) = engine()
        assertEquals(AskOutcome.EmptyQuestion, runSuspend { e.ask(" \u0000 ") })
        assertTrue(client.sent.isEmpty())
    }

    // ---- spec AC6: one follow-up ----

    @Test fun oneFollowUpCarriesTheHistoryThenASecondIsRefused() {
        val (client, e) = engine(text("vague.json"), text("general.json"))
        val first = runSuspend { e.ask("something funny") } as AskOutcome.Answer
        assertTrue(first.canFollowUp)
        val second = runSuspend { e.ask("something shorter") } as AskOutcome.Answer
        assertFalse(second.canFollowUp)
        assertEquals(listOf(Turn.Role.USER), client.sent[0].map { it.role })
        assertEquals(listOf(Turn.Role.USER, Turn.Role.ASSISTANT, Turn.Role.USER), client.sent[1].map { it.role })
        assertEquals("something funny", client.sent[1][0].text)
        assertEquals(fixture("vague.json"), client.sent[1][1].text)
        assertEquals(AskOutcome.FollowUpUsed, runSuspend { e.ask("and another") })
        assertEquals(2, client.sent.size)
    }

    @Test fun anErrorDoesNotUseUpATurnSoRetryResendsTheSameQuestion() {
        val (client, e) = engine(ClaudeResult.Failed(ClaudeError.NETWORK), text("general.json"), text("general.json"))
        assertEquals(AskOutcome.CantReach, runSuspend { e.ask("how tall") })
        val a = runSuspend { e.ask("how tall") } as AskOutcome.Answer
        assertTrue(a.canFollowUp)
        assertEquals(1, client.sent[1].size)
    }

    // ---- daily cap ----

    @Test fun dailyCapCountsCallsAndResetsTheNextDay() {
        val store = MemCount()
        val c = cap(store, limit = 2)
        assertTrue(c.tryConsume())
        assertTrue(c.tryConsume())
        assertFalse(c.tryConsume())
        assertEquals("2026-11-01 2", store.text)
        now = Instant.parse("2026-11-02T15:00:00Z")
        assertEquals(0, c.used())
        assertTrue(c.tryConsume())
        assertEquals("2026-11-02 1", store.text)
    }

    @Test fun dailyCapUsesLocalDateAndToleratesJunkButFailsClosedOnWriteErrors() {
        val store = MemCount().apply { text = "garbage" }
        assertTrue(cap(store).tryConsume())
        val broken = object : CountStore {
            override fun read(): String? = null
            override fun write(text: String) = throw java.io.IOException("disk")
        }
        assertFalse(cap(broken).tryConsume())
        now = Instant.parse("2026-11-02T03:00:00Z") // still 1 Nov in New York
        val s2 = MemCount()
        cap(s2).tryConsume()
        assertEquals("2026-11-01 1", s2.text)
    }

    @Test fun capReachedStopsBeforeSending() {
        val store = MemCount().apply { text = "2026-11-01 20" }
        val (client, e) = engine(text("general.json"), cap = cap(store))
        assertEquals(AskOutcome.CapReached, runSuspend { e.ask("hi") })
        assertTrue(client.sent.isEmpty())
    }

    @Test fun defaultsMatchTheAdr() {
        assertEquals("claude-opus-5-5", AskConfig.MODEL)
        assertEquals(20, AskConfig.DAILY_CAP)
        assertEquals(20_000L, AskConfig.TIMEOUT_MS)
    }

    // ---- request ----

    @Test fun requestHasModelEffortSchemaAndOnlyQuestionAndApps() {
        val body = Json.parseToJsonElement(requestBody(apps, listOf(Turn(Turn.Role.USER, "something funny")))).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        val oc = body["output_config"]!!.jsonObject
        assertEquals("low", oc["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", oc["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["stream"])
        assertNull(body["tools"])
        assertNull(body["thinking"])
        assertNull(body["temperature"])
        val system = body["system"]!!.jsonPrimitive.content
        assertTrue("- Netflix: com.netflix.ninja" in system)
        assertTrue("- YouTube: com.google.android.youtube.tv" in system, "control characters are stripped from labels")
        assertFalse(system.contains("calendar", ignoreCase = true), "never calendar data")
        val msgs = body["messages"]!!.jsonArray
        assertEquals(1, msgs.size)
        assertEquals("something funny", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
        val keys = body.keys
        assertEquals(setOf("model", "max_tokens", "system", "fallbacks", "output_config", "messages"), keys)
    }

    @Test fun schemaRestrictsPackagesToInstalledAppsAndIsClosed() {
        val s = replySchema(apps)
        assertEquals(false, s["additionalProperties"]!!.jsonPrimitive.content.toBoolean())
        val openObj = s["properties"]!!.jsonObject["open"]!!.jsonObject["anyOf"]!!.jsonArray[0].jsonObject
        val enum = openObj["properties"]!!.jsonObject["app_package"]!!.jsonObject["enum"] as JsonArray
        assertEquals(apps.map { it.packageName }, enum.map { it.jsonPrimitive.content })
        assertEquals("false", openObj["additionalProperties"]!!.jsonPrimitive.content)
        // No apps installed: no empty enum (an empty enum is an invalid schema).
        val none = replySchema(emptyList())["properties"]!!.jsonObject["open"]!!.jsonObject["anyOf"]!!.jsonArray[0]
            .jsonObject["properties"]!!.jsonObject["app_package"] as JsonObject
        assertNull(none["enum"])
    }

    @Test fun questionsAndLabelsAreCleanedAndBounded() {
        assertEquals("a b", cleanQuestion(" a\nb\u0000 "))
        assertEquals(500, cleanQuestion("x".repeat(900)).length)
        val many = askAppsOf((1..100).map { LauncherApp("p$it", "a", "L".repeat(200)) })
        assertEquals(60, many.size)
        assertEquals(60, many[0].label.length)
        assertEquals(1, askAppsOf(listOf(LauncherApp("p", "a", "A"), LauncherApp("p", "b", "B"))).size)
    }

    @Test fun toStringsHideText() {
        val r = AskReply.parse(fixture("exact-title.json"), apps)!!
        assertFalse("Stranger" in r.toString())
        assertFalse("secret" in Turn(Turn.Role.USER, "secret question").toString())
        assertFalse("hello" in ClaudeResult.Text("hello").toString())
    }
}
