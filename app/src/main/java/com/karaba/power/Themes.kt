package com.karaba.power

/** The colour themes (generated): key, name, description, light?, swatch background and dot. */
object Themes {
    data class Theme(val key: String, val name: String, val description: String, val light: Boolean, val bg: Int, val dot: Int, val style: Int)

    val all = listOf(
        Theme("lamplight", "Lamplight", "Serif & brass · warm dark", false, 0xFF12100D.toInt(), 0xFFC9A86A.toInt(), R.style.Palette_Lamplight),
        Theme("sky", "Sky", "Deep blue · clear night", false, 0xFF0B1426.toInt(), 0xFF8DBBFF.toInt(), R.style.Palette_Sky),
        Theme("coral", "Coral", "Coral on deep plum", false, 0xFF1A0F17.toInt(), 0xFFFF8F6B.toInt(), R.style.Palette_Coral),
        Theme("phosphor", "Phosphor", "Monospace · terminal green", false, 0xFF050805.toInt(), 0xFF5CFF7A.toInt(), R.style.Palette_Phosphor),
        Theme("slate", "Slate", "Silver on slate · greyscale", false, 0xFF121418.toInt(), 0xFFD7DCE3.toInt(), R.style.Palette_Slate),
        Theme("twilight", "Twilight", "Periwinkle on charcoal", false, 0xFF12121C.toInt(), 0xFFAFADFF.toInt(), R.style.Palette_Twilight),
        Theme("ice", "Ice", "Steel blue · pale ice", true, 0xFFEEF3F8.toInt(), 0xFF2F5D8A.toInt(), R.style.Palette_Ice),
        Theme("blush", "Blush", "Rose · soft pink", true, 0xFFFBF1F4.toInt(), 0xFFA8435F.toInt(), R.style.Palette_Blush),
        Theme("parchment", "Parchment", "Serif & sepia · warm paper", true, 0xFFF3E9D2.toInt(), 0xFF6B4A2B.toInt(), R.style.Palette_Parchment),
        Theme("newsprint", "Newsprint", "Ink & serif · newspaper", true, 0xFFE9E7E2.toInt(), 0xFF1C1C1C.toInt(), R.style.Palette_Newsprint),
        Theme("receipt", "Receipt", "Monospace · printed slip", true, 0xFFF2F2EE.toInt(), 0xFF2B3A33.toInt(), R.style.Palette_Receipt),
    )

    const val DEFAULT = "lamplight"

    fun byKey(key: String?): Theme = all.firstOrNull { it.key == key } ?: all.first { it.key == DEFAULT }
}
