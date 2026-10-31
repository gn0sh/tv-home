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
