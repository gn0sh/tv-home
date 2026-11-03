package tvhome.core.board

import tvhome.core.feeds.AlertKind
import tvhome.core.feeds.DayForecast
import tvhome.core.feeds.FeedsSnapshot
import tvhome.core.feeds.GameDay
import tvhome.core.feeds.GamePhase
import tvhome.core.feeds.PanelPolicies
import tvhome.core.feeds.PanelPolicy
import tvhome.core.feeds.PanelSwitches
import tvhome.core.feeds.PanelView
import tvhome.core.feeds.TeamConfig
import tvhome.core.feeds.Teams
import tvhome.core.feeds.view
import tvhome.core.state.AgendaState
import tvhome.core.ui.AgeLine
import tvhome.core.ui.activeAlerts
import tvhome.core.ui.screenModel
import tvhome.core.ui.ScreenModel
import tvhome.core.ui.untilText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Everything the Board page reads. Same sources as the home hero; nothing new is fetched. */
data class BoardInputs(
    val now: Instant,
    val zone: ZoneId,
    val is24h: Boolean,
    val agenda: AgendaState,
    val feeds: FeedsSnapshot,
    val switches: PanelSwitches,
    val hasLocation: Boolean,
    val quiet: Boolean,
    val teams: List<TeamConfig> = Teams.ALL,
)

/** A short text block: [lines] in order; [warn] draws it in the warning colour (stale, unknown). */
data class BoardCell(val heading: String, val lines: List<String>, val warn: Boolean = false)

/** One active NWS alert as the Board says it: "Tornado Warning until 7:45 PM". [severe] gets the red fill. */
data class BoardAlert(val text: String, val severe: Boolean)

/**
 * The whole Board page. Every string is decided here; the app only draws. Each section carries its own unknown or stale
 * state ([BoardCell.warn]) and never shows made-up values.
 */
data class Board(
    val grid: WeekGrid,
    /** Over the grid when the calendar cannot be shown ("Calendar not set up", ...); null when it can. */
    val calendarMessage: String?,
    /** The calendar's age line, only when it is old enough to matter (Stale), and the footer reason when emphasised. */
    val calendarAge: AgeLine?,
    /** Per column, the forecast line under the date ("63° / 41°" and "Rain 80%" when it is likely); null when unknown. */
    val forecast: List<BoardForecast?>,
    val alerts: List<BoardAlert>,
    val games: List<BoardCell>,
    val weather: BoardCell,
    /**
     * NWS alerts were checked but could not be loaded: "Alerts unknown" on the status row with the calendar's age (not in
     * the weather cell, so the bottom strip keeps to two lines when nothing is stale).
     */
    val alertsUnknown: Boolean = false,
) {
    /** Lines the bottom strip needs: its tallest cell (heading row included), 1 to 3. */
    val stripLines: Int get() = (games + weather).maxOf { it.lines.size }.coerceIn(1, 3)

    override fun toString(): String = "Board(${grid.columns.size} columns, message=${calendarMessage != null}, ${alerts.size} alerts)"
}

/** "63° / 41°" and, when rain is likely enough to plan for ([BoardCopy.RAIN_SHOWN_FROM]), "80%". */
data class BoardForecast(val temps: String, val rain: String?)

object BoardCopy {
    /** A day's precipitation chance is shown from this percent up (it answers "do we need an umbrella"). */
    const val RAIN_SHOWN_FROM = 30
    const val NOT_LOADED = "Not loaded yet"
    const val GAMES_OFF = "Turned off"
    const val SCHEDULE_UNKNOWN = "Schedule unknown"
    const val NO_GAME = "No upcoming game in the schedule"
    const val WEATHER = "Weather"
    const val WEATHER_OFF = "Turned off"
    const val NO_LOCATION = "Location not set"
    const val WEATHER_UNKNOWN = "Weather unknown"
    const val WEATHER_UNKNOWN_DETAIL = "No update in the last 2 hours"
    const val ALERTS_UNKNOWN = "Alerts unknown"
    const val MAX_ALERTS = 3
}

/** Games stay on the Board when old (a schedule rarely changes) but say their age, and warn past [staleAfter]. */
private val GAMES_POLICY = PanelPolicy(staleAfter = Duration.ofHours(12), hideWhenStale = false)

fun board(i: BoardInputs): Board {
    val (known, message, age) = calendarStatus(i)
    val week = when (val s = i.agenda) {
        is AgendaState.Ok -> s.week
        is AgendaState.NothingScheduled -> s.week
        is AgendaState.Stale -> s.week
        is AgendaState.Unavailable -> emptyList()
    }
    val grid = weekGrid(week, known, i.now, i.zone, i.is24h, i.quiet)

    val weatherView = view(i.feeds.weather, PanelPolicies.WEATHER, i.switches.weather && i.hasLocation, i.now)
    val weather = (weatherView as? PanelView.Shown)?.value
    val forecast = grid.columns.map { c -> weather?.days?.firstOrNull { it.date == c.date }?.let(::forecastOf) }

    val alertsOn = i.switches.alerts && i.hasLocation
    val alertsView = view(i.feeds.alerts, PanelPolicies.ALERTS, alertsOn, i.now)
    val alertSet = (alertsView as? PanelView.Shown)?.value?.let { activeAlerts(it, i.now) }
    val alerts = alertSet?.alerts.orEmpty().let { list ->
        val shown = list.take(BoardCopy.MAX_ALERTS).map { a ->
            BoardAlert(a.event + (a.until?.let { " " + untilText(it, i.now, i.zone, i.is24h) } ?: ""), a.kind == AlertKind.OVERRIDE)
        }
        if (list.size > BoardCopy.MAX_ALERTS) shown + BoardAlert("+${list.size - BoardCopy.MAX_ALERTS} more alerts", false) else shown
    }
    val alertsUnknown = alertsView == PanelView.NoData && i.feeds.alertsChecked

    return Board(grid, message, age, forecast, alerts, i.teams.map { gameCell(it, i) }, weatherCell(i, weatherView), alertsUnknown)
}

private fun forecastOf(d: DayForecast): BoardForecast =
    BoardForecast("${d.highF}° / ${d.lowF}°", d.precipChance?.takeIf { it >= BoardCopy.RAIN_SHOWN_FROM }?.let { "Rain $it%" })

/** Whether the calendar's days can be drawn, the message instead of them, and the age line (from [screenModel]). */
private fun calendarStatus(i: BoardInputs): Triple<Boolean, String?, AgeLine?> =
    when (val m = screenModel(i.agenda, i.now, i.zone, i.is24h, i.quiet)) {
        is ScreenModel.Message -> Triple(false, listOfNotNull(m.title, m.detail).joinToString(". "), null)
        is ScreenModel.Agenda -> {
            val stale = i.agenda is AgendaState.Stale
            val age = if (stale) m.age.copy(text = listOfNotNull(m.age.text, m.footer).joinToString(" · ")) else null
            Triple(true, null, age)
        }
    }

private fun gameCell(team: TeamConfig, i: BoardInputs): BoardCell {
    if (!i.switches.games) return BoardCell(team.name, listOf(BoardCopy.GAMES_OFF))
    val v = view(i.feeds.games, GAMES_POLICY, true, i.now)
    val shown = v as? PanelView.Shown ?: return BoardCell(team.name, listOf(BoardCopy.SCHEDULE_UNKNOWN), warn = true)
    val next = GameDay.next(shown.value, team.key, i.now)
        ?: return BoardCell(team.name, listOfNotNull(BoardCopy.NO_GAME, shown.ageText), warn = shown.stale)
    val g = next.game
    val vs = if (g.home) "vs" else "at"
    val first = if (g.phase == GamePhase.LIVE) GameDay.line(next, i.now, i.zone) else "$vs ${g.opponent}"
    val second = if (g.phase == GamePhase.LIVE) "Live · ${GameDay.tvText(g)}"
    else "${GameDay.whenText(g.start, i.now, i.zone, i.is24h)} · ${GameDay.tvText(g)}"
    return BoardCell(team.name, listOfNotNull(first, second, shown.ageText), warn = shown.stale)
}

private fun weatherCell(i: BoardInputs, v: PanelView<tvhome.core.feeds.WeatherNow>): BoardCell {
    return when {
        !i.switches.weather -> BoardCell(BoardCopy.WEATHER, listOf(BoardCopy.WEATHER_OFF))
        !i.hasLocation -> BoardCell(BoardCopy.WEATHER, listOf(BoardCopy.NO_LOCATION))
        v !is PanelView.Shown -> BoardCell(BoardCopy.WEATHER, listOf(BoardCopy.WEATHER_UNKNOWN, BoardCopy.WEATHER_UNKNOWN_DETAIL), warn = true)
        else -> {
            val w = v.value
            val now = listOf("Now ${w.tempF}°F", w.condition).filter { it.isNotEmpty() }.joinToString(" ")
            val today = w.days.firstOrNull { it.date == i.now.atZone(i.zone).toLocalDate() }
            val high = w.highF ?: today?.highF
            val low = w.lowF ?: today?.lowF
            val range = listOfNotNull(high?.let { "High $it°" }, low?.let { "Low $it°" }).joinToString(" · ").ifEmpty { null }
            // Rain: falling now, else when it starts, else today's chance when it is worth planning for.
            val rain = w.precipNow?.let { "${it.word.replaceFirstChar(Char::uppercase)} now" }
                ?: w.nextPrecip?.replaceFirstChar(Char::uppercase)
                ?: today?.precipChance?.takeIf { it >= BoardCopy.RAIN_SHOWN_FROM }?.let { "Rain $it% today" }
            val second = listOfNotNull(range, rain).joinToString(" · ").ifEmpty { null }
            BoardCell(BoardCopy.WEATHER, listOfNotNull(now, second, v.ageText), warn = v.stale)
        }
    }
}
