package app.clauddy.android

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.SizeF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// The widget must be drawn for the size it shows at in portrait, not the
// landscape width: drawn too wide, the readings stay in a row and shrink.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "port")
class WidgetSizeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun theListedPortraitSizeWins() {
        val o = Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(180f, 200f), SizeF(260f, 150f)))
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 260)
        }
        assertEquals(180f to 200f, PetWidget.sizeDp(ctx, o))
    }

    @Test
    fun withoutAListPortraitIsMinWidthByMaxHeight() {
        val o = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 260)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200)
        }
        assertEquals(180f to 200f, PetWidget.sizeDp(ctx, o))
    }
}
