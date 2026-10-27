package com.kahraba.power

import android.content.Context

/**
 * The user's general area (district, and optionally a town), chosen by hand, never from GPS.
 * Kept only on the phone; added to feedback emails so testers' results can be grouped by area.
 */
object Area {
    /** Lebanon's districts (cazas), with Beirut. */
    val DISTRICTS = listOf(
        "Akkar", "Aley", "Baabda", "Baalbek", "Batroun", "Bcharre", "Beirut", "Bint Jbeil",
        "Byblos (Jbeil)", "Chouf", "Hasbaya", "Hermel", "Jezzine", "Keserwan", "Koura",
        "Marjayoun", "Matn", "Miniyeh-Danniyeh", "Nabatieh", "Rashaya", "Sidon (Saida)",
        "Tripoli", "Tyre (Sour)", "West Bekaa", "Zahle", "Zgharta",
    )

    private const val DISTRICT = "area_district"
    private const val TOWN = "area_town"
    const val TOWN_MAX = 40

    fun district(ctx: Context) = Prefs.sp(ctx).getString(DISTRICT, "").orEmpty()
    fun town(ctx: Context) = Prefs.sp(ctx).getString(TOWN, "").orEmpty()

    fun set(ctx: Context, district: String, town: String) {
        Prefs.sp(ctx).edit()
            .putString(DISTRICT, district)
            .putString(TOWN, cleanTown(town))
            .apply()
    }

    fun clear(ctx: Context) {
        Prefs.sp(ctx).edit().remove(DISTRICT).remove(TOWN).apply()
    }

    /** "Aley", "Aley · Bhamdoun", or "" when not set. */
    fun label(ctx: Context): String = label(district(ctx), town(ctx))

    fun label(district: String, town: String): String = when {
        district.isEmpty() -> ""
        town.isEmpty() || town.equals(district, ignoreCase = true) -> district
        else -> "$district · $town"
    }

    fun cleanTown(text: String) = text.replace(Regex("\\s+"), " ").trim().take(TOWN_MAX)
}
