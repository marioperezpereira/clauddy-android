package app.clauddy.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// The login has to outlive the screen: Android may close the app while the
// person approves the code on OpenAI's page.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodexLoginTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun aWaitingCodeSurvivesANewScreen() {
        val dc = DeviceCode("https://auth.openai.com/codex/device", "ABCD-1234", "dev_1", 5, now() + 600_000)
        Store(ctx).codexPending = dc
        assertEquals(dc, Store(ctx).codexPending)
    }

    @Test
    fun anExpiredCodeReadsAsNone() {
        Store(ctx).codexPending = DeviceCode("u", "ABCD-1234", "dev_1", 5, now() - 1)
        assertNull(Store(ctx).codexPending)
    }

    @Test
    fun disconnectingDropsAWaitingCode() {
        val s = Store(ctx)
        s.codexPending = DeviceCode("u", "ABCD-1234", "dev_1", 5, now() + 600_000)
        s.logout(Provider.Codex)
        assertNull(Store(ctx).codexPending)
    }
}
