package app.clauddy.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The app: the pet and what it's up to, each account's limits in large
// figures, connecting the accounts, picking the pet and the widget's look.
// Claude uses the desktop's copy-the-code login; Codex shows a one-time code
// to approve on auth.openai.com.
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: Store
    private var dp = 1f

    private lateinit var petAnim: ImageView
    private lateinit var mood: TextView
    private lateinit var message: TextView
    private lateinit var usage: LinearLayout
    private lateinit var claudeRow: Row
    private lateinit var codexRow: Row
    private lateinit var claudePanel: LinearLayout
    private lateinit var codexPanel: LinearLayout
    private lateinit var code: EditText
    private lateinit var codexCode: TextView
    private lateinit var codexOpen: TextView
    private lateinit var preview: ImageView
    private lateinit var styleSystem: TextView
    private lateinit var styleClauddy: TextView
    private lateinit var seasonNote: TextView
    private val petCells = mutableListOf<Pair<Skin, View>>()
    private var codexWatch: Job? = null

    /** An account's line: logo, name, and either its state or a way in. */
    private class Row(val view: LinearLayout, val state: TextView, val action: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        dp = resources.displayMetrics.density

        // top: the name and a quiet way to read again
        val top = row(
            text("Clauddy", 22f, INK, SERIF),
            View(this).weighted(),
            button("Refresh") { refresh() },
        )

        // the pet on its shelf, and a word on what it's doing
        petAnim = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val stage = FrameLayout(this).apply {
            addView(petAnim, FrameLayout.LayoutParams(-1, dp(120)).apply { gravity = Gravity.BOTTOM })
        }
        mood = text("", 17f, INK, SERIF_ITALIC).apply { gravity = Gravity.CENTER }
        message = text("", 13f, ACCENT, SANS).apply { gravity = Gravity.CENTER; visibility = View.GONE }

        usage = column()

        // accounts, each with its connect steps folded under it
        claudeRow = accountRow(Provider.Claude)
        codexRow = accountRow(Provider.Codex)
        code = EditText(this).apply {
            hint = "Paste the code"
            setHintTextColor(INK_FAINT)
            setTextColor(INK)
            textSize = 15f
            typeface = MONO
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = outline(RULE, 10f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        claudePanel = column(
            text("Log in on the page that opened, copy the code it shows, and paste it here.", 14f, INK_MUTED, SANS).spaced(),
            code.margin(top = 14),
            row(
                button("Paste") {
                    getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.let { code.setText(it) }
                },
                View(this).weighted(),
                button("Save", primary = true) { saveClaudeCode() },
            ).margin(top = 10),
            text("A token from claude setup-token works too.", 12f, INK_FAINT, SANS).margin(top = 6),
        )

        codexCode = text("", 30f, INK, MONO).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setTextIsSelectable(true)
            background = outline(RULE, 14f)
            setPadding(0, dp(18), 0, dp(18))
        }
        codexOpen = button("Copy the code and open OpenAI", primary = true) {
            val dc = store.codexPending ?: return@button
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Codex code", dc.userCode))
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.url)))
        }
        codexPanel = column(
            codexCode,
            codexOpen.margin(top = 12),
            text("Waiting for your approval. The code lasts 15 minutes.", 12f, INK_FAINT, SANS).apply { gravity = Gravity.CENTER }.margin(top = 8),
        )

        // the pets, the chosen one ringed
        seasonNote = text("", 12f, INK_FAINT, SANS).spaced()

        // the widget as it looks now, and its two styles
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val previewPanel = FrameLayout(this).apply {
            background = fill(PANEL, 18f)
            setPadding(0, dp(20), 0, dp(20))
            addView(preview, FrameLayout.LayoutParams(dp(172), -2, Gravity.CENTER))
        }
        styleSystem = segment("System colours") { setThemed(true) }
        styleClauddy = segment("Clauddy colours") { setThemed(false) }
        val segments = row(styleSystem.weighted(), styleClauddy.weighted()).apply {
            background = outline(RULE, 12f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        val root = column(
            top,
            stage.margin(top = 8),
            rule(),
            mood.margin(top = 14),
            message.margin(top = 6),
            usage.margin(top = 8),
            label("Accounts"),
            claudeRow.view, claudePanel.margin(top = 4, bottom = 12), rule(),
            codexRow.view, codexPanel.margin(top = 4, bottom = 12), rule(),
            label("Pet"),
            petGrid(),
            seasonNote.margin(top = 10),
            label("Widget"),
            previewPanel,
            segments.margin(top = 12),
            text(
                "Add it from your home screen's widget list. Tap the bubble to read now, the pet to open the app. " +
                    "It reads every 15 minutes and lets you know at 90%, at the limit, and when a hot window resets.",
                13f, INK_MUTED, SANS,
            ).spaced().margin(top = 14),
        ).apply { setPadding(dp(24), dp(20), dp(24), dp(40)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(PAPER); fitsSystemWindows = true; addView(root) })
        // after the content: the bars' controller needs the window's views
        lightBars()

        // asked once on first open, not again on every rotation
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Refresh.schedule(this)
        show()
    }

    override fun onResume() {
        super.onResume()
        // picks a Codex login back up after Android closed the app on OpenAI's page
        watchCodex()
        if (store.connected().isNotEmpty()) refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- what's on screen

    private fun show() {
        val readings = store.readings()
        val skin = Skins.active(store.pet)
        // the app keeps Clauddy's own colours; the system's tones are the widget's choice
        val scene = PetArt.scene(this, readings, resources.displayMetrics.widthPixels, dp(260), pal = PetArt.COLOUR, skin = skin)
        petAnim.setImageDrawable(AnimationDrawable().apply {
            for ((i, ms) in scene.seq) addFrame(BitmapDrawable(resources, scene.frames[i]), ms)
            isOneShot = false
            start()
        })
        mood.text = scene.mood.caption

        showUsage(readings)

        // a login's steps show while it waits in the store, even after the app
        // closed on the way; while open they hold the one filled button
        val codexCodeWaiting = store.codexPending
        codexCode.text = codexCodeWaiting?.userCode
        for (p in Provider.entries) {
            val r = if (p == Provider.Claude) claudeRow else codexRow
            val on = store.connected(p)
            val open = !on && (if (p == Provider.Claude) store.pending != null else codexCodeWaiting != null)
            (if (p == Provider.Claude) claudePanel else codexPanel).visibility = if (open) View.VISIBLE else View.GONE
            r.state.visibility = if (on) View.VISIBLE else View.GONE
            r.action.text = when {
                on -> "Disconnect"
                open && p == Provider.Claude -> "Open again"
                open -> "New code"
                else -> "Connect"
            }
            styleAction(r.action, primary = !on && !open)
        }

        for ((s, cell) in petCells) cell.background = if (s.id == store.pet) outline(INK, 14f, 1.5f) else null
        seasonNote.text = if (skin.id != store.pet) "Today belongs to the season's pet, ${skin.name}. Yours is back tomorrow."
        else "On Halloween and at Christmas the season's pet takes over for the day."

        preview.setImageBitmap(PetArt.scene(this, readings, dp(180), dp(190), pal = PetArt.palette(this, store.themed), skin = skin).flat())
        styleSegment(styleSystem, store.themed)
        styleSegment(styleClauddy, !store.themed)
        say(Provider.entries.mapNotNull { store.error(it) }.joinToString("\n").ifEmpty { null })
    }

    // each account: its name and when it was read, then the figures
    private fun showUsage(readings: List<Reading>) {
        usage.removeAllViews()
        if (readings.isEmpty()) {
            usage.addView(text("Connect an account below to see how much of your limits you've used.", 15f, INK_MUTED, SERIF).apply { gravity = Gravity.CENTER }.spaced().margin(top = 18))
            return
        }
        readings.forEachIndexed { i, r ->
            if (i > 0) usage.addView(rule().margin(top = 20))
            val u = r.usage
            val age = u?.let { ageText(it.readAt).replaceFirstChar(Char::uppercase) } ?: "Reading…"
            usage.addView(row(logo(r.provider), text(r.provider.label, 15f, INK, SANS_MEDIUM).margin(left = 10), View(this).weighted(), text(age, 12f, INK_FAINT, SANS)).margin(top = 22))
            if (u == null) return@forEachIndexed
            val stale = u.stale
            val figures = row()
            figures.addView(figure(if (u.weeklyOnly) "This week" else "This session", u.session, stale).weighted())
            if (!u.weeklyOnly) figures.addView(figure("This week", u.week, stale).weighted().margin(left = 24))
            else figures.addView(View(this).weighted().margin(left = 24))
            usage.addView(figures.margin(top = 14))
        }
    }

    // a limit: the figure large, a hairline bar, and when it resets
    private fun figure(name: String, w: Window, stale: Boolean): LinearLayout {
        val pct = pctText(w.pct)
        // the % sign at half size
        val big = SpannableString(pct).apply { setSpan(RelativeSizeSpan(0.5f), pct.length - 1, pct.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return column(
            text(name, 12f, INK_MUTED, SANS),
            text("", 42f, if (stale) INK_FAINT else INK, SERIF).apply { setText(big); includeFontPadding = false }.margin(top = 4),
            bar(w.pct, if (w.hot) ACCENT else INK, stale).margin(top = 10),
            text(w.resetsAt?.let { "Resets in ${untilText(it)}" } ?: " ", 12f, INK_FAINT, SANS).margin(top = 8),
        )
    }

    private fun say(t: String?) {
        message.text = t ?: ""
        message.visibility = if (t == null) View.GONE else View.VISIBLE
    }

    // ---- accounts

    private fun accountRow(p: Provider): Row {
        val state = text("Connected", 13f, INK_FAINT, SANS)
        val action = TextView(this).apply { gravity = Gravity.CENTER }
        action.setOnClickListener {
            when {
                store.connected(p) -> disconnect(p)
                p == Provider.Claude -> {
                    val url = ClaudeAccount(store).begin()
                    show()
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                else -> startCodexLogin()
            }
        }
        val v = row(logo(p), text(p.label, 16f, INK, SANS_MEDIUM).margin(left = 12), View(this).weighted(), state, action.margin(left = 12))
        v.minimumHeight = dp(64)
        return Row(v, state, action)
    }

    private fun disconnect(p: Provider) {
        if (p == Provider.Codex) codexWatch?.cancel()
        store.logout(p)
        changed()
    }

    private fun saveClaudeCode() {
        val pasted = code.text.toString()
        if (pasted.isBlank()) return
        say("Connecting Claude…")
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    ClaudeAccount(store).complete(pasted); null
                } catch (e: AuthError) {
                    e.message
                } catch (e: Exception) {
                    "Login failed: unexpected answer (${e.javaClass.simpleName})"
                }
            }
            if (error != null) return@launch say(error)
            code.setText("")
            refresh()
        }
    }

    private fun startCodexLogin() {
        codexWatch?.cancel()
        say("Asking OpenAI for a code…")
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    CodexAccount(store).requestCode(); null
                } catch (e: Exception) {
                    "Codex: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            show()
            if (error != null) say(error) else watchCodex()
        }
    }

    // While a Codex code waits, ask about it at OpenAI's pace, through
    // Refresh so a background reading never polls the same code at once.
    private fun watchCodex() {
        if (codexWatch?.isActive == true) return
        codexWatch = scope.launch {
            while (!store.connected(Provider.Codex)) {
                val dc = store.codexPending ?: break
                Refresh.pollCodex(this@MainActivity)
                if (store.codexPending != null) delay(dc.intervalS.coerceAtLeast(1) * 1000)
            }
            if (store.connected(Provider.Codex)) refresh() else show()
        }
    }

    private fun refresh() {
        scope.launch {
            Refresh.now(this@MainActivity)
            show()
        }
    }

    private fun setThemed(v: Boolean) {
        store.themed = v
        changed()
    }

    // a setting changed: the widget and the screen both show it
    private fun changed() {
        PetWidget.redraw(this)
        show()
    }

    // ---- pieces

    // the pets in rows of four, each its idle pose and name; a tap picks it
    private fun petGrid(): LinearLayout {
        val grid = column()
        val px = dp(52)
        Skins.ALL.chunked(4).forEachIndexed { n, line ->
            val r = row()
            line.forEach { skin ->
                val cell = column(
                    ImageView(this).apply { setImageBitmap(PetArt.thumb(context, skin, px)) }.also { it.layoutParams = LinearLayout.LayoutParams(px, px) },
                    text(skin.name, 12f, INK_MUTED, SANS).apply { gravity = Gravity.CENTER }.margin(top = 6),
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    foreground = ripple(14f)
                    setOnClickListener {
                        store.pet = skin.id
                        changed()
                    }
                }
                r.addView(cell.weighted().margin(left = 3, right = 3))
                petCells += skin to cell
            }
            repeat(4 - line.size) { r.addView(View(this).weighted().margin(left = 3, right = 3)) }
            grid.addView(r.margin(top = if (n > 0) 6 else 0))
        }
        return grid
    }

    private fun lightBars() {
        @Suppress("DEPRECATION")
        window.statusBarColor = PAPER
        @Suppress("DEPRECATION")
        window.navigationBarColor = PAPER
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun dp(v: Int) = (v * dp).toInt()

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { it.params(); addView(it) }
    }

    // in a row, what isn't weighted keeps its own width
    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach {
            it.params().apply { if (width == -1 && weight == 0f) width = -2 }
            addView(it)
        }
    }

    // a part spans the column unless it says otherwise
    private fun <V : View> V.params(): LinearLayout.LayoutParams =
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)).also { layoutParams = it }

    private fun <V : View> V.margin(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) = apply {
        params().setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }

    private fun <V : View> V.weighted() = apply { params().apply { width = 0; weight = 1f } }

    private fun TextView.spaced() = apply { setLineSpacing(0f, 1.35f) }

    private fun text(t: String, size: Float, color: Int, face: Typeface) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = face
    }

    // a small tracked capital heading over each part, with room above
    private fun label(t: String) = text(t.uppercase(), 11f, INK_FAINT, SANS_MEDIUM).apply { letterSpacing = 0.14f }.margin(top = 44, bottom = 6)

    private fun rule() = View(this).apply {
        setBackgroundColor(RULE)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, (0.75f * dp).toInt()))
    }

    private fun logo(p: Provider) = ImageView(this).apply {
        setImageResource(p.logo)
        imageTintList = ColorStateList.valueOf(INK)
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
    }

    // the filled one is the step to take now; the plain one an aside
    private fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = t
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        styleAction(this, primary)
    }

    private fun styleAction(v: TextView, primary: Boolean) {
        v.textSize = 14f
        v.typeface = SANS_MEDIUM
        v.minHeight = dp(40)
        if (primary) {
            v.setTextColor(PAPER)
            v.background = RippleDrawable(ColorStateList.valueOf(Color.argb(0x33, 0xff, 0xff, 0xff)), fill(INK, 20f), null)
            v.setPadding(dp(18), dp(10), dp(18), dp(10))
        } else {
            v.setTextColor(ACCENT)
            v.background = ripple(20f)
            v.setPadding(dp(10), dp(10), dp(10), dp(10))
        }
    }

    private fun segment(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        typeface = SANS_MEDIUM
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    private fun styleSegment(v: TextView, on: Boolean) {
        v.setTextColor(if (on) PAPER else INK_MUTED)
        v.background = if (on) fill(INK, 9f) else ripple(9f)
    }

    // a hairline bar, its fill clipped to the share used; a stale reading shows faded
    private fun bar(pct: Double, color: Int, stale: Boolean) = View(this).apply {
        val used = ClipDrawable(fill(if (stale) INK_FAINT else color, 2f), Gravity.START, ClipDrawable.HORIZONTAL)
        used.level = (pct.coerceIn(0.0, 100.0) * 100).toInt()
        background = LayerDrawable(arrayOf(fill(RULE, 2f), used))
        layoutParams = LinearLayout.LayoutParams(-1, dp(3))
    }

    private fun fill(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius * dp }

    private fun outline(color: Int, radius: Float, width: Float = 1f) = GradientDrawable().apply {
        setStroke(maxOf(1, (width * dp).toInt()), color)
        cornerRadius = radius * dp
    }

    private fun ripple(radius: Float): Drawable = RippleDrawable(ColorStateList.valueOf(Color.argb(0x1f, 0x24, 0x17, 0x12)), null, fill(Color.BLACK, radius))

    companion object {
        // warm paper and ink, Clauddy's own colours, with one accent for
        // what needs a look
        private val PAPER = Color.rgb(0xf6, 0xef, 0xe3)
        private val PANEL = Color.rgb(0xec, 0xe2, 0xd2)
        private val INK = Color.rgb(0x24, 0x17, 0x12)
        private val INK_MUTED = Color.argb(0xa8, 0x24, 0x17, 0x12)
        private val INK_FAINT = Color.argb(0x73, 0x24, 0x17, 0x12)
        private val RULE = Color.argb(0x24, 0x24, 0x17, 0x12)
        private val ACCENT = Color.rgb(0xb8, 0x55, 0x36)

        private val SERIF: Typeface = Typeface.create("serif", Typeface.NORMAL)
        private val SERIF_ITALIC: Typeface = Typeface.create("serif", Typeface.ITALIC)
        private val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private val SANS_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val MONO: Typeface = Typeface.MONOSPACE
    }
}
