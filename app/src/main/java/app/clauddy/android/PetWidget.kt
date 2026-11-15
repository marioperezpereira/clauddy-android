package app.clauddy.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

// The home-screen pet: the picture from PetArt, redrawn after each reading.
// A tap on the bubble reads again (TapReceiver); a tap on the pet opens the app.
class PetWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        redraw(context)
        Refresh.schedule(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return redraw(context)
        super.onReceive(context, intent)
    }

    // resized on the home screen: draw again at the new size
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    companion object {
        /**
         * The widget's size in dp as it shows now. Android 12+ lists the real
         * sizes; before that the options hold portrait as min width x max
         * height and landscape as max width x min height.
         */
        fun sizeDp(context: Context, o: Bundle): Pair<Float, Float> {
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!sizes.isNullOrEmpty()) {
                    val s = if (portrait) sizes.minBy { it.width } else sizes.maxBy { it.width }
                    return s.width to s.height
                }
            }
            fun get(key: String) = o.getInt(key).takeIf { it > 0 }?.toFloat() ?: 180f
            return if (portrait) get(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) to get(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            else get(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) to get(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        }

        fun redraw(context: Context, reading: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PetWidget::class.java))
            if (ids.isEmpty()) return
            val store = Store(context)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val refresh = PendingIntent.getBroadcast(context, 0, Intent(context, TapReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
            val signedIn = store.connected().isNotEmpty()
            val density = context.resources.displayMetrics.density
            for (id in ids) {
                // drawn at the widget's own size in pixels, so the launcher
                // never stretches it
                val (wDp, hDp) = sizeDp(context, manager.getAppWidgetOptions(id))
                // kept within the RemoteViews bitmap budget, scaled as one so
                // the shape stays the widget's
                val k = minOf(density, 1000f / maxOf(wDp, hDp))
                val wPx = (wDp * k).toInt()
                val hPx = (hDp * k).toInt()
                // the pet's frames come cropped and at the sheet's own size,
                // so the loop stays well inside the RemoteViews bitmap budget
                val scene = PetArt.scene(context, store.readings(), wPx, hPx, reading, PetArt.palette(context, store.themed), Skins.active(store.pet))
                val views = RemoteViews(context.packageName, R.layout.widget).apply {
                    setImageViewBitmap(R.id.pet, scene.bubble)
                    removeAllViews(R.id.flipper)
                    // a ViewFlipper flips at one pace: a longer frame repeats
                    val tick = scene.mood.tickMs
                    for ((i, ms) in scene.seq) {
                        repeat(maxOf(1, Math.round(ms.toFloat() / tick))) {
                            addView(R.id.flipper, RemoteViews(context.packageName, R.layout.widget_frame).apply { setImageViewBitmap(R.id.frame, scene.frames[i]) })
                        }
                    }
                    setInt(R.id.flipper, "setFlipInterval", tick)
                    // signed out there is nothing to refresh: the whole widget opens the app
                    setOnClickPendingIntent(R.id.pet, if (signedIn) refresh else open)
                    setOnClickPendingIntent(R.id.flipper, open)
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}
