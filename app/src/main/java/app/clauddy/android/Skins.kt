package app.clauddy.android

import java.time.LocalDate

// The pets to pick from, as on the desktop (native/src/components/skins.rs):
// Clauddy and its costumes, each a moods sheet (4 x 4) in the same layout.
// Seasonal packs take over on their days: one pet per pack and year, the same
// all day, picked by a hash and never stored.

/**
 * A pet: its moods sheet (4 x 4), its desk scene (4 x 2), and where the art
 * sits inside a frame of each, as fractions: (left, top, right, bottom).
 */
class Skin(
    val id: String,
    val name: String,
    val pack: String,
    val sheet: Int,
    val workSheet: Int,
    val workBox: FloatArray,
    val fireBox: FloatArray,
    val standBox: FloatArray,
)

object Skins {
    const val DEFAULT = "clauddy"

    val ALL = listOf(
        Skin("clauddy", "Clauddy", "everyday", R.drawable.pet_moods, R.drawable.pet_working, floatArrayOf(0.047f, 0.203f, 0.961f, 0.93f), floatArrayOf(0.086f, 0.062f, 0.898f, 0.812f), floatArrayOf(0.18f, 0.27f, 0.84f, 0.79f)),
        Skin("wizard", "Wizard", "everyday", R.drawable.pet_wizard_moods, R.drawable.pet_wizard_working, floatArrayOf(0.047f, 0.051f, 0.961f, 0.93f), floatArrayOf(0.031f, 0.012f, 0.91f, 0.844f), floatArrayOf(0.059f, 0.07f, 0.852f, 0.828f)),
        Skin("astronaut", "Astronaut", "everyday", R.drawable.pet_astronaut_moods, R.drawable.pet_astronaut_working, floatArrayOf(0.047f, 0.039f, 0.961f, 0.934f), floatArrayOf(0.09f, 0.062f, 0.898f, 0.883f), floatArrayOf(0.164f, 0.172f, 0.891f, 0.848f)),
        Skin("santa", "Santa", "christmas", R.drawable.pet_santa_moods, R.drawable.pet_santa_working, floatArrayOf(0.043f, 0.035f, 0.965f, 0.934f), floatArrayOf(0.09f, 0.074f, 0.941f, 0.875f), floatArrayOf(0.141f, 0.172f, 0.941f, 0.871f)),
        Skin("reindeer", "Reindeer", "christmas", R.drawable.pet_reindeer_moods, R.drawable.pet_reindeer_working, floatArrayOf(0.043f, 0.07f, 0.965f, 0.934f), floatArrayOf(0.086f, 0.059f, 0.906f, 0.832f), floatArrayOf(0.176f, 0.152f, 0.836f, 0.812f)),
        Skin("gingerbread", "Gingerbread", "christmas", R.drawable.pet_gingerbread_moods, R.drawable.pet_gingerbread_working, floatArrayOf(0.047f, 0.145f, 0.965f, 0.938f), floatArrayOf(0.082f, 0.062f, 0.91f, 0.816f), floatArrayOf(0.121f, 0.238f, 0.875f, 0.793f)),
        Skin("pumpkin", "Pumpkin", "halloween", R.drawable.pet_pumpkin_moods, R.drawable.pet_pumpkin_working, floatArrayOf(0.047f, 0.109f, 0.969f, 0.934f), floatArrayOf(0.082f, 0.0f, 0.91f, 0.785f), floatArrayOf(0.176f, 0.16f, 0.93f, 0.785f)),
    )

    // (month, day) each pack takes over on
    private val PACK_DAYS = mapOf(
        "halloween" to listOf(10 to 31),
        "christmas" to listOf(12 to 24, 12 to 25),
    )

    fun find(id: String) = ALL.firstOrNull { it.id == id }

    // FNV-1a over UTF-16 code units, as the desktop and the Electron app do
    fun hash(text: String): Int {
        var h = 0x811c9dc5.toInt()
        for (ch in text) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h
    }

    /** The season's pet on its days, if its pack has any. */
    fun seasonal(date: LocalDate): Skin? {
        val pack = PACK_DAYS.entries.firstOrNull { (_, days) -> (date.monthValue to date.dayOfMonth) in days }?.key ?: return null
        val pool = ALL.filter { it.pack == pack }
        return pool[Integer.toUnsignedLong(hash("${date.year}:$pack")).rem(pool.size).toInt()]
    }

    /** What the pet wears: the season's on its days, else the saved choice. */
    fun active(chosen: String, date: LocalDate = LocalDate.now()) = seasonal(date) ?: find(chosen) ?: ALL[0]
}
