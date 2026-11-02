package tvhome.core.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tvhome.core.agenda.AgendaDay
import tvhome.core.agenda.AgendaItem
import tvhome.core.agenda.TimeLabel
import tvhome.core.feeds.AlertKind
import tvhome.core.feeds.AlertSet
import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.GameInfo
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.NwsAlert
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.Stamped
import tvhome.core.feeds.TeamGame
import tvhome.core.feeds.Teams
import tvhome.core.feeds.WatchMap
import tvhome.core.feeds.WatchTarget
import tvhome.core.feeds.WeatherNow
import tvhome.core.state.AgendaState
import tvhome.core.state.StaleCause
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** M7-B11 and the B9 glance wiring: Quiet applied inside homeGlance() and calendarTile(), and the Watch target. */
class QuietModeTest {
    private val zone = ZoneId.of("America/Chicago")
    private val mon = LocalDate.of(2026, 11, 2)
    private fun at(h: Int, m: Int = 0, day: LocalDate = mon) = day.atTime(h, m).atZone(zone).toInstant()

    // Distinct, unlikely substrings so any leak is caught.
    private val titles = listOf("Zygomatic-Surgery", "Quixotic-Therapy", "Marmalade-Interview", "Orchid-Birthday", "Walrus-Funeral", "Saffron-Audit")

    private fun timed(title: String, h: Int, m: Int, day: LocalDate = mon) =
        AgendaItem(title, TimeLabel.Starts(LocalTime.of(h, m)), at(h, m, day), at(h, m, day).plusSeconds(3600))
    private val allDay = AgendaItem(titles[3], TimeLabel.AllDay)

    private fun days(now: Instant): Pair<AgendaDay, AgendaDay> = AgendaDay(mon, listOf(
        allDay, timed(titles[0], 9, 0), timed(titles[1], 17, 30), timed(titles[2], 20, 0),
    )) to AgendaDay(mon.plusDays(1), listOf(timed(titles[4], 8, 0), timed(titles[5], 13, 0)))

    private fun states(now: Instant): List<AgendaState> {
        val (t, m) = days(now)
        return listOf(
            AgendaState.Ok(t, m, now, "Updated just now"),
            AgendaState.Stale(t, m, now.minus(Duration.ofMinutes(40)), 40, "Updated 40 min ago", false, StaleCause.OVERDUE, false),
            AgendaState.Stale(t, m, now.minus(Duration.ofMinutes(90)), 90, "Updated 90 min ago", true, StaleCause.CANT_REACH, false),
            AgendaState.Stale(t, m, now.minus(Duration.ofHours(5)), 300, "Updated 5 h ago", true, StaleCause.OVERDUE, false),
            AgendaState.NotSetUp, AgendaState.Loading, AgendaState.CantReach, AgendaState.Unreadable,
        )
    }

    private fun strings(g: HomeGlance): List<String> = buildList {
        add(g.clock); add(g.date); g.chip?.let(::add)
        g.nowNext?.let { add(it.line1); it.line2?.let(::add); it.ageText?.let(::add) }
        when (val c = g.card) {
            is ContextCard.Alert -> { add(c.event); c.until?.let(::add); add(c.text) }
            is ContextCard.Game -> { add(c.line); c.network?.let(::add) }
            is ContextCard.TimeOfDay -> { add(c.heading); add(c.text); c.detail?.let(::add) }
            null -> Unit
        }
        g.brief?.let(::add)
        g.watch?.let { add(it.network) }
    }

    private fun modelStrings(m: ScreenModel): List<String> = when (m) {
        is ScreenModel.Message -> listOfNotNull(m.title, m.detail)
        is ScreenModel.Agenda -> listOfNotNull(m.age.text, m.age.liveDescription, m.footer) +
            listOf(m.today, m.tomorrow).flatMap { d -> listOf(d.heading, d.date, d.headingDescription) + d.rows.flatMap { listOf(it.time, it.title, it.description) } }
    }

    private fun tileStrings(t: CalendarTile): List<String> = listOfNotNull(t.date, t.time, t.text, t.note, t.description)

    @Test fun noFixtureTitleSubstringReachesHomeGlanceOrCalendarTileWithQuietOn() {
        val weather = Stamped(WeatherNow(55, "Overcast", null, "rain after 6 PM", 63, 48, 15, 41), Instant.EPOCH)
        // Morning (7 AM), midday, 4:50 PM (a countdown to "Quixotic-Therapy" at 5:30), evening and late night.
        for (h in listOf(7, 12, 16, 19, 23)) for (m in listOf(0, 50)) {
            val now = at(h, m)
            for (state in states(now)) {
                val feeds = FeedsSnapshot(weather = Stamped(weather.value, now))
                val g = homeGlance(GlanceInputs(
                    now, zone, false, feeds, PanelSwitches(), true, state,
                    travel = listOf(TravelRule("Therapy", 30), TravelRule("Surgery", 20)), quiet = true,
                ))
                val tile = calendarTile(state, now, zone, false, quiet = true)
                for (s in strings(g) + tileStrings(tile)) for (t in titles) {
                    assertFalse(s.contains(t, ignoreCase = true), "title $t leaked in \"$s\" at $h:$m state ${state.javaClass.simpleName}")
                }
                assertTrue(g.quiet)
            }
        }
    }

    @Test fun theSameInputsWithQuietOffDoShowTitlesSoTheTestAboveBites() {
        val now = at(16, 50)
        val state = states(now).first()
        val g = homeGlance(GlanceInputs(now, zone, false, FeedsSnapshot(), PanelSwitches(), true, state, quiet = false))
        assertTrue(strings(g).any { it.contains(titles[1]) }, strings(g).toString())
        assertTrue(tileStrings(calendarTile(state, now, zone, false)).any { it.contains(titles[1]) })
    }

    @Test fun timesStayAndOnlyTheTitleBecomesBusy() {
        val now = at(16, 50)
        val state = states(now).first()
        val tile = calendarTile(state, now, zone, false, quiet = true)
        assertEquals("5:30 PM", tile.time)
        assertEquals("Busy", tile.text)
        assertEquals("Calendar, Monday 2 November. Next: 5:30 PM, Busy.", tile.description)
        val g = homeGlance(GlanceInputs(now, zone, false, FeedsSnapshot(), PanelSwitches(), true, state, quiet = true))
        assertEquals("Busy in 40 min", g.nowNext!!.line1)
    }

    @Test fun weatherGameAndAlertAreUnaffectedByQuiet() {
        val now = at(16, 50)
        val game = TeamGame(Teams.PREDATORS, GameInfo(at(19), "Blues", true, GamePhase.UPCOMING, null, null, null))
        val tornado = NwsAlert("Tornado Warning", "Extreme", AlertKind.OVERRIDE, now.plus(Duration.ofMinutes(55)))
        val feeds = FeedsSnapshot(
            weather = Stamped(WeatherNow(72, "Cloudy", null, "rain after 6 PM"), now),
            games = Stamped(listOf(game), now), alerts = Stamped(AlertSet(listOf(tornado)), now), alertsChecked = true,
        )
        fun glance(q: Boolean) = homeGlance(GlanceInputs(now, zone, false, feeds, PanelSwitches(), true, AgendaState.NotSetUp, quiet = q))
        val loud = glance(false); val quiet = glance(true)
        assertEquals(loud.chip, quiet.chip)
        assertEquals(loud.card, quiet.card)
        assertEquals(loud.clock, quiet.clock)
        val noAlert = FeedsSnapshot(weather = feeds.weather, games = feeds.games)
        fun glance2(q: Boolean) = homeGlance(GlanceInputs(now, zone, false, noAlert, PanelSwitches(), true, AgendaState.NotSetUp, quiet = q))
        assertEquals(glance2(false).card, glance2(true).card)
        assertTrue(glance2(true).card is ContextCard.Game)
    }

    @Test fun quietTitleHelper() {
        assertEquals("Busy", QuietMode.title("Anything", true))
        assertEquals("Busy", QuietMode.title("", true))
        assertEquals("Anything", QuietMode.title("  Anything ", false))
        assertEquals(Copy.NO_TITLE, QuietMode.title("   ", false))
        assertEquals(NowNextCopy.MAX_TITLE, QuietMode.title("x".repeat(100), false).length)
    }

    // --- Watch target in the glance (B9) ---------------------------------------------------------------------------

    private val map = WatchMap.parse("ESPN+=com.espn.gtv", "predators=ESPN+")
    private fun watchFor(phase: GamePhase, installed: Boolean, nets: List<String> = listOf("ESPN+"), mapp: WatchMap = map): HomeGlance {
        val now = at(16, 50)
        val start = if (phase == GamePhase.UPCOMING) at(19) else at(16)
        val game = TeamGame(Teams.PREDATORS, GameInfo(start, "Blues", true, phase, 1, 0, null, nets))
        val feeds = FeedsSnapshot(games = Stamped(listOf(game), now))
        return homeGlance(GlanceInputs(now, zone, false, feeds, PanelSwitches(), true, AgendaState.NotSetUp, watchMap = mapp, isInstalled = { installed }))
    }

    @Test fun watchOnlyForALiveOrUpcomingGameWithAnInstalledMappedApp() {
        assertEquals(WatchTarget("ESPN+", "com.espn.gtv"), watchFor(GamePhase.UPCOMING, true).watch)
        assertEquals(WatchTarget("ESPN+", "com.espn.gtv"), watchFor(GamePhase.LIVE, true).watch)
        assertNull(watchFor(GamePhase.UPCOMING, false).watch, "uninstalled: no Watch")
        assertNull(watchFor(GamePhase.UPCOMING, true, nets = listOf("SCRIPPS")).watch, "no mapping: no Watch")
        assertNull(watchFor(GamePhase.UPCOMING, true, mapp = WatchMap.EMPTY).watch)
        assertNull(watchFor(GamePhase.FINAL, true).watch, "an ended game has nothing to watch")
        assertNull(homeGlance(GlanceInputs(at(16, 50), zone, false, FeedsSnapshot(), PanelSwitches(), true, AgendaState.NotSetUp, watchMap = map, isInstalled = { true })).watch, "no game: no Watch")
    }

    @Test fun gamesSwitchOffMeansNoWatchAndNoCard() {
        val now = at(16, 50)
        val game = TeamGame(Teams.PREDATORS, GameInfo(at(19), "Blues", true, GamePhase.UPCOMING, null, null, null, listOf("ESPN+")))
        val g = homeGlance(GlanceInputs(now, zone, false, FeedsSnapshot(games = Stamped(listOf(game), now)), PanelSwitches(games = false), true,
            AgendaState.NotSetUp, watchMap = map, isInstalled = { true }))
        assertNull(g.watch); assertNull(g.card)
    }

    @Test fun theTimeOfDayCardIsOneGlanceItemAndFillsTheCardWhenNothingElseDoes() {
        val now = at(19, 30)
        val w = Stamped(WeatherNow(50, "Clear", null, null, 63, 41, 15, 38), now)
        val g = homeGlance(GlanceInputs(now, zone, false, FeedsSnapshot(weather = w), PanelSwitches(), true, AgendaState.NotSetUp))
        assertTrue(g.card is ContextCard.TimeOfDay)
        assertTrue(GlanceItem.CARD in g.items)
        assertTrue(g.items.size <= GlanceBudget.MAX)
    }

    @Test fun noFixtureTitleReachesTheFullScreenCalendarWithQuietOn() {
        for (h in listOf(7, 16, 23)) {
            val now = at(h, 50)
            for (state in states(now)) {
                for (s in modelStrings(screenModel(state, now, zone, false, hideTitles = true))) for (t in titles) {
                    assertFalse(s.contains(t, ignoreCase = true), "title $t leaked in \"$s\" at $h state ${state.javaClass.simpleName}")
                }
            }
        }
        val now = at(16, 50)
        val m = screenModel(states(now).first(), now, zone, false, hideTitles = true) as ScreenModel.Agenda
        assertEquals(4, m.today.rows.size, "rows and times stay")
        assertTrue(m.today.rows.all { it.title == "Busy" && it.description == "${it.time}, Busy" })
        assertEquals(m.initialFocus, (screenModel(states(now).first(), now, zone, false) as ScreenModel.Agenda).initialFocus)
        // Control: with titles shown, they are there.
        val loud = screenModel(states(now).first(), now, zone, false) as ScreenModel.Agenda
        assertTrue(loud.today.rows.any { it.title == titles[1] })
    }
}
