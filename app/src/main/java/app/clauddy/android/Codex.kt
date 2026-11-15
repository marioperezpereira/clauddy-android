package app.clauddy.android

import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder

// Codex through the ChatGPT account, as the Codex CLI does it (openai/codex:
// codex-rs/login/src/device_code_auth.rs for the login, codex-rs/backend-client
// for the limits). The device-code login suits a phone: the app shows a code,
// the person approves it on auth.openai.com, nothing gets pasted back.

private const val ISSUER = "https://auth.openai.com"
private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
private const val UA = "codex-cli"
private const val DAY_S = 24 * 3600

/** What the person types on the verification page, and until when it works. */
data class DeviceCode(val url: String, val userCode: String, val deviceAuthId: String, val intervalS: Long, val expiresAt: Long)

class CodexAccount(store: Store) : Account(store, Provider.Codex) {

    /** Step 1: a one-time code to approve at `url` (it lasts 15 minutes), kept until it's used. */
    fun requestCode(): DeviceCode {
        val (status, text) = post("$ISSUER/api/accounts/deviceauth/usercode", JSONObject().put("client_id", CLIENT_ID).toString())
        if (status == 404) throw AuthError(404, "Device-code login isn't enabled for this account")
        if (status !in 200..299) throw AuthError(status, "Codex login failed ($status)")
        val j = JSONObject(text)
        val code = j.optString("user_code").ifEmpty { j.optString("usercode") }
        val dc = DeviceCode("$ISSUER/codex/device", code, j.getString("device_auth_id"), j.optString("interval").trim().toLongOrNull() ?: 5, now() + 15 * 60_000)
        store.codexPending = dc
        store.setError(provider, null)
        return dc
    }

    /**
     * Step 2: ask once whether the code was approved; if so, trade it for
     * tokens. False while it waits. The code is dropped once it's used or
     * dead, and kept through a failed connection.
     */
    fun poll(code: DeviceCode): Boolean {
        if (now() > code.expiresAt) {
            store.codexPending = null
            throw AuthError(0, "The code expired: start again")
        }
        val body = JSONObject().put("device_auth_id", code.deviceAuthId).put("user_code", code.userCode).toString()
        val (status, text) = post("$ISSUER/api/accounts/deviceauth/token", body)
        if (status == 403 || status == 404) return false
        store.codexPending = null
        if (status !in 200..299) throw AuthError(status, "Codex login failed ($status)")
        val j = JSONObject(text)
        exchange(j.getString("authorization_code"), j.getString("code_verifier"))
        return true
    }

    private fun exchange(code: String, verifier: String) {
        val form = listOf(
            "grant_type" to "authorization_code",
            "client_id" to CLIENT_ID,
            "code" to code,
            "redirect_uri" to "$ISSUER/deviceauth/callback",
            "code_verifier" to verifier,
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val (status, text) = post("$ISSUER/oauth/token", form, "application/x-www-form-urlencoded")
        if (status !in 200..299) throw AuthError(status, "Codex login failed ($status)")
        save(JSONObject(text), null)
    }

    override fun refreshGrant(refresh: String) =
        post("$ISSUER/oauth/token", JSONObject().put("grant_type", "refresh_token").put("client_id", CLIENT_ID).put("refresh_token", refresh).toString())

    override fun usageCall(token: String) = http(
        USAGE_URL,
        headers = buildMap {
            put("Authorization", "Bearer $token")
            put("User-Agent", UA)
            store.codexAccount?.let { put("chatgpt-account-id", it) }
        },
    )

    override fun parse(j: JSONObject, readAt: Long) = parseCodexUsage(j, readAt)

    // the access token is a JWT: its `exp` says when to refresh; the id token
    // names the ChatGPT workspace the usage belongs to
    override fun save(j: JSONObject, oldRefresh: String?) {
        val access = j.getString("access_token")
        val exp = claims(access)?.optLong("exp")?.takeIf { it > 0 }?.times(1000) ?: (now() + 3600_000)
        store.setTokens(provider, Tokens(access, j.optString("refresh_token").ifEmpty { oldRefresh }, exp))
        j.optString("id_token").takeIf { it.isNotEmpty() }?.let(::claims)
            ?.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")?.takeIf { it.isNotEmpty() }
            ?.let { store.codexAccount = it }
    }

    private fun post(url: String, body: String, type: String = "application/json") = http(url, body, type, mapOf("User-Agent" to UA))
}

private fun claims(jwt: String): JSONObject? = try {
    JSONObject(String(Base64.decode(jwt.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
} catch (e: Exception) {
    null
}

/**
 * Codex's `wham/usage` as limits: a window of a day or more is the week, a
 * shorter one the session (the desktop's rule).
 */
fun parseCodexUsage(j: JSONObject, readAt: Long): Usage {
    val rl = j.optJSONObject("rate_limit")
    var session: Window? = null
    var week: Window? = null
    for ((key, secondary) in listOf("primary_window" to false, "secondary_window" to true)) {
        val w = rl?.optJSONObject(key) ?: continue
        if (!w.has("used_percent")) continue
        val reset = w.optLong("reset_at", 0).takeIf { it > 0 }?.times(1000)
        val v = Window(w.getDouble("used_percent"), reset)
        val long = if (w.has("limit_window_seconds")) w.getLong("limit_window_seconds") >= DAY_S else secondary
        if (long) week = v else session = v
    }
    return usageOf(session, week, readAt)
}
