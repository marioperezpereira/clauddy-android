package app.clauddy.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// What a paste into the Claude field may become: none of these reach the network.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthCompleteTest {
    private val store get() = Store(RuntimeEnvironment.getApplication())

    @Test
    fun aSetupTokenIsKept() {
        ClaudeAccount(store).complete("  sk-ant-oat01-abc  ")
        assertEquals("sk-ant-oat01-abc", store.tokens(Provider.Claude)?.access)
    }

    @Test
    fun halfACodeIsRefusedNotSavedAsAToken() {
        store.pending = "verifier" to "state"
        assertThrows(AuthError::class.java) { ClaudeAccount(store).complete("just-the-code-part") }
        assertNull(store.tokens(Provider.Claude))
    }

    @Test
    fun aCodeFromAnotherLoginIsRefused() {
        store.pending = "verifier" to "state"
        assertThrows(AuthError::class.java) { ClaudeAccount(store).complete("code#other-state") }
        assertNull(store.tokens(Provider.Claude))
    }

    @Test
    fun aCodeWithoutConnectFirstIsRefused() {
        assertThrows(AuthError::class.java) { ClaudeAccount(store).complete("code#state") }
        assertNull(store.tokens(Provider.Claude))
    }
}
