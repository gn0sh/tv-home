package tvhome.core.assistant

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.GameInfo
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.Stamped
import tvhome.core.feeds.TeamGame
import tvhome.core.feeds.Teams
import tvhome.core.feeds.WatchMap
import tvhome.core.feeds.WeatherNow
import tvhome.core.runSuspend
import tvhome.core.state.AgendaState
import tvhome.core.ui.GlanceInputs
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** M7-B10 (ADR 0008 section 5 as amended: placeholders, ledger, pinned model) and the B11 brief part. */
class BriefTest {
    private val zone = ZoneId.of("America/Chicago")
    private val mon = LocalDate.of(2026, 11, 2)
    private fun at(h: Int, m: Int = 0, day: LocalDate = mon) = day.atTime(h, m).atZone(zone).toInstant()
    private var now = at(7, 5)

    private class Mem(var text: String? = null, var failWrites: Boolean = false, var failReads: Boolean = false) : CountStore {
        var writes = 0
        override fun read(): String? { if (failReads) throw java.io.IOException(); return text }
        override fun write(text: String) { if (failWrites) throw java.io.IOException(); this.text = text; writes++ }
    }

    private class Counting(var reply: () -> ClaudeResult) : BriefClient {
        val bodies = mutableListOf<String>()
        override suspend fun post(body: String): ClaudeResult { bodies += body; return reply() }
    }

    private val secretTitles = listOf("Zygomatic-Surgery", "Quixotic Therapy With Dr Rao")
    private fun item(title: String, h: Int, m: Int, day: LocalDate) =
        AgendaItem(title, TimeLabel.Starts(LocalTime.of(h, m)), at(h, m, day), at(h, m, day).plusSeconds(1800))
    private fun agenda(t: Instant = now) = AgendaState.Ok(
        AgendaDay(mon, listOf(item(secretTitles[1], 17, 30, mon))),
        AgendaDay(mon.plusDays(1), listOf(item(secretTitles[0], 9, 0, mon.plusDays(1)))), t, "Updated just now",
    )
    private var temp = 55
    private fun inputs(quiet: Boolean = false, a: AgendaState = agenda()): GlanceInputs {
        val game = TeamGame(Teams.PREDATORS, GameInfo(at(19), "Blues", true, GamePhase.UPCOMING, null, null, null, listOf("ESPN+")))
        return GlanceInputs(
            now, zone, false,
            FeedsSnapshot(weather = Stamped(WeatherNow(temp, "Overcast", null, "rain after 3 PM", 63, 48), now), games = Stamped(listOf(game), now)),
            PanelSwitches(brief = true), true, a, quiet = quiet, watchMap = WatchMap.parse("ESPN+=com.espn.gtv", null),
        )
    }
    private fun facts(quiet: Boolean = false) = briefFacts(inputs(quiet))
    private fun reply(line: String, vararg uses: String) =
        ClaudeResult.Text(kotlinx.serialization.json.buildJsonObject {
            put("line", kotlinx.serialization.json.JsonPrimitive(line))
            put("uses", kotlinx.serialization.json.JsonArray(uses.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString())

    private fun engine(c: BriefClient, ledger: Mem = Mem(), cache: Mem = Mem(), on: Boolean = true, key: Boolean = true) =
        BriefEngine(c, BriefLedger(ledger, { now }, { zone }), cache, { now }, { on }, { key })

    // --- facts and request body -------------------------------------------------------------------------------------

    @Test fun factsHoldPlaceholdersAndNoTitles() {
        val f = facts()
        assertEquals(listOf("E1", "E2"), f.events.map { it.id })
        assertEquals("today 5:30 PM", f.events[0].whenText)
        assertEquals("tomorrow 9:00 AM", f.events[1].whenText)
        assertEquals("Predators vs Blues, 7:00 PM on ESPN+", f.game)
        val sent = f.sentJson().toString()
        for (t in secretTitles) assertFalse(sent.contains(t), "title in facts")
        assertFalse(f.toString().contains("Zygomatic"))
    }

    @Test fun requestBodyGoldenNoTitleNoFallbacksPinnedModelQuietOnOrOff() {
        for (quiet in listOf(false, true)) {
            val body = briefRequestBody(facts(quiet))
            for (t in secretTitles) for (part in t.split(' ', '-')) assertFalse(body.contains(part), "title part '$part' in body")
            assertFalse(body.contains("fallbacks"), "no fallbacks field")
            assertFalse(body.contains("location") || body.contains("36.5"), "no location")
            val o = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
            assertEquals(AskConfig.MODEL, (o["model"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals(1024, (o["max_tokens"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
            assertFalse(o.containsKey("tools"))
            assertEquals(1, (o["messages"] as kotlinx.serialization.json.JsonArray).size, "no history")
            assertTrue(body.contains(""""effort":"low""""))
        }
        assertEquals(briefRequestBody(facts(false)), briefRequestBody(facts(true)), "Quiet changes nothing that is sent")
    }

    // --- ledger -------------------------------------------------------------------------------------------------------

    @Test fun neverMoreThanTwoADayOr62AMonthAcrossDaysAndRestarts() {
        val store = Mem()
        var perDay = HashMap<LocalDate, Int>()
        var perMonth = HashMap<String, Int>()
        var day = LocalDate.of(2026, 10, 25)
        repeat(70) {
            for (h in 0 until 24) for (m in listOf(0, 30)) {
                now = at(h, m, day)
                val ledger = BriefLedger(store, { now }, { zone }) // a fresh object each tick = a process restart
                if (ledger.tryBegin()) {
                    perDay.merge(day, 1, Int::plus); perMonth.merge(day.toString().substring(0, 7), 1, Int::plus)
                }
            }
            day = day.plusDays(1)
        }
        assertTrue(perDay.values.all { it == 2 }, "exactly two slots a day: $perDay")
        assertTrue(perMonth.values.all { it <= 62 }, "$perMonth")
        assertEquals(62, perMonth["2026-12"], "December has 62 slots and all are allowed")
        assertTrue((perMonth["2026-10"] ?: 0) <= 14)
    }

    @Test fun theMonthlyLimitStopsAnEarlyMonthWithAHigherLimit() {
        val store = Mem()
        var granted = 0
        for (d in 1..28) for (h in listOf(8, 17)) {
            now = at(h, 0, LocalDate.of(2026, 11, d))
            if (BriefLedger(store, { now }, { zone }, limit = 10).tryBegin()) granted++
        }
        assertEquals(10, granted)
    }

    @Test fun slotIsRecordedBeforeTheCallAndAWriteFailureMeansNoCall() {
        val ledger = Mem(failWrites = true)
        val c = Counting { reply("x", "weather") }
        runSuspend { engine(c, ledger).refreshIfDue(facts()) }
        assertTrue(c.bodies.isEmpty(), "ledger unwritable: no call")
        ledger.failWrites = false
        var seenAtCall: String? = null
        val c2 = Counting { seenAtCall = ledger.text; ClaudeResult.Failed(ClaudeError.NETWORK) }
        runSuspend { engine(c2, ledger).refreshIfDue(facts()) }
        assertEquals("2026-11 1 2026-11-02T07", seenAtCall, "already written when the request goes out")
        runSuspend { engine(c2, ledger).refreshIfDue(facts()) }
        assertEquals(1, c2.bodies.size, "an error spent the slot: no retry")
    }

    @Test fun ledgerFailsClosedOnUnreadableAndMalformedFiles() {
        for (bad in listOf("garbage", "2026-11 x -", "2026-11 5", "2026-11 -3 -", "2026-11 5 nonsense", "")) {
            val s = Mem(bad)
            assertFalse(BriefLedger(s, { now }, { zone }).tryBegin(), "'$bad'")
            assertEquals("2026-11 62 -", s.text, "rewritten as exhausted")
        }
        val unreadable = Mem("2026-11 1 -", failReads = true)
        assertFalse(BriefLedger(unreadable, { now }, { zone }).tryBegin())
        // Heals next month.
        val s = Mem("2026-11 62 -")
        now = at(8, 0, LocalDate.of(2026, 12, 1))
        assertTrue(BriefLedger(s, { now }, { zone }).tryBegin())
    }

    @Test fun aStoredMonthOrSlotLaterThanNowMeansNoCallAndNoRewrite() {
        for (stored in listOf("2026-12 3 2026-12-01T07", "2026-11 3 2026-11-03T07", "2026-11 3 2026-11-02T16")) {
            now = at(8, 0)
            val s = Mem(stored)
            assertFalse(BriefLedger(s, { now }, { zone }).tryBegin(), stored)
            assertEquals(stored, s.text)
        }
    }

    @Test fun noSlotBeforeSevenAndTheSecondSlotAtFour() {
        val s = Mem()
        now = at(6, 59); assertFalse(BriefLedger(s, { now }, { zone }).tryBegin())
        now = at(7, 0); assertTrue(BriefLedger(s, { now }, { zone }).tryBegin())
        now = at(15, 59); assertFalse(BriefLedger(s, { now }, { zone }).tryBegin())
        now = at(16, 0); assertTrue(BriefLedger(s, { now }, { zone }).tryBegin())
        now = at(23, 0); assertFalse(BriefLedger(s, { now }, { zone }).tryBegin())
    }

    // --- engine outcomes ------------------------------------------------------------------------------------------------

    @Test fun switchOffOrNoKeyMakesNoRequestAndSpendsNoSlot() {
        val c = Counting { reply("x", "weather") }
        val ledger = Mem()
        runSuspend { engine(c, ledger, on = false).refreshIfDue(facts()) }
        runSuspend { engine(c, ledger, key = false).refreshIfDue(facts()) }
        assertTrue(c.bodies.isEmpty()); assertNull(ledger.text)
        assertNotNull(engine(c, on = false).line(facts(), false), "off: the free template still fills the slot")
    }

    @Test fun everyFailureShowsTheTemplateAndSpendsTheSlotOnce() {
        val cases: List<() -> ClaudeResult> = listOf(
            { ClaudeResult.Failed(ClaudeError.NETWORK) }, { ClaudeResult.Failed(ClaudeError.TIMEOUT) }, { ClaudeResult.Failed(ClaudeError.REFUSED) },
            { ClaudeResult.Failed(ClaudeError.NO_KEY) }, { ClaudeResult.Failed(ClaudeError.BAD_RESPONSE) },
            { ClaudeResult.Text("") }, { ClaudeResult.Text("not json") }, { reply("") }, { reply("x".repeat(300), "weather") },
            { reply("See https://evil.example/x {E4}", "weather") }, { reply("ok {E3}", "E3") }, { reply("ok", "bogus") },
            { reply("{X} brace", "weather") }, { reply("stray } brace", "weather") },
        )
        for ((i, r) in cases.withIndex()) {
            val c = Counting(r); val cache = Mem()
            val e = engine(c, Mem(), cache)
            runSuspend { e.refreshIfDue(facts()) }
            assertEquals(1, c.bodies.size, "case $i")
            assertNull(cache.text, "case $i: nothing cached")
            assertEquals(templateLine(facts(), false), e.line(facts(), false), "case $i")
        }
    }

    @Test fun aThrowingClientIsAnErrorAndCancellationPropagatesWithTheSlotSpent() {
        val ledger = Mem()
        val boom = Counting { throw IllegalStateException("boom") }
        runSuspend { engine(boom, ledger).refreshIfDue(facts()) }
        assertEquals(1, boom.bodies.size)
        val ledger2 = Mem()
        val cancel = Counting { throw CancellationException("c") }
        assertThrows(CancellationException::class.java) { runSuspend { engine(cancel, ledger2).refreshIfDue(facts()) } }
        assertEquals("2026-11 1 2026-11-02T07", ledger2.text, "no refund")
        runSuspend { engine(cancel, ledger2).refreshIfDue(facts()) }
        assertEquals(1, cancel.bodies.size)
    }

    @Test fun countingClientNeverSeesMoreThanTwoCallsADayOver40Days() {
        val ledger = Mem(); val cache = Mem()
        val c = Counting { reply("Rain after 3", "rain") }
        var day = mon
        repeat(40) {
            for (h in 0 until 24) {
                now = at(h, 15, day)
                runSuspend { engine(c, ledger, cache).refreshIfDue(facts()) } // new engine each tick: a restart
            }
            day = day.plusDays(1)
        }
        assertEquals(80, c.bodies.size, "40 days x 2 slots; November 2-30 = 29 days (58) + December 1-11 = 11 days (22), both under 62")
    }

    // --- cache, fingerprint, fill -----------------------------------------------------------------------------------------

    @Test fun successIsCachedWithoutTitlesAndFilledLocallyWithBusyInQuiet() {
        val cache = Mem()
        val c = Counting { reply("Rain after 3, {E1} at 5:30, {E2} tomorrow", "rain", "E1", "E2") }
        val e = engine(c, Mem(), cache)
        runSuspend { e.refreshIfDue(facts()) }
        for (t in secretTitles) assertFalse(cache.text!!.contains(t), "no title in the cache")
        assertEquals("Rain after 3, Quixotic Therapy With at 5:30, Zygomatic-Surgery tomorrow", e.line(facts(), false))
        assertEquals("Rain after 3, Busy at 5:30, Busy tomorrow", e.line(facts(), true))
        // Survives a restart (new engine, same files).
        assertEquals("Rain after 3, Busy at 5:30, Busy tomorrow", engine(c, Mem("x"), cache).line(facts(), true))
    }

    @Test fun aTemperatureChangeKeepsALineThatDoesNotNameItAndAPassedEventDropsIt() {
        val cache = Mem()
        runSuspend { engine(Counting { reply("Rain after 3 PM, {E1}", "rain", "E1") }, Mem(), cache).refreshIfDue(facts()) }
        val e = engine(Counting { reply("") }, Mem("x"), cache)
        temp = 40
        assertTrue(e.line(facts(), false)!!.startsWith("Rain after 3 PM, Quixotic"), "temperature not named: kept")
        // E1 (5:30 PM) has passed: the next event is now E1 with a different time.
        now = at(18, 0)
        val later = briefFacts(inputs(a = agenda(now)))
        assertEquals("tomorrow 9:00 AM", later.events[0].whenText)
        assertEquals(templateLine(later, false), e.line(later, false), "{E1} moved: template")
        // A line that names the temperature drops when it changes.
        val cache2 = Mem()
        now = at(7, 5); temp = 55
        runSuspend { engine(Counting { reply("It is cool", "temps") }, Mem(), cache2).refreshIfDue(facts()) }
        val e2 = engine(Counting { reply("") }, Mem("x"), cache2)
        assertEquals("It is cool", e2.line(facts(), false))
        temp = 54
        assertNotEquals("It is cool", e2.line(facts(), false))
    }

    @Test fun anAbsentNamedFactDropsTheLine() {
        val cache = Mem()
        runSuspend { engine(Counting { reply("Rain after 3 PM", "rain") }, Mem(), cache).refreshIfDue(facts()) }
        val dry = facts().copy(rain = null)
        assertNotEquals("Rain after 3 PM", engine(Counting { reply("") }, Mem("x"), cache).line(dry, false))
    }

    @Test fun aTamperedCacheIsIgnored() {
        val cache = Mem()
        runSuspend { engine(Counting { reply("Rain after 3 PM", "rain") }, Mem(), cache).refreshIfDue(facts()) }
        val good = cache.text!!
        for (bad in listOf(good.replace("Rain after 3 PM", "Visit evil.com now"), good.replace("Rain after 3 PM", "{E9}"), "{", good + "x".repeat(3000),
            good.replace(Regex("\"at\":\"[^\"]*\""), "\"at\":\"2099-01-01T00:00:00Z\""))) {
            val e = engine(Counting { reply("") }, Mem("x"), Mem(bad))
            assertEquals(templateLine(facts(), false), e.line(facts(), false), bad.take(40))
        }
    }

    @Test fun fillIsSinglePass() {
        val events = listOf(BriefEvent("E1", "Lunch {E2} plan", "today", false), BriefEvent("E2", "Dentist", "today", false))
        assertEquals("Lunch {E2} plan then Dentist", fillPlaceholders("{E1} then {E2}", events, false))
        assertEquals("Busy then Busy", fillPlaceholders("{E1} then {E2}", events, true))
        // And through the engine: the final line is not expanded a second time.
        val cache = Mem()
        val f = facts().copy(events = events)
        runSuspend { engine(Counting { reply("{E1} then {E2}", "E1", "E2") }, Mem(), cache).refreshIfDue(f) }
        assertEquals("Lunch {E2} plan then Dentist", engine(Counting { reply("") }, Mem("x"), cache).line(f, false))
    }

    @Test fun titlesAreCleanedCutAtAWordBoundaryAndTheLineIsCappedAt90() {
        assertEquals("Quixotic Therapy With", BriefText.title("Quixotic Therapy With Dr Rao", false))
        assertEquals("Supercalifragilisticexpi", BriefText.title("Supercalifragilisticexpialidocious", false))
        assertEquals("Hi there", BriefText.title("Hi\u0007‮ there", false))
        assertEquals("Call", BriefText.title("Call https://evil.example/x?y=1", false))
        assertEquals("Call", BriefText.title("Call www.evil.example", false))
        assertEquals("a b", BriefText.clean("a\u0000\n\t b"))
        assertEquals("ok", BriefText.clean("ok http://x.y/z"))
        assertEquals("go", BriefText.clean("go bit.ly/abc".replace("bit.ly", "bit.com")))
        // A filled line over 90 chars is not shown: the template is.
        val cache = Mem()
        val f = facts().copy(events = listOf(BriefEvent("E1", "Quixotic Therapy With Dr Rao", "today 5:30 PM", false)))
        val long = "x".repeat(75) + " {E1}"
        runSuspend { engine(Counting { reply(long, "E1") }, Mem(), cache).refreshIfDue(f) }
        assertEquals(templateLine(f, false), engine(Counting { reply("") }, Mem("x"), cache).line(f, false))
        assertTrue(templateLine(f, false)!!.length <= BriefConfig.MAX_LINE)
        assertTrue(templateLine(f.copy(game = "g".repeat(200)), false)!!.length <= BriefConfig.MAX_LINE)
    }

    @Test fun templateHasTheFactsAndBusyInQuiet() {
        val t = templateLine(facts(), false)!!
        assertEquals("Rain after 3 PM · Predators vs Blues, 7:00 PM on ESPN+", t, "the event part is dropped when the line would pass 90 characters")
        val withEvent = templateLine(facts().copy(game = null), false)!!
        assertEquals("Rain after 3 PM · Quixotic Therapy With today 5:30 PM", withEvent)
        val q = templateLine(facts(), true)!!
        assertEquals("Rain after 3 PM · Busy today 5:30 PM", templateLine(facts().copy(game = null), true))
        for (title in secretTitles) assertFalse(q.contains(title))
        assertNull(templateLine(BriefFacts("d", null, null, null, null, null, null, null, emptyList()), false))
    }

    @Test fun noCalendarMeansNoEventsAndQuietBriefsStillRun() {
        val f = briefFacts(inputs(a = AgendaState.NotSetUp))
        assertTrue(f.events.isEmpty())
        val stale = AgendaState.Ok(AgendaDay(mon, listOf(item("Secret", 17, 30, mon))), AgendaDay(mon.plusDays(1), emptyList()), now.minus(Duration.ofHours(4)), "x")
        assertTrue(briefFacts(inputs(a = stale)).events.isEmpty(), "calendar 3 h old offers nothing")
        // In Quiet the brief still makes its (title-free) call.
        val c = Counting { reply("Rain", "rain") }
        runSuspend { engine(c).refreshIfDue(facts(quiet = true)) }
        assertEquals(1, c.bodies.size)
    }
}
