package app.clauddy.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The shape of chatgpt.com/backend-api/wham/usage, per openai/codex's
// RateLimitStatusPayload model.
class CodexUsageTest {
    private fun window(pct: Int, seconds: Int, resetAt: Int) =
        """{"used_percent":$pct,"limit_window_seconds":$seconds,"reset_after_seconds":0,"reset_at":$resetAt}"""

    @Test
    fun theFiveHourWindowIsTheSessionAndTheLongOneTheWeek() {
        val j = JSONObject("""{"plan_type":"plus","rate_limit":{"allowed":true,"limit_reached":false,
            "primary_window":${window(28, 18_000, 1_800_000_000)},"secondary_window":${window(64, 604_800, 1_800_500_000)}}}""")
        val u = parseCodexUsage(j, 0)
        assertEquals(28.0, u.session.pct, 0.0)
        assertEquals(1_800_000_000_000L, u.session.resetsAt)
        assertEquals(64.0, u.week.pct, 0.0)
        assertFalse(u.weeklyOnly)
    }

    @Test
    fun aPlanWithOnlyTheWeekShowsItInTheSessionsPlace() {
        val j = JSONObject("""{"plan_type":"free","rate_limit":{"allowed":true,"limit_reached":false,
            "primary_window":${window(40, 604_800, 1_800_000_000)},"secondary_window":null}}""")
        val u = parseCodexUsage(j, 0)
        assertTrue(u.weeklyOnly)
        assertEquals(40.0, u.session.pct, 0.0)
    }

    @Test
    fun noRateLimitReadsAsEmpty() {
        val u = parseCodexUsage(JSONObject("""{"plan_type":"plus","rate_limit":null}"""), 0)
        assertEquals(0.0, u.session.pct, 0.0)
        assertFalse(u.weeklyOnly)
    }
}
