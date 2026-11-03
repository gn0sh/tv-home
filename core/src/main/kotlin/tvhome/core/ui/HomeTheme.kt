package tvhome.core.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The home screen's look in one place, so it can be reskinned later (by voice, via the remote) without a rebuild.
 * Colours are 0xRRGGBB ints; sizes are dp. Defaults are the M1 design tokens.
 */
data class HomeTheme(
    val background: Int = Tokens.BACKGROUND,
    val tile: Int = 0x1C222B,
    val focusFill: Int = Tokens.FOCUS_FILL,
    val focusRing: Int = Tokens.FOCUS_RING,
    val text: Int = Tokens.TEXT,
    val secondaryText: Int = Tokens.SECONDARY,
    val tileWidthDp: Int = 192,
    val tileHeightDp: Int = 108,
) {
    /**
     * This theme on a game day whose hero asks for a [screen] background and [tile] fill (Predators navy), or this
     * theme unchanged when either is null or the result would not read (game-screen-theme-contrast): the theme's text
     * and secondary text keep 7:1 on the screen and on the tile, and the tile and the focused fill stand out from the
     * screen by at least [GAME_SEPARATION]:1, so a voice-set custom theme never ends up unreadable.
     */
    fun forGameDay(screen: Int?, tile: Int?): HomeTheme {
        if (screen == null || tile == null) return this
        val reads = listOf(screen, tile).all { bg ->
            Tokens.contrast(text, bg) >= 7.0 && Tokens.contrast(secondaryText, bg) >= 7.0
        }
        val separate = Tokens.contrast(tile, screen) >= GAME_SEPARATION && Tokens.contrast(focusFill, screen) >= GAME_SEPARATION
        return if (reads && separate) copy(background = screen, tile = tile) else this
    }

    companion object {
        val DEFAULT = HomeTheme()

        /** Luminance contrast the tile and focused fill keep against a game-day screen (the default pair is about 1.16). */
        const val GAME_SEPARATION = 1.3

        /** Files larger than this are not read at all. */
        const val MAX_BYTES = 4096

        /**
         * What the home zone map fits (layout-tile-range): taller tiles squeeze the hero below [HomeLayout.HERO_MIN_DP],
         * wider ones push the dock's pills past the safe area. HeroLayoutTest checks both ends.
         */
        val TILE_WIDTH_DP = 120..200
        val TILE_HEIGHT_DP = 68..120

        private val HEX = Regex("#[0-9A-Fa-f]{6}")
        private val COLOURS = setOf("background", "tile", "focusFill", "focusRing", "text", "secondaryText")
        private val SIZES = setOf("tileWidthDp", "tileHeightDp")

        /**
         * Strict: a JSON object whose keys are all known, colours "#RRGGBB" strings, sizes whole numbers in range.
         * Missing keys keep their defaults. Anything else (null, too long, bad JSON, an unknown key, a wrong type or an
         * out-of-range value) gives [DEFAULT] for the whole theme, never a half-applied one.
         */
        fun parse(text: String?): HomeTheme {
            if (text == null || text.length > MAX_BYTES) return DEFAULT
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return DEFAULT
            if (obj.keys.any { it !in COLOURS && it !in SIZES }) return DEFAULT
            fun colour(key: String, def: Int): Int? {
                val v = obj[key] ?: return def
                val p = v as? JsonPrimitive ?: return null
                if (!p.isString || !HEX.matches(p.content)) return null
                return p.content.substring(1).toInt(16)
            }
            fun size(key: String, def: Int, range: IntRange): Int? {
                val v = obj[key] ?: return def
                val p = v as? JsonPrimitive ?: return null
                if (p.isString) return null
                val n = p.intOrNull ?: return null
                return n.takeIf { it in range }
            }
            return HomeTheme(
                background = colour("background", DEFAULT.background) ?: return DEFAULT,
                tile = colour("tile", DEFAULT.tile) ?: return DEFAULT,
                focusFill = colour("focusFill", DEFAULT.focusFill) ?: return DEFAULT,
                focusRing = colour("focusRing", DEFAULT.focusRing) ?: return DEFAULT,
                text = colour("text", DEFAULT.text) ?: return DEFAULT,
                secondaryText = colour("secondaryText", DEFAULT.secondaryText) ?: return DEFAULT,
                tileWidthDp = size("tileWidthDp", DEFAULT.tileWidthDp, TILE_WIDTH_DP) ?: return DEFAULT,
                tileHeightDp = size("tileHeightDp", DEFAULT.tileHeightDp, TILE_HEIGHT_DP) ?: return DEFAULT,
            )
        }
    }
}
