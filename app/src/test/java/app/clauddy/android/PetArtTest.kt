package app.clauddy.android

import android.graphics.Bitmap
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// Renders the widget's picture for each state into build/pet-art/ so it can
// be looked at without a device.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PetArtTest {
    private fun shot(name: String, readings: List<Reading>, reading: Boolean = false, themed: Boolean = false, skin: Skin = Skins.ALL[0]) {
        val ctx = RuntimeEnvironment.getApplication()
        val bmp = PetArt.scene(ctx, readings, 900, 600, reading, PetArt.palette(ctx, themed), skin).flat()
        val dir = File("build/pet-art").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 0 && bmp.height > 0)
    }

    private val h = 3_600_000L

    private fun usage(session: Double, week: Double, weeklyOnly: Boolean = false, readAt: Long = now()) =
        Usage(Window(session, now() + 2 * h + 13 * 60_000 + 30_000), Window(week, now() + 99 * h + 30_000), weeklyOnly, readAt)

    private fun claude(u: Usage?) = listOf(Reading(Provider.Claude, u))
    private fun both(a: Usage?, b: Usage?) = listOf(Reading(Provider.Claude, a), Reading(Provider.Codex, b))

    @Test fun idle() = shot("idle", claude(usage(42.0, 68.0)))
    @Test fun urgentWeek() = shot("urgent-week", claude(usage(55.0, 86.0)))
    @Test fun fire() = shot("fire", claude(usage(93.0, 71.0)))
    @Test fun maxed() = shot("maxed", claude(usage(100.0, 100.0)))
    @Test fun weeklyOnly() = shot("weekly-only", claude(usage(64.0, 64.0, true)))
    @Test fun stale() = shot("stale", claude(usage(42.0, 68.0, readAt = now() - 65 * 60_000)))
    @Test fun reading() = shot("reading", claude(usage(42.0, 68.0)), reading = true)
    @Test fun signedOut() = shot("signed-out", emptyList())
    @Test fun withCodex() = shot("with-codex", both(usage(42.0, 68.0), usage(28.0, 64.0)))
    @Test fun withCodexFirstRead() = shot("with-codex-first-read", both(usage(42.0, 68.0), null))
    @Test fun codexOnlyHot() = shot("codex-only-hot", listOf(Reading(Provider.Codex, usage(93.0, 40.0))))
    @Test fun themedIdle() = shot("themed-idle", claude(usage(42.0, 68.0)), themed = true)
    @Test fun themedWithCodex() = shot("themed-with-codex", both(usage(5.0, 57.0), usage(28.0, 86.0)), themed = true)
    @Test fun themedSignedOut() = shot("themed-signed-out", emptyList(), themed = true)
    @Test @org.robolectric.annotation.Config(qualifiers = "night") fun themedNight() = shot("themed-night", both(usage(42.0, 68.0), usage(28.0, 64.0)), themed = true)

    @Test fun everyPet() = Skins.ALL.forEach { shot("pet-${it.id}", claude(usage(42.0, 68.0)), skin = it) }
    @Test fun everyPetOnFire() = Skins.ALL.forEach { shot("pet-${it.id}-fire", claude(usage(93.0, 68.0)), skin = it) }
    @Test fun everyPetThemed() = Skins.ALL.forEach { shot("pet-${it.id}-themed", claude(usage(42.0, 68.0)), themed = true, skin = it) }

    @Test fun working() = shot("working", listOf(Reading(Provider.Claude, usage(42.0, 68.0), working = true), Reading(Provider.Codex, usage(28.0, 64.0))))
    @Test fun everyPetWorking() = Skins.ALL.forEach { shot("pet-${it.id}-working", listOf(Reading(Provider.Claude, usage(42.0, 68.0), working = true)), skin = it) }

    @Test
    fun theWidgetLoopStaysLight() {
        val ctx = RuntimeEnvironment.getApplication()
        val scene = PetArt.scene(ctx, listOf(Reading(Provider.Claude, usage(42.0, 68.0), working = true)), 1000, 1000)
        assertTrue(scene.mood == PetArt.Mood.Working)
        assertTrue(scene.frames.size == 8)
        val bytes = scene.bubble.byteCount + scene.frames.sumOf { it.byteCount }
        // well under the budget a 1080 x 2400 screen gives RemoteViews (~15 MB)
        assertTrue("loop takes $bytes bytes", bytes < 8_000_000)
    }

    // in a wide widget two services widen the bubble; the pet still gets the height under it
    @Test
    fun thePetFillsTheSpaceUnderTheBubble() {
        val ctx = RuntimeEnvironment.getApplication()
        val readings = both(usage(9.0, 58.0), usage(32.0, 32.0, weeklyOnly = true)).let { listOf(it[0].copy(working = true), it[1]) }
        val scene = PetArt.scene(ctx, readings, 1000, 420)
        val f = scene.frames[0]
        val shown = f.height * minOf(1000f / f.width, scene.petZone.toFloat() / f.height)
        assertTrue("pet shown $shown px tall in 420", shown >= 0.5f * 420)
        // drawn at the sheet's own size, never stretched from a smaller copy
        assertTrue("frame ${f.width}x${f.height}", f.height >= 180)
    }

    // a square 2x2 widget stacks the readings so the text stays readable; a
    // wide one keeps them in a row
    @Test
    fun aSquareWidgetStacksTheReadings() {
        val ctx = RuntimeEnvironment.getApplication()
        val two = both(usage(10.0, 58.0), usage(33.0, 33.0, weeklyOnly = true))
        assertTrue(PetArt.scene(ctx, two, 480, 480).stacked)
        assertTrue(PetArt.scene(ctx, two + two.take(1), 480, 480).stacked)
        assertTrue(!PetArt.scene(ctx, two, 1000, 420).stacked)
        assertTrue(!PetArt.scene(ctx, two.take(1), 480, 480).stacked)
    }

    private fun square(name: String, readings: List<Reading>) {
        val ctx = RuntimeEnvironment.getApplication()
        val bmp = PetArt.scene(ctx, readings, 480, 480, pal = PetArt.themed(ctx)).flat()
        File(File("build/pet-art").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun squareOne() = square("square-1", listOf(Reading(Provider.Claude, usage(10.0, 58.0), working = true)))
    @Test fun squareTwo() = square("square-2", both(usage(10.0, 58.0), usage(33.0, 33.0, weeklyOnly = true)).let { listOf(it[0].copy(working = true), it[1]) })
    @Test fun squareThree() = square("square-3", both(usage(10.0, 58.0), usage(33.0, 33.0, weeklyOnly = true)).let { listOf(it[0].copy(working = true), it[1], it[0]) })

    // the layers of a working scene, for an animated preview
    @Test
    fun dumpWorkingLoop() {
        val ctx = RuntimeEnvironment.getApplication()
        val scene = PetArt.scene(ctx, both(usage(42.0, 68.0), usage(28.0, 64.0)).let { listOf(it[0].copy(working = true), it[1]) }, 900, 600)
        val dir = File("build/pet-art/loop").apply { mkdirs() }
        File(dir, "bubble.png").outputStream().use { scene.bubble.compress(Bitmap.CompressFormat.PNG, 100, it) }
        scene.frames.forEachIndexed { i, f -> File(dir, "frame$i.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        File(dir, "seq.txt").writeText(scene.seq.joinToString("\n") { "${it.first} ${it.second}" })
    }
}
