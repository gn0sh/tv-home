package tvhome.core.feeds

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** The Board's 7-day forecast, from the same Open-Meteo request (daily fields). */
class WeatherForecastTest {
    private fun doc(daily: String) = """{"current":{"time":"2026-11-03T09:00","temperature_2m":55.2,"weather_code":2},
        "daily":$daily}"""
    private val days = (0 until 7).joinToString(",") { "\"" + LocalDate.of(2026, 11, 3).plusDays(it.toLong()) + "\"" }

    @Test fun sevenDaysParsedInOrder() {
        val w = WeatherFeed.parse(doc("""{"time":[$days],"temperature_2m_max":[63.4,58,60,61,62,70,71],
            "temperature_2m_min":[41.2,39.9,40,41,42,50,51],"weather_code":[3,61,0,1,2,95,71],
            "precipitation_probability_max":[10,80,0,5,20,100,null]}"""))!!
        assertEquals(7, w.days.size)
        assertEquals(DayForecast(LocalDate.of(2026, 11, 3), 63, 41, "Overcast", 10), w.days[0])
        assertEquals(DayForecast(LocalDate.of(2026, 11, 4), 58, 40, "Light rain", 80), w.days[1])
        assertEquals("Thunderstorm", w.days[5].condition)
        assertNull(w.days[6].precipChance)
        assertEquals(63, w.highF, "today's high still comes from the same daily block")
    }

    @Test fun daysWithoutAHighOrLowAreDroppedAndOddValuesNeverFailTheDocument() {
        val w = WeatherFeed.parse(doc("""{"time":[$days],"temperature_2m_max":[63,null,"x",61,62,999,71],
            "temperature_2m_min":[41,40,40,41,42,50],"weather_code":["3"],"precipitation_probability_max":[150,-1]}"""))!!
        assertEquals(listOf(3, 6, 7), w.days.map { it.date.dayOfMonth })
        assertNull(w.days[0].precipChance, "out of range")
        assertEquals("", w.days[0].condition, "a string code is not a code")
        assertNotNull(WeatherFeed.parse(doc("""{"time":"nope"}""")))
        assertTrue(WeatherFeed.parse(doc("""{"time":"nope"}"""))!!.days.isEmpty())
    }

    @Test fun pastDaysAreSkipped() {
        val w = WeatherFeed.parse(doc("""{"time":["2026-11-02","2026-11-03"],"temperature_2m_max":[50,51],"temperature_2m_min":[40,41]}"""))!!
        assertEquals(listOf(LocalDate.of(2026, 11, 3)), w.days.map { it.date })
    }

    @Test fun forecastSurvivesTheDiskCacheAndOldFilesStillLoad() {
        val w = WeatherNow(55, "Overcast", null, null, 63, 41, days = listOf(DayForecast(LocalDate.of(2026, 11, 3), 63, 41, "Overcast", 10)))
        val now = Instant.parse("2026-11-03T15:00:00Z")
        val text = PanelCacheCodec.encode(Stamped(w, now), PanelCaches.weather)
        assertEquals(w, PanelCacheCodec.decode(text, PanelCaches.weather, now)!!.value)
        val old = """{"v":1,"at":"$now","data":{"tempF":55,"condition":"Overcast"}}"""
        assertTrue(PanelCacheCodec.decode(old, PanelCaches.weather, now)!!.value.days.isEmpty())
        val bad = """{"v":1,"at":"$now","data":{"tempF":55,"condition":"Overcast","days":[{"date":"2026-11-03","highF":63,"lowF":41,"condition":"x","precipChance":101}]}}"""
        assertNull(PanelCacheCodec.decode(bad, PanelCaches.weather, now))
    }
}
