package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.Fixtures
import java.time.Instant

class NwsAlertsTest {
    private val now = Instant.parse("2026-04-15T04:20:00Z") // 11:20 PM CDT, 14 Apr

    private fun doc(vararg props: String) =
        """{"type":"FeatureCollection","features":[${props.joinToString(",") { """{"type":"Feature","properties":{$it}}""" }}]}"""
    private fun p(event: String, severity: String = "Severe", type: String = "Alert", status: String = "Actual",
                  ends: String? = "\"2026-04-15T06:00:00Z\"", expires: String? = "\"2026-04-15T06:00:00Z\"") =
        """"event":${kotlinx.serialization.json.JsonPrimitive(event)},"severity":"$severity","messageType":"$type","status":"$status","ends":$ends,"expires":$expires"""

    @Test fun urlUsesTwoDecimalsAndThePointFilter() {
        assertEquals("https://api.weather.gov/alerts/active?point=36.53,-87.36", NwsAlerts.url(Location(36.5312, -87.3591)).toString())
        assertTrue(HttpFeedFetcher.isAllowed(NwsAlerts.url(Location(36.53, -87.36))))
    }

    /** Synthetic: the real captures hold no Warning. Same shape as nws-alerts-area.json. */
    private val warningDoc = doc(p("Tornado Warning", "Extreme", ends = "\"2026-04-15T04:45:00Z\""),
        p("Flood Watch", ends = "\"2026-04-15T12:00:00Z\"", expires = "\"2026-04-15T06:00:00Z\""),
        p("Wind Advisory", "Moderate", ends = null, expires = "\"2026-04-15T08:00:00Z\""))

    @Test fun realTexasCaptureWithActiveAlerts() {
        val at = Instant.parse("2026-11-02T21:36:00Z")
        val s = NwsAlerts.parse(Fixtures.text("nws-alerts-area.json"), at)!!
        assertEquals(listOf("Tornado Watch", "Wind Advisory"), s.alerts.map { it.event })
        assertEquals(listOf("Extreme", "Moderate"), s.alerts.map { it.severity })
        assertEquals(listOf(AlertKind.SUFFIX, AlertKind.SUFFIX), s.alerts.map { it.kind }, "an Extreme Watch is still a chip suffix")
        assertNull(s.override)
        assertEquals("Tornado Watch +1", s.suffixText())
        assertEquals(Instant.parse("2026-11-03T04:00:00Z"), s.alerts[0].until)
        assertEquals(Instant.parse("2026-11-03T10:00:00Z"), s.alerts[1].until, "ends (later than expires) wins")
        // After the watch ends only the advisory is left.
        assertEquals(listOf("Wind Advisory"), NwsAlerts.parse(Fixtures.text("nws-alerts-area.json"), Instant.parse("2026-11-03T04:00:00Z"))!!.alerts.map { it.event })
    }

    @Test fun realPointCaptureWithNothingActive() {
        assertEquals(AlertSet(emptyList()), NwsAlerts.parse(Fixtures.text("nws-alerts-point.json"), now))
    }

    @Test fun syntheticWarning() {
        val s = NwsAlerts.parse(warningDoc, now)!!
        assertEquals(listOf("Tornado Warning", "Flood Watch", "Wind Advisory"), s.alerts.map { it.event })
        assertEquals(listOf(AlertKind.OVERRIDE, AlertKind.SUFFIX, AlertKind.SUFFIX), s.alerts.map { it.kind })
        assertEquals(Instant.parse("2026-04-15T04:45:00Z"), s.override!!.until)
        assertEquals(Instant.parse("2026-04-15T12:00:00Z"), s.alerts[1].until, "ends wins over expires")
        assertEquals(Instant.parse("2026-04-15T08:00:00Z"), s.alerts[2].until, "null ends falls back to expires")
    }

    @Test fun classifyTable() {
        val table = listOf(
            Triple("Tornado Warning", "Extreme", AlertKind.OVERRIDE),
            Triple("Tornado Warning", "Severe", AlertKind.OVERRIDE),
            Triple("Severe Thunderstorm Warning", "Severe", AlertKind.OVERRIDE),
            Triple("Flash Flood Warning", "Severe", AlertKind.OVERRIDE),
            Triple("Flood Warning", "Moderate", AlertKind.SUFFIX),
            Triple("Tornado Watch", "Severe", AlertKind.SUFFIX),
            Triple("Severe Thunderstorm Watch", "Severe", AlertKind.SUFFIX),
            Triple("Flood Watch", "Severe", AlertKind.SUFFIX),
            Triple("Wind Advisory", "Moderate", AlertKind.SUFFIX),
            Triple("Heat Advisory", "Severe", AlertKind.SUFFIX),
            Triple("Special Weather Statement", "Moderate", AlertKind.SUFFIX),
            Triple("Hazardous Weather Outlook", "Unknown", AlertKind.SUFFIX),
            Triple("Extreme Wind Warning", "Extreme", AlertKind.OVERRIDE),
            Triple("Civil Danger Emergency", "Extreme", AlertKind.OVERRIDE),
            Triple("Tornado Emergency", "extreme", AlertKind.OVERRIDE),
            Triple("Unheard Of Hazard", "Extreme", AlertKind.OVERRIDE), // unknown Extreme event overrides
            Triple("Unheard Of Hazard", "Severe", AlertKind.SUFFIX),
            Triple("Tornado Watch", "Extreme", AlertKind.SUFFIX), // seen in the real TX capture
            Triple("Extreme Cold Advisory", "Extreme", AlertKind.SUFFIX),
            Triple("Special Weather Statement", "Extreme", AlertKind.SUFFIX),
            Triple("Tornado Warning", "Moderate", AlertKind.SUFFIX),
            Triple("  Winter Storm Warning  ", " severe ", AlertKind.OVERRIDE),
        )
        for ((e, s, k) in table) assertEquals(k, AlertRules.classify(e, s), "$e/$s")
        // Through the parser too.
        for ((e, s, k) in table) assertEquals(k, NwsAlerts.parse(doc(p(e, s)), now)!!.alerts.single().kind, "$e/$s")
    }

    @Test fun expiredCancelTestAndOtherStatusesAreDropped() {
        assertTrue(NwsAlerts.parse(doc(p("Tornado Warning", ends = "\"2026-04-15T04:20:00Z\"")), now)!!.alerts.isEmpty(), "ends == now")
        assertTrue(NwsAlerts.parse(doc(p("Tornado Warning", ends = null, expires = "\"2026-04-15T04:00:00Z\"")), now)!!.alerts.isEmpty())
        for (t in listOf("Cancel", "Ack", "Error", "")) assertTrue(NwsAlerts.parse(doc(p("Tornado Warning", type = t)), now)!!.alerts.isEmpty(), t)
        for (st in listOf("Test", "Exercise", "System", "Draft")) assertTrue(NwsAlerts.parse(doc(p("Tornado Warning", status = st)), now)!!.alerts.isEmpty(), st)
        assertEquals(1, NwsAlerts.parse(doc(p("Tornado Warning", ends = "\"2026-04-15T04:20:01Z\"")), now)!!.alerts.size)
    }

    @Test fun nullOrBadEndsFallsBackToExpiresAndBothMissingIsKept() {
        val a = NwsAlerts.parse(doc(p("Flood Watch", ends = null, expires = "\"2026-04-15T09:00:00-05:00\"")), now)!!.alerts.single()
        assertEquals(Instant.parse("2026-04-15T14:00:00Z"), a.until)
        val b = NwsAlerts.parse(doc(p("Flood Watch", ends = "\"soon\"", expires = "\"2026-04-15T09:00:00Z\"")), now)!!.alerts.single()
        assertEquals(Instant.parse("2026-04-15T09:00:00Z"), b.until)
        assertNull(NwsAlerts.parse(doc(p("Flood Watch", ends = null, expires = null)), now)!!.alerts.single().until)
    }

    @Test fun atMostEightOverrideFirstAndDuplicatesCollapse() {
        val many = (1..12).map { p("Advisory $it", "Minor") } + p("Tornado Warning", "Extreme") +
            p("Flood Watch", ends = "\"2026-04-15T05:00:00Z\"") + p("Flood Watch", ends = "\"2026-04-15T09:00:00Z\"")
        val s = NwsAlerts.parse(doc(*many.toTypedArray()), now)!!
        assertEquals(8, s.alerts.size)
        assertEquals("Tornado Warning", s.alerts.first().event)
        assertEquals("Flood Watch", s.alerts[1].event, "Severe before Minor")
        assertEquals(Instant.parse("2026-04-15T09:00:00Z"), s.alerts[1].until)
        assertEquals(1, s.alerts.count { it.event == "Flood Watch" })
    }

    @Test fun stringsAreCleanedTo40AndClassifiedBeforeTheCut() {
        val long = "Extraordinarily Long Named Hazard Of Some Kind Warning"
        val a = NwsAlerts.parse(doc(p(long, "Severe")), now)!!.alerts.single()
        assertEquals(AlertKind.OVERRIDE, a.kind, "Warning past char 40 still counts")
        assertTrue(a.event.length <= 40)
        val c = NwsAlerts.parse(doc(p("Tornado‮\u0000 Warning​", "Severe")), now)!!.alerts.single()
        assertEquals("Tornado Warning", c.event)
        val huge = NwsAlerts.parse(doc(p("x".repeat(100_000) + " Watch", "Severe")), now)!!.alerts.single()
        assertEquals(40, huge.event.length)
    }

    @Test fun malformedDocumentsAreNullAndNeverThrow() {
        for (bad in listOf("", "{", "null", "[]", "<html>Service Unavailable</html>", """{"features":{}}""",
            """{"type":"FeatureCollection"}""", "\"str\"", "{\"features\":[1,2,3]}".replace("[1,2,3]", "7"))) {
            assertNull(NwsAlerts.parse(bad, now), bad)
        }
        // Wrong types inside features are skipped, not fatal.
        assertTrue(NwsAlerts.parse("""{"features":[1,"x",null,{"properties":7},{"properties":{"event":5,"status":"Actual","messageType":"Alert"}}]}""", now)!!.alerts.isEmpty())
    }

    @Test fun hostileDeepNestingIsRejectedWithoutStackOverflow() {
        val depth = 100_000
        val deep = "[".repeat(depth) + "]".repeat(depth)
        assertNull(NwsAlerts.parse(deep, now))
        val deepObj = """{"features":[{"properties":""" + "{\"a\":".repeat(depth) + "1" + "}".repeat(depth) + "}]}"
        assertNull(NwsAlerts.parse(deepObj, now))
        // Brackets inside strings do not count.
        assertEquals(1, NwsAlerts.parse(doc(p("[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[ Watch")), now)!!.alerts.size)
        assertFalse(NwsAlerts.depthOver("""{"a":"\"[[[["}""", 2))
        assertTrue(NwsAlerts.depthOver("[[[", 2))
    }

    @Test fun halfAMegabyteOfFeaturesParsesFastAndKeepsEight() {
        val one = """{"type":"Feature","properties":{${p("Wind Advisory", "Minor")}}}"""
        val n = (512 * 1024) / (one.length + 1)
        val big = """{"type":"FeatureCollection","features":[${List(n) { one.replace("Wind Advisory", "Advisory ${it % 50}") }.joinToString(",")}]}"""
        assertTrue(big.length in 400_000..(HttpFeedFetcher.MAX_BODY.toInt()), "${big.length}")
        val t0 = System.nanoTime()
        val s = NwsAlerts.parse(big, now)!!
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L)
        assertEquals(8, s.alerts.size)
    }

    @Test fun cacheDtoRoundTripsAndIsRevalidated() {
        val s = NwsAlerts.parse(warningDoc, now)!!
        assertEquals(s, NwsAlerts.fromDto(NwsAlerts.toDto(s)))
        val d = NwsAlerts.toDto(s)
        assertNull(NwsAlerts.fromDto(NwsAlerts.AlertSetDto(d.alerts.map { NwsAlerts.AlertDto(it.event, it.severity, "PANIC", it.until) })))
        assertNull(NwsAlerts.fromDto(NwsAlerts.AlertSetDto(List(9) { d.alerts[0] })))
        assertNull(NwsAlerts.fromDto(NwsAlerts.AlertSetDto(listOf(NwsAlerts.AlertDto("x", "Minor", "SUFFIX", "tomorrow")))))
        assertNull(NwsAlerts.fromDto(NwsAlerts.AlertSetDto(listOf(NwsAlerts.AlertDto("\u0000", "Minor", "SUFFIX")))))
        val long = NwsAlerts.fromDto(NwsAlerts.AlertSetDto(listOf(NwsAlerts.AlertDto("y".repeat(500) + "‮", "Minor", "SUFFIX"))))!!
        assertEquals(40, long.alerts.single().event.length)
    }
}
