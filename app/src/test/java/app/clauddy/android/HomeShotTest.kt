package app.clauddy.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// Renders the app's screen whole into build/pet-art/home-*.png, to look at
// without a device.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeShotTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        try { WorkManager.initialize(ctx, Configuration.Builder().build()) } catch (_: IllegalStateException) {}
    }

    private fun shot(name: String) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val content = (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)
        val w = ctx.resources.displayMetrics.widthPixels
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, w, content.measuredHeight)
        // the bars size themselves after a layout pass
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, w, content.measuredHeight)
        val bmp = Bitmap.createBitmap(w, content.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(0xf6, 0xef, 0xe3))
        content.draw(c)
        File(File("build/pet-art").apply { mkdirs() }, "home-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun signedOut() = shot("signed-out")

    @Test
    fun connectingClaude() {
        Store(ctx).pending = "verifier" to "state"
        shot("connecting")
    }

    @Test
    fun bothConnected() {
        val s = Store(ctx)
        s.setTokens(Provider.Claude, Tokens("a", "r", now() + 3_600_000))
        s.setTokens(Provider.Codex, Tokens("a", "r", now() + 3_600_000))
        s.setUsage(Provider.Claude, Usage(Window(10.0, now() + 82 * 60_000L), Window(58.0, now() + 85 * 3_600_000L), false, now() - 3 * 60_000))
        s.setUsage(Provider.Codex, Usage(Window(83.0, now() + 80 * 3_600_000L), Window(83.0, now() + 80 * 3_600_000L), true, now() - 3 * 60_000))
        s.setRisenAt(Provider.Claude, now())
        s.pet = "astronaut"
        shot("both")
    }
}
