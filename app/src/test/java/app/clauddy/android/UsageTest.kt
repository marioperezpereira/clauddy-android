package app.clauddy.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {
    @Test
    fun readsTheSessionAndTheWeek() {
        val j = JSONObject("""{"five_hour":{"utilization":42.0,"resets_at":"2026-11-14T16:00:00+00:00"},"seven_day":{"utilization":68.0,"resets_at":null}}""")
        val u = parseClaudeUsage(j, 0)
        assertEquals(42.0, u.session.pct, 0.0)
        assertEquals(1_794_672_000_000L, u.session.resetsAt)
        assertEquals(68.0, u.week.pct, 0.0)
        assertNull(u.week.resetsAt)
        assertFalse(u.weeklyOnly)
    }

    @Test
    fun aPlanWithNoFiveHourWindowShowsItsWeekInTheSessionsPlace() {
        val u = parseClaudeUsage(JSONObject("""{"five_hour":null,"seven_day":{"utilization":64.0}}"""), 0)
        assertTrue(u.weeklyOnly)
        assertEquals(64.0, u.session.pct, 0.0)
    }

    @Test
    fun anIdleSessionWithNoResetIsStillASession() {
        val u = parseClaudeUsage(JSONObject("""{"five_hour":{"utilization":0.0},"seven_day":{"utilization":10.0}}"""), 0)
        assertFalse(u.weeklyOnly)
        assertEquals(0.0, u.session.pct, 0.0)
    }

    @Test
    fun bothServicesShareTheWeeklyOnlyRule() {
        val week = Window(40.0, null)
        assertTrue(usageOf(null, week, 0).weeklyOnly)
        assertEquals(40.0, usageOf(null, week, 0).session.pct, 0.0)
        assertFalse(usageOf(Window(5.0, null), week, 0).weeklyOnly)
        assertFalse(usageOf(null, null, 0).weeklyOnly)
    }

    @Test
    fun saysHowOldAReadingIs() {
        assertEquals("just now", ageText(0, 30_000))
        assertEquals("45m ago", ageText(0, 45 * 60_000L))
    }

    @Test
    fun countsDownToAReset() {
        assertEquals("2h 13m", untilText(133 * 60_000L, 0))
        assertEquals("4d 3h", untilText((4 * 24 + 3) * 3_600_000L, 0))
        assertEquals("0m", untilText(0, 5))
    }

    @Test
    fun aRisingSessionIsWorkAndAResetIsNot() {
        fun u(pct: Double) = Usage(Window(pct, null), Window(50.0, null), false, 0)
        assertTrue(Refresh.rose(u(40.0), u(42.0)))
        assertFalse(Refresh.rose(u(42.0), u(42.0)))
        assertFalse(Refresh.rose(u(95.0), u(3.0)))
        assertFalse(Refresh.rose(null, u(42.0)))
    }

    // the window counts as new once its reset time went by, even if readings
    // were missed and the % already climbed back
    @Test
    fun aPassedResetTimeIsANewWindow() {
        val before = Usage(Window(95.0, 1_000L), Window(50.0, null), false, 0)
        assertTrue(Refresh.newWindow(before, Usage(Window(92.0, 9_000L), Window(60.0, null), false, 1_000L)))
        assertFalse(Refresh.newWindow(before, Usage(Window(97.0, 1_000L), Window(60.0, null), false, 999L)))
    }

    @Test
    fun withoutAResetTimeABigDropIsANewWindow() {
        val before = Usage(Window(95.0, null), Window(50.0, null), false, 0)
        assertTrue(Refresh.newWindow(before, Usage(Window(3.0, null), Window(50.0, null), false, 1)))
        assertFalse(Refresh.newWindow(before, Usage(Window(90.0, null), Window(50.0, null), false, 1)))
    }
}
