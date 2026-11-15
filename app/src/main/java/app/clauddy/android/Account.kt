package app.clauddy.android

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// What both services share: the limits as the pet shows them, one HTTP call,
// and the token flow around reading the usage. Each service only says where
// its endpoints are and how its answers read (Claude.kt, Codex.kt).

fun now() = System.currentTimeMillis()

/** An HTTP status, or 0 for "try again later" (never "log in again"). */
class AuthError(val status: Int, message: String) : Exception(message)

data class Tokens(val access: String, val refresh: String?, val expiresAt: Long)

/** One limit window: its % and when it resets (epoch ms), if known. */
data class Window(val pct: Double, val resetsAt: Long?) {
    /** High enough to show in the urgent colour. */
    val hot get() = pct >= 80
}

data class Usage(val session: Window, val week: Window, val weeklyOnly: Boolean, val readAt: Long) {
    val stale get() = now() - readAt > STALE_MS

    companion object {
        /** A reading older than this is shown as old. */
        const val STALE_MS = 30 * 60_000L
    }
}

/** A plan without a session window shows its week in the session's place (the desktop's rule). */
fun usageOf(session: Window?, week: Window?, readAt: Long): Usage {
    val empty = Window(0.0, null)
    return Usage(session ?: week ?: empty, week ?: empty, session == null && week != null, readAt)
}

fun pctText(pct: Double) = "%.0f%%".format(pct)

/** The alert step a session % has reached: 100 at the limit, 90 running hot, else 0. */
fun level(pct: Double) = when {
    pct >= 100 -> 100
    pct >= 90 -> 90
    else -> 0
}

/**
 * One call: a GET, or a POST when there's a body. A connection that fails at
 * any step is "offline", never a crash.
 */
fun http(url: String, body: String? = null, type: String = "application/json", headers: Map<String, String> = emptyMap()): Pair<Int, String> {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        headers.forEach(conn::setRequestProperty)
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", type)
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
    } catch (e: IOException) {
        throw AuthError(0, "offline: ${e.message}")
    } finally {
        conn?.disconnect()
    }
}

/** A connected service: its tokens in the store, kept fresh while its usage is read. */
abstract class Account(protected val store: Store, val provider: Provider) {
    /** POSTs the refresh grant. */
    protected abstract fun refreshGrant(refresh: String): Pair<Int, String>

    /** GETs the usage with `token`. */
    protected abstract fun usageCall(token: String): Pair<Int, String>

    protected abstract fun parse(j: JSONObject, readAt: Long): Usage

    /** Keeps the tokens a grant returned; a new refresh token, or the old one. */
    protected abstract fun save(j: JSONObject, oldRefresh: String?)

    /** The account's own %; a token near its end, or a rejected one, gets one refresh first. */
    fun fetchUsage(): Usage {
        var t = store.tokens(provider) ?: throw AuthError(401, "not connected")
        if (t.expiresAt - now() < 60_000) t = refresh(t)
        var (status, text) = usageCall(t.access)
        if (status == 401 && t.refresh != null) {
            val again = usageCall(refresh(t).access)
            status = again.first
            text = again.second
        }
        if (status !in 200..299) throw AuthError(status, "usage $status: ${text.take(150)}")
        return parse(JSONObject(text), now())
    }

    private fun refresh(t: Tokens): Tokens {
        val refresh = t.refresh?.takeIf { it.isNotEmpty() } ?: throw AuthError(401, "no refresh token")
        val (status, text) = refreshGrant(refresh)
        // a rejected grant means the session is dead: log in again
        if (status !in 200..299) throw AuthError(if (status in setOf(400, 401, 403)) 401 else status, "refresh failed ($status)")
        save(JSONObject(text), refresh)
        return store.tokens(provider)!!
    }
}

fun Provider.account(store: Store): Account = when (this) {
    Provider.Claude -> ClaudeAccount(store)
    Provider.Codex -> CodexAccount(store)
}

/** "2h 13m", "4d 3h", "12m" until `at`. */
fun untilText(at: Long, from: Long = now()): String {
    val m = ((at - from) / 60_000).coerceAtLeast(0)
    return when {
        m >= 24 * 60 -> "${m / (24 * 60)}d ${m % (24 * 60) / 60}h"
        m >= 60 -> "${m / 60}h ${m % 60}m"
        else -> "${m}m"
    }
}

/** "just now", or "12m ago", "3h 5m ago" since `at`. */
fun ageText(at: Long, from: Long = now()) = if (from - at < 60_000) "just now" else "${untilText(from, at)} ago"
