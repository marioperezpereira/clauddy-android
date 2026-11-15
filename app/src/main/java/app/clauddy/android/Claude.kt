package app.clauddy.android

import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

// Claude OAuth, the same flow and public client as Claude Code and the desktop
// Clauddy (native/src/auth.rs), plus the account's usage endpoint.

private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
private const val REDIRECT = "https://platform.claude.com/oauth/code/callback"
private const val AUTHORIZE = "https://claude.ai/oauth/authorize"
private const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
private const val SCOPE = "org:create_api_key user:profile user:inference"
private const val UA = "claude-cli/2.1.181 (external, cli)"
private const val SETUP_TOKEN_PREFIX = "sk-ant-oat"

class ClaudeAccount(store: Store) : Account(store, Provider.Claude) {

    /** Step 1: the authorize URL; the verifier and state wait in the store. */
    fun begin(): String {
        val verifier = randomB64()
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomB64()
        store.pending = verifier to state
        store.setError(provider, null)
        val params = listOf(
            "code" to "true",
            "client_id" to CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to REDIRECT,
            "scope" to SCOPE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        return AUTHORIZE + "?" + params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
    }

    /** Step 2: the pasted "code#state", or a long-lived `claude setup-token`. */
    fun complete(pasted: String) {
        val raw = pasted.trim()
        if (raw.startsWith(SETUP_TOKEN_PREFIX)) {
            store.setTokens(provider, Tokens(raw, null, now() + 365L * 86_400_000))
            store.pending = null
            return
        }
        if (!raw.contains('#')) throw AuthError(0, "That isn't the whole code: copy all of it from the page")
        val (verifier, state) = store.pending ?: throw AuthError(0, "Tap Connect first, then paste the code")
        val code = raw.substringBefore('#')
        val returned = raw.substringAfter('#').substringBefore('#')
        if (returned.isNotEmpty() && returned != state) throw AuthError(0, "That code is from another login: tap Connect again")
        val body = JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("state", returned.ifEmpty { state })
            .put("code_verifier", verifier)
            .put("client_id", CLIENT_ID)
            .put("redirect_uri", REDIRECT)
        val (status, text) = http(TOKEN_URL, body.toString(), headers = mapOf("User-Agent" to UA))
        if (status !in 200..299) throw AuthError(status, "Login failed ($status): ${text.take(150)}")
        save(JSONObject(text), null)
        store.pending = null
    }

    override fun refreshGrant(refresh: String) = http(
        TOKEN_URL,
        JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh).put("client_id", CLIENT_ID).toString(),
        headers = mapOf("User-Agent" to UA),
    )

    override fun usageCall(token: String) = http(
        USAGE_URL,
        headers = mapOf(
            "Authorization" to "Bearer $token",
            "anthropic-beta" to "oauth-2025-04-20",
            "anthropic-version" to "2023-06-01",
            "User-Agent" to UA,
        ),
    )

    override fun parse(j: JSONObject, readAt: Long) = parseClaudeUsage(j, readAt)

    override fun save(j: JSONObject, oldRefresh: String?) = store.setTokens(
        provider,
        Tokens(
            access = j.optString("access_token"),
            refresh = j.optString("refresh_token").ifEmpty { oldRefresh },
            expiresAt = now() + j.optLong("expires_in", 3600).coerceAtLeast(1) * 1000,
        ),
    )

    private fun randomB64(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let(::b64url)
    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

/** The usage response as limits: `five_hour` is the session, `seven_day` the week. */
fun parseClaudeUsage(j: JSONObject, readAt: Long): Usage {
    fun window(key: String): Window? {
        val o = j.optJSONObject(key) ?: return null
        if (!o.has("utilization") || o.isNull("utilization")) return null
        val reset = o.optString("resets_at").takeIf { it.isNotEmpty() && it != "null" }?.let(::parseInstant)
        return Window(o.getDouble("utilization"), reset)
    }
    return usageOf(window("five_hour"), window("seven_day"), readAt)
}

private fun parseInstant(s: String): Long? = try {
    java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
} catch (e: Exception) {
    null
}
