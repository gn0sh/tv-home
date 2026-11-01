package tvhome.core.ui

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters

/** What the hero backdrop draws on top of its gradient. The app has one Canvas drawing per motif. */
enum class HeroMotif { MOON_BATS, SNOW, DAWN, DAY, SUNSET, STARS }

/**
 * The hero area's look for one moment (M2e). Colours are 0xRRGGBB. [text] and [secondaryText] are for the title,
 * subtitle and clock over the backdrop; [accent] is the badge colour (the badge text is drawn in [backdropTop]).
 * [badge] is the holiday name, or null when no holiday applies.
 */
data class HeroTheme(
    val backdropTop: Int,
    val backdropBottom: Int,
    val accent: Int,
    val motif: HeroMotif,
    val badge: String?,
    val text: Int = 0xF4F1EA,
    val secondaryText: Int = 0xD5DCE8,
) {
    companion object {
        /** The fourth Thursday of [year]'s November (US Thanksgiving). */
        fun thanksgiving(year: Int): LocalDate =
            LocalDate.of(year, Month.NOVEMBER, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))

        /** Holidays win over time of day. Time of day: morning 5-11, day 11-17, evening 17-21, night 21-5. */
        fun at(date: LocalDate, time: LocalTime): HeroTheme = holiday(date) ?: timeOfDay(time)

        private val HALLOWEEN = HeroTheme(0x120A1E, 0x3A1A12, 0xFF9A3C, HeroMotif.MOON_BATS, "HALLOWEEN")
        private val CHRISTMAS = HeroTheme(0x0A2418, 0x1B4A36, 0xFF8A80, HeroMotif.SNOW, "CHRISTMAS")
        private val THANKSGIVING = HeroTheme(0x2A1608, 0x5A2E12, 0xF2B45A, HeroMotif.SUNSET, "THANKSGIVING")
        private val NEW_YEAR = HeroTheme(0x080B1E, 0x1A1F4A, 0xF2D675, HeroMotif.STARS, "NEW YEAR")
        private val VALENTINES = HeroTheme(0x2A0A1C, 0x5A1A3A, 0xFF9DBB, HeroMotif.SUNSET, "VALENTINE'S DAY")
        private val INDEPENDENCE = HeroTheme(0x081530, 0x1A2E5C, 0xFF8A80, HeroMotif.STARS, "JULY 4")

        private val MORNING = HeroTheme(0x1F3B66, 0x4A5F86, 0xFFD27A, HeroMotif.DAWN, null)
        private val DAY = HeroTheme(0x1B4A86, 0x245C96, 0xFFE08A, HeroMotif.DAY, null)
        private val EVENING = HeroTheme(0x2E2250, 0x7A3E3A, 0xFFB86B, HeroMotif.SUNSET, null)

        /** Dim on purpose: a TV in a dark room. */
        private val NIGHT = HeroTheme(0x04060B, 0x0A1020, 0x8AB4F8, HeroMotif.STARS, null)

        internal val ALL = listOf(
            HALLOWEEN, CHRISTMAS, THANKSGIVING, NEW_YEAR, VALENTINES, INDEPENDENCE, MORNING, DAY, EVENING, NIGHT,
        )

        private fun holiday(d: LocalDate): HeroTheme? {
            val m = d.month
            val day = d.dayOfMonth
            val thanks = thanksgiving(d.year)
            return when {
                m == Month.OCTOBER && day >= 15 -> HALLOWEEN
                m == Month.NOVEMBER && !d.isBefore(thanks.minusDays(3)) && !d.isAfter(thanks) -> THANKSGIVING
                m == Month.DECEMBER && day <= 26 -> CHRISTMAS
                m == Month.DECEMBER && day == 31 -> NEW_YEAR
                m == Month.JANUARY && day == 1 -> NEW_YEAR
                m == Month.FEBRUARY && day == 14 -> VALENTINES
                m == Month.JULY && day == 4 -> INDEPENDENCE
                else -> null
            }
        }

        private fun timeOfDay(t: LocalTime): HeroTheme = when (t.hour) {
            in 5..10 -> MORNING
            in 11..16 -> DAY
            in 17..20 -> EVENING
            else -> NIGHT
        }
    }
}
