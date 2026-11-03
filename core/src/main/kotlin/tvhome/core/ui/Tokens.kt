package tvhome.core.ui

import kotlin.math.pow

/**
 * Design tokens (docs/design/tv-home-m1-agenda.md, Type and colour) as plain 0xRRGGBB ints, so the contrast ratios
 * the design requires are checked by a JVM test. The app maps them to Compose colours.
 */
object Tokens {
    const val BACKGROUND = 0x101418
    const val TEXT = 0xF2F4F7
    const val SECONDARY = 0xB8C0CC
    const val AMBER = 0xFFC247
    const val FOCUS_FILL = 0x243044
    const val FOCUS_RING = 0x8AB4F8

    // Sizes: sp for text, dp for layout.
    const val HEADING_SP = 36
    const val DAY_HEADING_SP = 30
    const val ROW_SP = 28
    const val SECONDARY_SP = 24
    const val AGE_SP = 22
    const val ROW_HEIGHT_DP = 64
    const val ROW_GAP_DP = 8
    const val TIME_COLUMN_DP = 120
    const val MAX_CONTENT_WIDTH_DP = 1100
    const val RING_DP = 4
    const val MARGIN_H_DP = 64
    const val MARGIN_V_DP = 36

    /**
     * A grey with every channel at [fraction] of 0xFF, rounded up: the brightest a white pixel can be under a black scrim
     * (rounding up is the worse case for light text).
     */
    fun white(fraction: Float): Int {
        val c = kotlin.math.ceil(255 * fraction.toDouble() - 1e-9).toInt().coerceIn(0, 255)
        return (c shl 16) or (c shl 8) or c
    }

    /** WCAG 2.x relative luminance of an 0xRRGGBB colour. */
    fun luminance(rgb: Int): Double {
        fun ch(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    /** WCAG contrast ratio, 1.0 to 21.0. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

/**
 * M7 home-screen type scale and colours (ADR 0008 section 3; task M7-F1). Sizes are sp at 1280x720 dp, where 1 sp is
 * 1.5 physical px at 1080p, so [MIN_SP] 22 sp = 33 px (spec R3: at least 32 px). Hero text sits on the backdrop
 * scrimmed to [BACKDROP_BRIGHTNESS]: the test checks every text colour against a pure-white pixel at that brightness,
 * so it holds over any art. The alert card is an opaque fill, never a border (R4).
 */
object GlanceTokens {
    const val MIN_SP = 22
    const val CLOCK_SP = 40
    const val DATE_SP = 24
    const val CHIP_SP = 24
    const val HERO_SP = 44
    const val HERO_LINE2_SP = 28
    const val AGE_SP = 22
    const val ALERT_SP = 40
    const val ALERT_UNTIL_SP = 28
    const val CARD_SP = 32
    const val BADGE_SP = 22

    /** The backdrop is drawn under a black scrim so its brightest pixel is 25% of white (spec "about 35%"; ADR 0008). */
    const val BACKDROP_BRIGHTNESS = 0.25f
    val SCRIM_ALPHA: Float get() = 1f - BACKDROP_BRIGHTNESS

    /** Hero text colours: the [HeroTheme] defaults, here so the test names them. */
    const val HERO_TEXT = 0xF4F1EA
    const val HERO_SECONDARY = 0xD5DCE8

    /** Severe-alert card: hero text on a deep red fill. */
    const val ALERT_FILL = 0x5C1010
    const val ALERT_TEXT = HERO_TEXT

    /** Every size the M7 hero and top strip draw, for the floor test. */
    val ALL_SP = listOf(CLOCK_SP, DATE_SP, CHIP_SP, HERO_SP, HERO_LINE2_SP, AGE_SP, ALERT_SP, ALERT_UNTIL_SP, CARD_SP, BADGE_SP)
}
