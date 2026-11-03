package tvhome.core.feeds

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Serializers for the three panel caches (weather, alerts, games). Decoding re-applies the parse limits: the file is
 * never trusted (security finding `panel-cache-future-stamp`). Anything out of bounds makes the whole file "no cache".
 */
internal object PanelCaches {
    const val WEATHER = "weather"
    const val ALERTS = "alerts"
    const val GAMES = "games"

    private const val MAX_GAMES = 64

    @Serializable class WeatherDto(
        val tempF: Int, val condition: String, val precipNow: String? = null, val nextPrecip: String? = null,
        val highF: Int? = null, val lowF: Int? = null, val highHour: Int? = null, val overnightLowF: Int? = null,
        val days: List<DayDto> = emptyList(),
    )
    @Serializable class DayDto(val date: String, val highF: Int, val lowF: Int, val condition: String, val precipChance: Int? = null)
    @Serializable class GameDto(
        val team: String, val start: String, val opponent: String, val home: Boolean, val phase: String,
        val ownScore: Int? = null, val oppScore: Int? = null, val period: String? = null,
        val networks: List<String> = emptyList(),
    )

    val weather: KSerializer<WeatherNow> = mapped(WeatherDto.serializer(),
        { WeatherDto(it.tempF, it.condition, it.precipNow?.name, it.nextPrecip, it.highF, it.lowF, it.highHour, it.overnightLowF,
            it.days.map { d -> DayDto(d.date.toString(), d.highF, d.lowF, d.condition, d.precipChance) }) },
        { d ->
            require(d.tempF in -100..150)
            for (t in listOf(d.highF, d.lowF, d.overnightLowF)) require(t == null || t in -100..150)
            require(d.highHour == null || d.highHour in 0..23)
            val precip = d.precipNow?.let { p -> Precip.entries.first { it.name == p } }
            val cond = if (d.condition.isEmpty()) "" else requireNotNull(Feeds.clean(d.condition))
            require(d.days.size <= WeatherFeed.FORECAST_DAYS)
            val days = d.days.map { x ->
                require(x.highF in -100..150 && x.lowF in -100..150)
                require(x.precipChance == null || x.precipChance in 0..100)
                DayForecast(java.time.LocalDate.parse(x.date), x.highF, x.lowF,
                    if (x.condition.isEmpty()) "" else requireNotNull(Feeds.clean(x.condition)), x.precipChance)
            }
            WeatherNow(d.tempF, cond, precip, d.nextPrecip?.let { requireNotNull(Feeds.clean(it)) }, d.highF, d.lowF, d.highHour, d.overnightLowF, days)
        })

    val alerts: KSerializer<AlertSet> = mapped(NwsAlerts.AlertSetDto.serializer(), NwsAlerts::toDto) {
        requireNotNull(NwsAlerts.fromDto(it))
    }

    /** Games for [teams] only; a game of a team no longer followed is dropped, not an error. */
    fun games(teams: List<TeamConfig>): KSerializer<List<TeamGame>> = mapped(ListSerializer(GameDto.serializer()),
        { list -> list.map { (t, g) -> GameDto(t.key, g.start.toString(), g.opponent, g.home, g.phase.name, g.ownScore, g.oppScore, g.period, g.networks) } },
        { list ->
            require(list.size <= MAX_GAMES * teams.size.coerceAtLeast(1))
            list.mapNotNull { d ->
                val team = teams.firstOrNull { it.key == d.team } ?: return@mapNotNull null
                val score = 0..999
                require(d.ownScore == null || d.ownScore in score)
                require(d.oppScore == null || d.oppScore in score)
                TeamGame(team, GameInfo(
                    Instant.parse(d.start), requireNotNull(Feeds.clean(d.opponent)), d.home,
                    GamePhase.entries.first { it.name == d.phase }, d.ownScore, d.oppScore,
                    d.period?.let { requireNotNull(Feeds.clean(it)) },
                    d.networks.also { require(it.size <= GameInfo.MAX_NETWORKS) }.map { requireNotNull(Feeds.clean(it)) },
                ))
            }
        })

    private fun <T, D> mapped(dto: KSerializer<D>, to: (T) -> D, from: (D) -> T): KSerializer<T> = object : KSerializer<T> {
        override val descriptor: SerialDescriptor = dto.descriptor
        override fun serialize(encoder: Encoder, value: T) = dto.serialize(encoder, to(value))
        override fun deserialize(decoder: Decoder): T = from(dto.deserialize(decoder))
    }
}
