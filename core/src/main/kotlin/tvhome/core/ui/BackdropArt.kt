package tvhome.core.ui

/**
 * What the hero backdrop draws over its image, and the worst pixel it can produce under the hero text (R3). The app
 * draws: image, then the theme's gradient tint at [TINT_ALPHA] when the image was rendered for another theme, then the
 * snow or rain particles, then the scrim ([HeroTheme.scrim] at [GlanceTokens.SCRIM_ALPHA]). GlanceTokensTest checks
 * [worstUnderText] for every theme.
 */
object BackdropArt {
    /** How much of the theme's gradient goes over an image rendered for another theme's palette. */
    const val TINT_ALPHA = 0.38f

    /** The strongest snowflake (white) and raindrop alpha the particle drawings use, before [precipScale]. */
    const val SNOW_MAX_ALPHA = 0.75f
    const val RAIN = 0xAFC8F0
    const val RAIN_MAX_ALPHA = 0.45f

    /** The backdropTop of the theme each image was rendered for (MOTIFS in tools/art/render_backdrops.py). */
    fun renderedTop(motif: HeroMotif): Int = when (motif) {
        HeroMotif.DAWN -> 0x1F3B66
        HeroMotif.DAY -> 0x1B4A86
        HeroMotif.SUNSET -> 0x2E2250
        HeroMotif.STARS -> 0x04060B
        HeroMotif.MOON_BATS -> 0x120A1E
        HeroMotif.SNOW -> 0x0A2418
        HeroMotif.GAME -> 0x2A0707 // Ohio State
        HeroMotif.ARENA -> 0x041E42 // Predators
    }

    fun tinted(theme: HeroTheme): Boolean = theme.backdropTop != renderedTop(theme.motif)

    /**
     * The brightest art pixel under the hero text for [theme], before particles and scrim: the image's text-zone peak
     * ([BackdropPeaks]), with the theme's gradient tint over it when the image belongs to another theme (the tint is
     * a vertical gradient, so the worst of its two ends).
     */
    fun artPeak(theme: HeroTheme): Int {
        val peak = BackdropPeaks.of(theme.motif)
        if (!tinted(theme)) return peak
        return listOf(theme.backdropTop, theme.backdropBottom).map { blend(peak, it, TINT_ALPHA) }
            .maxBy { Tokens.luminance(it) }
    }

    /**
     * The multiplier (0..1) on the particle alphas for [theme], so a [colour] particle at [maxAlpha] over the art's
     * brightest pixel still keeps 7:1 for both hero text colours once scrimmed (precip-over-team-scrim). 1 under a
     * black scrim (any pixel at 25% is fine there); smaller under team-coloured scrims.
     */
    fun precipScale(theme: HeroTheme, colour: Int, maxAlpha: Float): Float {
        fun ok(s: Float) = reads(GlanceTokens.composite(blend(artPeak(theme), colour, maxAlpha * s), theme.scrim))
        if (ok(1f)) return 1f
        var lo = 0f
        var hi = 1f
        repeat(20) { val mid = (lo + hi) / 2; if (ok(mid)) lo = mid else hi = mid }
        return lo
    }

    /** The worst composited pixel under the hero text: the art peak with the strongest snowflake or raindrop. */
    fun worstUnderText(theme: HeroTheme): List<Int> {
        val peak = artPeak(theme)
        val snow = blend(peak, 0xFFFFFF, SNOW_MAX_ALPHA * precipScale(theme, 0xFFFFFF, SNOW_MAX_ALPHA))
        val rain = blend(peak, RAIN, RAIN_MAX_ALPHA * precipScale(theme, RAIN, RAIN_MAX_ALPHA))
        return listOf(peak, snow, rain).map { GlanceTokens.composite(it, theme.scrim) }
    }

    private fun reads(px: Int) =
        Tokens.contrast(GlanceTokens.HERO_TEXT, px) >= 7.0 && Tokens.contrast(GlanceTokens.HERO_SECONDARY, px) >= 7.0

    /** [b] over [a] at alpha [t], per sRGB channel (as Compose blends). */
    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return kotlin.math.round(x + (y - x) * t).toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
