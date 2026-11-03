package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Fixtures follow Open-Meteo's documented response shape (not fetched live from the sandbox: see ADR 0005). */
class WeatherFeedTest {
    private fun doc(currentCode: Int, hourlyCodes: List<Int>, now: String = "2026-11-01T14:15", temp: String = "54.6"): String {
        val times = hourlyCodes.indices.joinToString(",") { i -> "\"2026-11-0%dT%02d:00\"".format(1 + i / 24, i % 24) }
        return """{"latitude":36.5,"longitude":-87.4,"utc_offset_seconds":-18000,
          "current":{"time":"$now","interval":900,"temperature_2m":$temp,"weather_code":$currentCode},
          "hourly":{"time":[$times],"weather_code":[${hourlyCodes.joinToString(",")}]}}"""
    }
    private fun codes(vararg overrides: Pair<Int, Int>): List<Int> =
        MutableList(48) { 3 }.also { l -> overrides.forEach { (h, c) -> l[h] = c } }

    @Test fun urlHasTwoDecimalsAndNoIdentifiers() {
        val u = WeatherFeed.url(Location(36.5301, -87.3599))
        assertEquals("api.open-meteo.com", u.host)
        assertEquals("36.53", u.queryParameter("latitude"))
        assertEquals("-87.36", u.queryParameter("longitude"))
        assertEquals("fahrenheit", u.queryParameter("temperature_unit"))
        assertTrue(HttpFeedFetcher.isAllowed(u))
    }

    @Test fun locationIsRangeChecked() {
        assertNotNull(Location.of(36.5, -87.3))
        assertNull(Location.of(91.0, 0.0))
        assertNull(Location.of(0.0, 181.0))
        assertNull(Location.of(Double.NaN, 0.0))
    }

    @Test fun currentTempConditionAndNextRain() {
        val w = WeatherFeed.parse(doc(3, codes(21 to 61)))!!
        assertEquals(55, w.tempF)
        assertEquals("Overcast", w.condition)
        assertNull(w.precipNow)
        assertEquals("rain after 9 PM", w.nextPrecip)
        assertEquals("55°F Overcast · rain after 9 PM", w.chip)
    }

    @Test fun nextSnowAndRainBeyondTwelveHoursIsIgnored() {
        assertEquals("snow after 11 PM", WeatherFeed.parse(doc(0, codes(23 to 73)))!!.nextPrecip)
        // now 14:15; 12 h window ends 02:15 next day (index 26). Index 27 is outside.
        assertNull(WeatherFeed.parse(doc(0, codes(27 to 63)))!!.nextPrecip)
        assertEquals("rain after 2 AM", WeatherFeed.parse(doc(0, codes(26 to 63)))!!.nextPrecip)
    }

    @Test fun currentHourSlotAndPastSlotsAreNotNext() {
        assertNull(WeatherFeed.parse(doc(3, codes(14 to 63, 9 to 63)))!!.nextPrecip)
        assertEquals("rain after 3 PM", WeatherFeed.parse(doc(3, codes(15 to 63)))!!.nextPrecip)
    }

    @Test fun precipitatingNowSetsOverlayKindAndNoNext() {
        val rain = WeatherFeed.parse(doc(63, codes(18 to 63)))!!
        assertEquals(Precip.RAIN, rain.precipNow); assertNull(rain.nextPrecip); assertEquals("Rain", rain.condition)
        assertEquals(Precip.SNOW, WeatherFeed.parse(doc(73, codes()))!!.precipNow)
        assertEquals(Precip.RAIN, WeatherFeed.parse(doc(95, codes()))!!.precipNow)
        assertNull(WeatherFeed.parse(doc(45, codes()))!!.precipNow)
    }

    @Test fun unknownCodeStillShowsTemperature() {
        val w = WeatherFeed.parse(doc(42, codes()))!!
        assertEquals("", w.condition)
        assertEquals("55°F", w.chip)
    }

    @Test fun malformedIsNoData() {
        for (bad in listOf(
            "", "not json", "[]", "{}", """{"current":{}}""",
            doc(3, codes()).replace("54.6", "\"warm\""),
            doc(3, codes(), temp = "900"),
            doc(3, codes()).replace("\"weather_code\":3}", "\"weather_code\":\"x\"}"),
            doc(3, codes(), now = "yesterday"),
        )) assertNull(WeatherFeed.parse(bad), bad.take(60))
    }

    @Test fun malformedHourlyDegradesToNoNextButKeepsCurrent() {
        val w = WeatherFeed.parse("""{"current":{"time":"2026-11-01T14:15","temperature_2m":40,"weather_code":0},"hourly":{"time":"x"}}""")!!
        assertEquals(40, w.tempF); assertNull(w.nextPrecip)
    }

    @Test fun chipIsCapped() {
        assertTrue(WeatherNow(70, "x".repeat(100), null, "y".repeat(100)).chip.length <= Feeds.LABEL_CAP)
    }

    // --- M7-B9: hourly temperature and daily min/max ---------------------------------------------------------------

    private fun tempDoc(now: String = "2026-11-01T14:15", dailyDays: String = """["2026-11-01","2026-11-02"]""",
                        hourly: (Int) -> Double? = { h -> 40.0 + (if (h < 24) minOf(h, 24 - h) else 30 - (h - 24)) }): String {
        val times = (0 until 48).joinToString(",") { i -> "\"2026-11-0%dT%02d:00\"".format(1 + i / 24, i % 24) }
        val temps = (0 until 48).joinToString(",") { hourly(it)?.toString() ?: "null" }
        return """{"current":{"time":"$now","temperature_2m":54.6,"weather_code":3},
          "hourly":{"time":[$times],"weather_code":[${List(48) { 3 }.joinToString(",")}],"temperature_2m":[$temps]},
          "daily":{"time":$dailyDays,"temperature_2m_max":[63.4,58.0],"temperature_2m_min":[41.2,39.9]}}"""
    }

    @Test fun urlAsksForHourlyTemperatureAndDailyMinMax() {
        val u = WeatherFeed.url(Location(36.53, -87.36))
        assertEquals("weather_code,temperature_2m", u.queryParameter("hourly"))
        assertEquals("temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max", u.queryParameter("daily"))
        assertEquals("7", u.queryParameter("forecast_days"))
        assertEquals("api.open-meteo.com", u.host)
        assertTrue(HttpFeedFetcher.isAllowed(u))
    }

    @Test fun parsesTodaysHighLowHighHourAndTheOvernightLow() {
        val w = WeatherFeed.parse(tempDoc())!!
        assertEquals(63, w.highF); assertEquals(41, w.lowF)
        assertEquals(12, w.highHour, "hourly peak is at noon in this series")
        // Night 7 PM to 7 AM: hours 19..31; the series is lowest at 40+... check by recomputing.
        val expected = (19..31).minOf { h -> 40 + (if (h < 24) minOf(h, 24 - h) else 30 - (h - 24)) }
        assertEquals(expected, w.overnightLowF)
        assertEquals("55°F Overcast", w.chip, "the chip is unchanged")
    }

    @Test fun afterMidnightTheOvernightLowIsTheNightInProgress() {
        val w = WeatherFeed.parse(tempDoc(now = "2026-11-02T02:15", dailyDays = """["2026-11-02","2026-11-03"]"""))!!
        // Hours 02..07 of 2026-11-02 (series indices 26..31): 30 - (h - 24) = 28..23 plus 40.
        assertEquals((26..31).minOf { 40 + 30 - (it - 24) }, w.overnightLowF)
    }

    @Test fun missingOrMalformedNewFieldsAreNullAndNeverFailTheDocument() {
        val old = WeatherFeed.parse(doc(3, codes()))!!
        assertNull(old.highF); assertNull(old.lowF); assertNull(old.highHour); assertNull(old.overnightLowF)
        val gap = WeatherFeed.parse(tempDoc(hourly = { h -> if (h == 22) null else 50.0 }))!!
        assertNull(gap.overnightLowF, "a hole in the night hours means no overnight low")
        assertEquals(63, gap.highF)
        val wrongDay = WeatherFeed.parse(tempDoc(dailyDays = """["2026-10-30","2026-10-31"]"""))!!
        assertNull(wrongDay.highF); assertNull(wrongDay.lowF)
        val junk = WeatherFeed.parse("""{"current":{"time":"2026-11-01T14:15","temperature_2m":50,"weather_code":0},"daily":"x","hourly":{"time":[1],"temperature_2m":"y"}}""")!!
        assertEquals(50, junk.tempF); assertNull(junk.highF)
        val absurd = WeatherFeed.parse(tempDoc().replace("63.4", "999"))!!
        assertNull(absurd.highF)
    }

    @Test fun newFieldsRoundTripThroughTheCacheAndAreRangeChecked() {
        val w = WeatherNow(55, "Overcast", null, null, 63, 41, 15, 38)
        val ser = PanelCaches.weather
        val at = java.time.Instant.parse("2026-11-01T20:00:00Z")
        assertEquals(w, PanelCacheCodec.decode(PanelCacheCodec.encode(Stamped(w, at), ser), ser, at)!!.value)
        assertNull(PanelCacheCodec.decode(PanelCacheCodec.encode(Stamped(w.copy(highHour = 25), at), ser), ser, at))
        assertNull(PanelCacheCodec.decode(PanelCacheCodec.encode(Stamped(w.copy(overnightLowF = 900), at), ser), ser, at))
        // An old cache file (no new fields) still loads.
        val oldText = """{"v":1,"at":"2026-11-01T20:00:00Z","data":{"tempF":50,"condition":"Clear"}}"""
        assertEquals(WeatherNow(50, "Clear", null, null), PanelCacheCodec.decode(oldText, ser, at)!!.value)
    }
}
