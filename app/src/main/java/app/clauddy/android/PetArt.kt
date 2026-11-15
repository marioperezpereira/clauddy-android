package app.clauddy.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.min

// The pet with its glance above its head, drawn as one bitmap: the same
// pixel-art bubble as the desktop's pet-only mode (native/src/components/
// glance.rs), with the session % in bold, the week's % beside it, and a bar
// for each. The widget and the app both show this picture.
object PetArt {
    private const val H = 28f
    private const val O = 3f
    private const val TAIL = 9f
    private const val PAD = 6f
    private const val WEEK_GAP = 4f
    // the most of the widget's height the bubble takes: readings in a row, stacked
    private const val ROW_SHARE = 0.5f
    private const val STACKED_SHARE = 0.62f
    private const val BLOCKS = 8

    /** The colours a picture is drawn in. */
    class Palette(
        val paper: Int,
        val paperShade: Int,
        val paperLight: Int,
        val ink: Int,
        val bar: Int,
        val urgent: Int,
        /** the pet recoloured to two tones, or null for its own colours */
        val petTint: ColorMatrixColorFilter?,
    ) {
        val inkMuted get() = withAlpha(ink, 0x99)
        val inkFaint get() = withAlpha(ink, 0x26)
    }

    private val SHADOW = Color.argb(0x59, 0, 0, 0)

    /** The desktop Clauddy's own colours. */
    val COLOUR = Palette(
        paper = Color.rgb(0xf4, 0xe8, 0xd4),
        paperShade = Color.rgb(0xe3, 0xd0, 0xb2),
        paperLight = Color.rgb(0xfd, 0xf6, 0xea),
        ink = Color.rgb(0x2a, 0x1a, 0x12),
        bar = Color.rgb(0xd9, 0x77, 0x57),
        urgent = Color.rgb(0xc0, 0x39, 0x2b),
        petTint = null,
    )

    /** The widget's look: the system's two tones, or Clauddy's own colours. */
    fun palette(context: Context, themed: Boolean) = if (themed) themed(context) else COLOUR

    /**
     * Two tones from the system theme, the ones themed icons use (Android 12+;
     * greys before), so the widget sits with a monochrome home screen.
     */
    fun themed(context: Context): Palette {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val (paper, ink) = if (Build.VERSION.SDK_INT >= 31) {
            if (dark) context.getColor(android.R.color.system_neutral1_800) to context.getColor(android.R.color.system_accent1_100)
            else context.getColor(android.R.color.system_accent1_100) to context.getColor(android.R.color.system_neutral2_700)
        } else {
            if (dark) Color.rgb(0x2e, 0x2e, 0x2e) to Color.rgb(0xe0, 0xe0, 0xe0) else Color.rgb(0xe8, 0xe8, 0xe8) to Color.rgb(0x3c, 0x3c, 0x3c)
        }
        return Palette(paper, blend(paper, ink, 0.14f), blend(paper, ink, -0.06f), ink, ink, ink, twoTone(ink, paper))
    }

    // dark pixels (outline, eyes) to `ink`, the body and lighter to `paper`
    private fun twoTone(ink: Int, paper: Int): ColorMatrixColorFilter {
        val lo = 0.1f
        val span = 0.45f
        fun row(i: Int, p: Int): FloatArray {
            val d = (p - i).toFloat()
            val k = d / span / 255f
            return floatArrayOf(k * 0.3f, k * 0.59f, k * 0.11f, 0f, i - lo / span * d)
        }
        val m = row(Color.red(ink), Color.red(paper)) + row(Color.green(ink), Color.green(paper)) +
            row(Color.blue(ink), Color.blue(paper)) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
        return ColorMatrixColorFilter(ColorMatrix(m))
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    // `t` of the way from `a` to `b` (negative goes the other way), clamped
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /**
     * What the pet is doing, and its loop: (frame, ms) on its sheet, played
     * as the desktop plays them (native/src/components/sprite.rs). `tickMs`
     * is the widget's flip step, the loop's shortest frame or close to it.
     */
    enum class Mood(val caption: String, val seq: List<Pair<Int, Int>>, val tickMs: Int) {
        Idle("Taking it easy", listOf(0 to 2400, 1 to 600, 0 to 2200, 2 to 150, 3 to 600), 150),
        Sleeping("Asleep", listOf(4 to 800, 5 to 800, 6 to 800, 7 to 800), 800),
        Fire("Running hot", listOf(8 to 120, 9 to 120, 10 to 120, 11 to 120), 120),
        Tired("Out of breath", listOf(12 to 450, 13 to 450, 14 to 450, 15 to 450), 450),
        // typing in turns, a look at the screen, a blink, stuck, happy, a cheer
        Working(
            "At work",
            listOf(
                0 to 200, 1 to 200, 0 to 200, 1 to 200, 3 to 200, 0 to 200, 1 to 200, 2 to 150, 0 to 200, 1 to 200, 4 to 700, 0 to 200,
                1 to 200, 5 to 500, 6 to 600, 5 to 300, 3 to 200, 7 to 200, 0 to 200, 1 to 200, 3 to 200, 0 to 200, 1 to 200, 3 to 200,
            ),
            100,
        ),
    }

    /**
     * Someone at work sets the mood, as on the desktop; else the hottest
     * session does. Nothing connected, it sleeps.
     */
    fun moodOf(readings: List<Reading>): Mood {
        if (readings.isEmpty()) return Mood.Sleeping
        if (readings.any { it.working }) return Mood.Working
        val hottest = readings.mapNotNull { it.usage?.session?.pct }.maxOrNull() ?: 0.0
        return when (level(hottest)) {
            100 -> Mood.Tired
            90 -> Mood.Fire
            else -> Mood.Idle
        }
    }

    // read from the main thread and from readings in the background
    private val sheets = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()

    private fun sheet(context: Context, res: Int) = sheets.getOrPut(res) {
        BitmapFactory.decodeResource(context.resources, res, BitmapFactory.Options().apply { inScaled = false })
    }

    /** A pet standing idle, its body filling a `px` square (the picker's thumbnails). */
    fun thumb(context: Context, skin: Skin, px: Int): Bitmap {
        val sheet = sheet(context, skin.sheet)
        val a = skin.standBox
        val fw = sheet.width / 4
        val fh = sheet.height / 4
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val side = px / maxOf(a[2] - a[0], a[3] - a[1])
        val left = px / 2f - (a[0] + a[2]) / 2 * side
        val top = px / 2f - (a[1] + a[3]) / 2 * side
        Canvas(bmp).drawBitmap(sheet, Rect(0, 0, fw, fh), RectF(left, top, left + side, top + side), Paint().apply { isFilterBitmap = true })
        return bmp
    }

    /**
     * The picture in two parts, stacked: the bubble on top, `bubble` as wide
     * as the space it was drawn for, and under it the pet's frames, cropped
     * to the pet and drawn at its sheet's own resolution, to show as large as
     * the `petZone` pixels left below the bubble allow. `seq` steps through
     * `frames` as (index, ms).
     */
    class Scene(val bubble: Bitmap, val frames: List<Bitmap>, val seq: List<Pair<Int, Int>>, val mood: Mood, val petZone: Int, val stacked: Boolean = false) {
        /** Both parts in one picture, the pet on its first frame. */
        fun flat(): Bitmap {
            val out = Bitmap.createBitmap(bubble.width, bubble.height + petZone, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(bubble, 0f, 0f, null)
            val f = frames[seq[0].first]
            val k = min(out.width.toFloat() / f.width, petZone.toFloat() / f.height)
            val l = (out.width - f.width * k) / 2
            c.drawBitmap(f, Rect(0, 0, f.width, f.height), RectF(l, bubble.height.toFloat(), l + f.width * k, bubble.height + f.height * k), Paint().apply { isFilterBitmap = true })
            return out
        }
    }

    /**
     * The picture for `fitW` x `fitH` pixels: one reading per connected
     * service, side by side as on the desktop or stacked when the widget is
     * squarer (no service connected, the
     * bubble asks to connect), at most half the height, and the pet in the
     * rest. The pet's frames are drawn at its sheet's own resolution.
     */
    fun scene(
        context: Context,
        readings: List<Reading>,
        fitW: Int,
        fitH: Int,
        reading: Boolean = false,
        pal: Palette = COLOUR,
        skin: Skin = Skins.ALL[0],
    ): Scene {
        // measured at a nominal scale; widths are kept in points
        var s = 4f
        val bold = textPaint(s, true)
        val regular = textPaint(s, false)
        val small = textPaint(s, false, 8.5f)
        val connected = readings.isNotEmpty()

        // a fixed width per reading (the widest one) so the widget never jumps
        val widest = bold.measureText("100%") / s + WEEK_GAP + regular.measureText("100%") / s
        val footW = small.measureText("↻ 23h 59m · 6d 23h") / s
        val rw = if (connected) maxOf(5 + 12 + 4 + widest + 5, footW + 10) else 5 + 12 + 4 + bold.measureText("Connect") / s + 5
        val rh = if (connected) H + 11 else H
        val n = maxOf(readings.size, 1)

        // the readings side by side or stacked, whichever lets the text be
        // larger in this widget's shape: a wide one keeps them in a row, a
        // square one stacks them (and gives the bubble a bit more height)
        val bubbleTop = PAD + O
        fun fit(cols: Int, rows: Int, share: Float) =
            min(fitW / (rw * cols + 2 * O + 2 * PAD), fitH * share / (bubbleTop + rh * rows + O + TAIL + 1))
        val stacked = n > 1 && fit(1, n, STACKED_SHARE) > fit(n, 1, ROW_SHARE)
        val cols = if (stacked) 1 else n
        val bw = rw * cols
        val bh = rh * (if (stacked) n else 1)
        val h = bubbleTop + bh + O + TAIL + 1
        s = fit(cols, if (stacked) n else 1, if (stacked) STACKED_SHARE else ROW_SHARE).coerceAtLeast(1f)
        val w = maxOf(fitW / s, bw + 2 * O + 2 * PAD)
        bold.textSize = 10.5f * s
        regular.textSize = 10.5f * s
        small.textSize = 8.5f * s
        val bmp = Bitmap.createBitmap(ceil(w * s).toInt(), ceil(h * s).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(s, s)
        val fill = Paint().apply { isAntiAlias = false }
        val cx = w / 2

        // the pet: each frame cropped to its art, with room for the Zz and
        // the sweat drop that reach past the body
        val mood = moodOf(readings)
        val working = mood == Mood.Working
        val art = when (mood) {
            Mood.Working -> skin.workBox
            Mood.Fire -> skin.fireBox
            else -> skin.standBox
        }
        val sheet = sheet(context, if (working) skin.workSheet else skin.sheet)
        val sheetCols = 4
        val sheetRows = if (working) 2 else 4
        val fw = sheet.width / sheetCols
        val fh = sheet.height / sheetRows
        val m = if (working) 0.02f else 0.12f
        val crop = RectF((art[0] - m).coerceAtLeast(0f) * fw, (art[1] - m).coerceAtLeast(0f) * fh, (art[2] + m).coerceAtMost(1f) * fw, (art[3] + m / 2).coerceAtMost(1f) * fh)
        val petZone = (fitH - bmp.height).coerceAtLeast(1)
        val k = minOf(1f, fitW / crop.width())
        val distinct = mood.seq.map { it.first }.distinct()
        val petPaint = Paint().apply { isFilterBitmap = true; colorFilter = pal.petTint }
        val frames = distinct.map { f ->
            Bitmap.createBitmap(ceil(crop.width() * k).toInt(), ceil(crop.height() * k).toInt(), Bitmap.Config.ARGB_8888).also {
                val pc = Canvas(it)
                val src = Rect(((f % sheetCols) * fw + crop.left).toInt(), ((f / sheetCols) * fh + crop.top).toInt(), ((f % sheetCols) * fw + crop.right).toInt(), ((f / sheetCols) * fh + crop.bottom).toInt())
                pc.drawBitmap(sheet, src, RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()), petPaint)
            }
        }
        val seq = mood.seq.map { (f, ms) -> distinct.indexOf(f) to ms }

        // a hard pixel shadow, the outline, then the paper in two tones
        val bubble = RectF(cx - bw / 2, bubbleTop, cx + bw / 2, bubbleTop + bh)
        pixelBox(c, fill, RectF(bubble).apply { offset(0f, O) }, SHADOW)
        pixelBox(c, fill, bubble, pal.ink)
        c.drawRect(bubble, fill.color(pal.paper))
        c.drawRect(bubble.left, bubble.bottom - 4, bubble.right, bubble.bottom, fill.color(pal.paperShade))
        c.drawRect(bubble.left, bubble.top, bubble.right, bubble.top + 2, fill.color(pal.paperLight))
        tail(c, fill, cx, bubble.bottom + O, pal)

        if (!connected) {
            drawLogo(context, c, R.drawable.claude_logo, bubble.left, bubble.top + 10, pal)
            text(c, s, bold.apply { color = pal.ink }, "Connect", bubble.left + 21, bubble.top + 10)
        } else {
            readings.forEachIndexed { i, r ->
                val x = bubble.left + (if (stacked) 0 else i) * rw
                val y = bubble.top + (if (stacked) i else 0) * rh
                // a faint pixel rule between readings
                if (i > 0 && stacked) c.drawRect(x + 5, y - 1, x + rw - 5, y + 1, fill.color(pal.inkFaint))
                else if (i > 0) c.drawRect(x - 1, bubble.top + 5, x + 1, bubble.bottom - 6, fill.color(pal.inkFaint))
                drawReading(context, c, s, fill, bold, regular, small, r, x, y, rw, reading, pal)
            }
        }
        return Scene(bmp, frames, seq, mood, petZone, stacked)
    }

    // one service: logo, session % in bold, the week's beside it, a bar for
    // each, and when they reset (or how old the reading is) underneath
    private fun drawReading(
        context: Context, c: Canvas, s: Float, fill: Paint, bold: Paint, regular: Paint, small: Paint,
        r: Reading, x: Float, top: Float, rw: Float, reading: Boolean, pal: Palette,
    ) {
        val u = r.usage
        val mid = top + 10
        drawLogo(context, c, r.provider.logo, x, mid, pal)
        val session = value(u?.session?.pct)
        val week = if (u != null && !u.weeklyOnly) value(u.week.pct) else null
        val stale = u?.stale == true
        val urgent = u?.session?.hot == true
        val vx = x + 5 + 12 + 4
        text(c, s, bold.apply { color = if (urgent) pal.urgent else pal.ink }, session, vx, mid)
        if (week != null) {
            regular.color = if (u!!.week.hot) pal.urgent else pal.inkMuted
            text(c, s, regular, week, vx + bold.measureText(session) / s + WEEK_GAP, mid)
        }
        val foot = when {
            reading -> "reading…"
            u == null -> null
            stale -> "read ${ageText(u.readAt)}"
            else -> listOfNotNull(u.session.resetsAt, u.week.resetsAt.takeIf { !u.weeklyOnly })
                .joinToString(" · ") { untilText(it) }.takeIf { it.isNotEmpty() }?.let { "↻ $it" }
        }
        if (foot != null) text(c, s, small.apply { color = pal.inkMuted }, foot, x + 5, top + 31)
        blocks(c, fill, x + 5, top + 17, rw - 10, 3f, u?.session?.pct, urgent, stale, pal)
        if (week != null) blocks(c, fill, x + 5, top + 21, rw - 10, 2f, u!!.week.pct, u.week.hot, stale, pal)
    }

    private fun drawLogo(context: Context, c: Canvas, res: Int, x: Float, mid: Float, pal: Palette) {
        val logo = context.getDrawable(res) ?: return
        c.save()
        c.translate(x + 5, mid - 6)
        c.scale(12f / 24, 12f / 24)
        logo.setBounds(0, 0, 24, 24)
        logo.setTint(pal.ink)
        logo.draw(c)
        c.restore()
    }

    // text in points, vertically centred on `mid`, drawn at full resolution
    private fun text(c: Canvas, s: Float, p: Paint, t: String, x: Float, mid: Float) {
        c.save()
        c.scale(1 / s, 1 / s)
        c.drawText(t, x * s, mid * s - (p.ascent() + p.descent()) / 2, p)
        c.restore()
    }

    private fun value(pct: Double?) = pct?.let(::pctText) ?: "—"

    private fun textPaint(s: Float, bold: Boolean, size: Float = 10.5f) = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textSize = size * s
    }

    private fun Paint.color(c: Int) = apply { color = c }

    // a pixel-art box: two bands of outline, so the corners step in
    private fun pixelBox(c: Canvas, p: Paint, r: RectF, ink: Int) {
        p.color = ink
        c.drawRect(r.left - O, r.top, r.right + O, r.bottom, p)
        c.drawRect(r.left, r.top - O, r.right, r.bottom + O, p)
    }

    // the tail under the bubble, centred on `x`, stepping in 3 px a row
    private fun tail(c: Canvas, p: Paint, x: Float, top: Float, pal: Palette) {
        floatArrayOf(18f, 12f, 6f).forEachIndexed { row, w -> c.drawRect(x - w / 2, top + row * O, x + w / 2, top + row * O + O, p.color(pal.ink)) }
        floatArrayOf(12f, 6f).forEachIndexed { row, w -> c.drawRect(x - w / 2, top - O + row * O, x + w / 2, top + row * O, p.color(pal.paperShade)) }
    }

    // a row of pixel blocks, the share of `pct` lit; faded when the reading is old
    private fun blocks(c: Canvas, p: Paint, x: Float, y: Float, w: Float, h: Float, pct: Double?, urgent: Boolean, stale: Boolean, pal: Palette) {
        val filled = pct?.let { ceil(it / 100 * BLOCKS).toInt() }?.coerceIn(0, BLOCKS) ?: 0
        val bw = (w - (BLOCKS - 1)) / BLOCKS
        for (i in 0 until BLOCKS) {
            p.color = if (i < filled) (if (urgent) pal.urgent else pal.bar) else pal.inkFaint
            if (stale && i < filled) p.alpha = 0x66
            c.drawRect(x + i * (bw + 1), y, x + i * (bw + 1) + bw, y + h, p)
        }
    }
}
