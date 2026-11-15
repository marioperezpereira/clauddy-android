package app.clauddy.android

import android.content.Context

/** The services the pet can follow. */
enum class Provider(val label: String, val logo: Int, val key: String) {
    Claude("Claude", R.drawable.claude_logo, ""),
    Codex("Codex", R.drawable.openai_logo, "codex_"),
}

/** One service's reading, as the widget shows it; `working` when its session % went up lately. */
data class Reading(val provider: Provider, val usage: Usage?, val working: Boolean = false)

// Everything the app keeps, in its private preferences: each service's token,
// a login in progress, the last readings and what the alerts already said.
// Claude's keys are unprefixed (as in the first version); Codex's start with
// "codex_".
class Store(context: Context) {
    private val p = context.getSharedPreferences("clauddy", Context.MODE_PRIVATE)

    fun tokens(of: Provider): Tokens? {
        val k = of.key
        return p.getString("${k}access", null)?.let { Tokens(it, p.getString("${k}refresh", null), p.getLong("${k}expires_at", 0)) }
    }

    // written before returning: a refresh token rotates on use, so one lost
    // to a killed process would sign the account out
    fun setTokens(of: Provider, t: Tokens?) {
        val k = of.key
        p.edit().apply {
            if (t == null) remove("${k}access").remove("${k}refresh").remove("${k}expires_at")
            else putString("${k}access", t.access).putString("${k}refresh", t.refresh).putLong("${k}expires_at", t.expiresAt)
        }.commit()
    }

    fun connected(of: Provider) = tokens(of) != null

    /** The connected services, Claude first; empty when none is. */
    fun connected(): List<Provider> = Provider.entries.filter { connected(it) }

    /** Claude's OAuth verifier and state while its login is open. */
    var pending: Pair<String, String>?
        get() = p.getString("verifier", null)?.let { v -> p.getString("state", null)?.let { v to it } }
        set(v) {
            p.edit().apply {
                if (v == null) remove("verifier").remove("state") else putString("verifier", v.first).putString("state", v.second)
            }.apply()
        }

    /**
     * Codex's device code while it waits for approval. Kept here, not in the
     * screen, so the login survives Android closing the app while the person
     * is on OpenAI's page. An expired one reads as none.
     */
    var codexPending: DeviceCode?
        get() {
            val id = p.getString("codex_pending_id", null) ?: return null
            val exp = p.getLong("codex_pending_expires", 0)
            if (now() > exp) return null
            return DeviceCode(p.getString("codex_pending_url", "")!!, p.getString("codex_pending_code", "")!!, id, p.getLong("codex_pending_interval", 5), exp)
        }
        set(v) {
            p.edit().apply {
                if (v == null) listOf("id", "url", "code", "interval", "expires").forEach { remove("codex_pending_$it") }
                else putString("codex_pending_id", v.deviceAuthId).putString("codex_pending_url", v.url).putString("codex_pending_code", v.userCode)
                    .putLong("codex_pending_interval", v.intervalS).putLong("codex_pending_expires", v.expiresAt)
            }.apply()
        }

    /** The ChatGPT workspace Codex's usage is read for. */
    var codexAccount: String?
        get() = p.getString("codex_account", null)
        set(v) = p.edit().putString("codex_account", v).apply()

    fun usage(of: Provider): Usage? {
        val k = of.key
        if (!p.contains("${k}session_pct")) return null
        fun reset(n: String) = p.getLong("$k$n", -1).takeIf { it >= 0 }
        return Usage(
            Window(p.getFloat("${k}session_pct", 0f).toDouble(), reset("session_reset")),
            Window(p.getFloat("${k}week_pct", 0f).toDouble(), reset("week_reset")),
            p.getBoolean("${k}weekly_only", false),
            p.getLong("${k}read_at", 0),
        )
    }

    fun setUsage(of: Provider, u: Usage?) {
        val k = of.key
        p.edit().apply {
            if (u == null) {
                listOf("session_pct", "session_reset", "week_pct", "week_reset", "weekly_only", "read_at").forEach { remove("$k$it") }
            } else {
                putFloat("${k}session_pct", u.session.pct.toFloat()).putLong("${k}session_reset", u.session.resetsAt ?: -1)
                putFloat("${k}week_pct", u.week.pct.toFloat()).putLong("${k}week_reset", u.week.resetsAt ?: -1)
                putBoolean("${k}weekly_only", u.weeklyOnly).putLong("${k}read_at", u.readAt)
            }
        }.apply()
    }

    /** What the widget shows: each connected service, its last reading, and whether it's at work. */
    fun readings(): List<Reading> = connected().map { Reading(it, usage(it), now() - risenAt(it) <= WORK_MS) }

    /** When the service's session % last went up (0: not seen). */
    fun risenAt(of: Provider) = p.getLong("${of.key}risen_at", 0)
    fun setRisenAt(of: Provider, v: Long) = p.edit().putLong("${of.key}risen_at", v).apply()

    companion object {
        /** A rise this recent still counts as working: a bit over one 15-minute reading. */
        const val WORK_MS = 20 * 60_000L
    }

    /** The last error worth showing, per service, cleared by a good reading. */
    fun error(of: Provider): String? = p.getString("${of.key}error", null)
    fun setError(of: Provider, v: String?) = p.edit().putString("${of.key}error", v).apply()

    /** The highest alert already sent for a service's current session window. */
    fun alerted(of: Provider) = p.getInt("${of.key}alerted", 0)
    fun setAlerted(of: Provider, v: Int) = p.edit().putInt("${of.key}alerted", v).apply()

    /** The widget in the system theme's two tones (the default) or in Clauddy's colours. */
    var themed: Boolean
        get() = p.getBoolean("themed", true)
        set(v) = p.edit().putBoolean("themed", v).apply()

    /** The pet picked in the app; the season's own takes over on its days. */
    var pet: String
        get() = p.getString("pet", Skins.DEFAULT) ?: Skins.DEFAULT
        set(v) = p.edit().putString("pet", v).apply()

    fun logout(of: Provider) {
        setTokens(of, null)
        setUsage(of, null)
        setError(of, null)
        setAlerted(of, 0)
        setRisenAt(of, 0)
        if (of == Provider.Claude) pending = null else {
            codexAccount = null
            codexPending = null
        }
    }
}
