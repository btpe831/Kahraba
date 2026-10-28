package com.kahraba.power

/**
 * The colour themes. Each has a light and a dark version (palettes.xml); which one shows depends on
 * Settings > Appearance (automatic follows the phone's dark mode).
 */
object Themes {
    /** One version of a theme: its swatch colours (background and dot) and its style. */
    data class Variant(val bg: Int, val dot: Int, val style: Int)

    data class Theme(val key: String, val name: String, val description: String, val light: Variant, val dark: Variant) {
        fun variant(dark: Boolean) = if (dark) this.dark else light
    }

    private fun v(bg: Long, dot: Long, style: Int) = Variant(bg.toInt(), dot.toInt(), style)

    val all = listOf(
        Theme("lamplight", "Lamplight", "Serif & brass · warm",
            v(0xFFF5F2ED, 0xFF7C602D, R.style.Palette_Lamplight_Light), v(0xFF12100D, 0xFFC9A86A, R.style.Palette_Lamplight_Dark)),
        Theme("sky", "Sky", "Deep blue · clear sky",
            v(0xFFECF0F6, 0xFF1761CF, R.style.Palette_Sky_Light), v(0xFF0B1426, 0xFF8DBBFF, R.style.Palette_Sky_Dark)),
        Theme("coral", "Coral", "Coral & plum",
            v(0xFFF6EEEC, 0xFFB33B14, R.style.Palette_Coral_Light), v(0xFF1A0F17, 0xFFFF8F6B, R.style.Palette_Coral_Dark)),
        Theme("phosphor", "Phosphor", "Monospace · terminal green",
            v(0xFFECF6EE, 0xFF0D7721, R.style.Palette_Phosphor_Light), v(0xFF050805, 0xFF5CFF7A, R.style.Palette_Phosphor_Dark)),
        Theme("slate", "Slate", "Silver & slate · greyscale",
            v(0xFFEFF1F2, 0xFF3D434B, R.style.Palette_Slate_Light), v(0xFF121418, 0xFFD7DCE3, R.style.Palette_Slate_Dark)),
        Theme("twilight", "Twilight", "Periwinkle & charcoal",
            v(0xFFECECF6, 0xFF4D4AB5, R.style.Palette_Twilight_Light), v(0xFF12121C, 0xFFAFADFF, R.style.Palette_Twilight_Dark)),
        Theme("ice", "Ice", "Steel blue · ice",
            v(0xFFEEF3F8, 0xFF2F5D8A, R.style.Palette_Ice_Light), v(0xFF0C1115, 0xFF7EA9D3, R.style.Palette_Ice_Dark)),
        Theme("blush", "Blush", "Rose · soft pink",
            v(0xFFFBF1F4, 0xFFA8435F, R.style.Palette_Blush_Light), v(0xFF140D0F, 0xFFD590A3, R.style.Palette_Blush_Dark)),
        Theme("parchment", "Parchment", "Serif & sepia · paper",
            v(0xFFF3E9D2, 0xFF6B4A2B, R.style.Palette_Parchment_Light), v(0xFF14100D, 0xFFCA9D72, R.style.Palette_Parchment_Dark)),
        Theme("newsprint", "Newsprint", "Ink & serif · newspaper",
            v(0xFFE9E7E2, 0xFF1C1C1C, R.style.Palette_Newsprint_Light), v(0xFF111111, 0xFFE8E6E1, R.style.Palette_Newsprint_Dark)),
        Theme("receipt", "Receipt", "Monospace · printed slip",
            v(0xFFF2F2EE, 0xFF2B3A33, R.style.Palette_Receipt_Light), v(0xFF0F1211, 0xFFBFCBC4, R.style.Palette_Receipt_Dark)),
    )

    const val DEFAULT = "lamplight"

    fun byKey(key: String?): Theme = all.firstOrNull { it.key == key } ?: all.first { it.key == DEFAULT }
}
