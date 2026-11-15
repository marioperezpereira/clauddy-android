package app.clauddy.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SkinsTest {
    @Test
    fun theHashMatchesTheDesktops() {
        assertEquals(0x811c9dc5.toInt(), Skins.hash(""))
        assertEquals(0xe40c292c.toInt(), Skins.hash("a"))
    }

    @Test
    fun theSavedPetShowsAndAnUnknownOneFallsBack() {
        assertEquals("astronaut", Skins.active("astronaut", LocalDate.of(2026, 11, 6)).id)
        assertEquals(Skins.DEFAULT, Skins.active("nope", LocalDate.of(2026, 11, 6)).id)
    }

    @Test
    fun christmasPicksOneOfItsPetsForBothDays() {
        val eve = Skins.seasonal(LocalDate.of(2026, 12, 24))!!
        assertEquals("christmas", eve.pack)
        assertEquals(eve.id, Skins.seasonal(LocalDate.of(2026, 12, 25))!!.id)
        assertEquals("wizard", Skins.active("wizard", LocalDate.of(2026, 12, 26)).id)
    }

    @Test
    fun halloweenBringsThePumpkinAndOtherDaysNothing() {
        assertEquals("pumpkin", Skins.seasonal(LocalDate.of(2026, 10, 31))!!.id)
        assertNull(Skins.seasonal(LocalDate.of(2026, 11, 6)))
    }
}
