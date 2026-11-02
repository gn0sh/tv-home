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

    @Serializable class WeatherDto(val tempF: Int, val condition: String, val precipNow: String? = null, val nextPrecip: String? = null)
    @Serializable class GameDto(
        val team: String, val start: String, val opponent: String, val home: Boolean, val phase: String,
        val ownScore: Int? = null, val oppScore: Int? = null, val period: String? = null,
        val networks: List<String> = emptyList(),
    )

    val weather: KSerializer<WeatherNow> = mapped(WeatherDto.serializer(),
        { WeatherDto(it.tempF, it.condition, it.precipNow?.name, it.nextPrecip) },
        { d ->
            require(d.tempF in -100..150)
            val precip = d.precipNow?.let { p -> Precip.entries.first { it.name == p } }
            val cond = if (d.condition.isEmpty()) "" else requireNotNull(Feeds.clean(d.condition))
            WeatherNow(d.tempF, cond, precip, d.nextPrecip?.let { requireNotNull(Feeds.clean(it)) })
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
