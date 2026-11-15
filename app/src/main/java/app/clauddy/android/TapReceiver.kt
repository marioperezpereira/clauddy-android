package app.clauddy.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// A tap on the widget's bubble. Not exported, so only the widget's own
// PendingIntent reaches it; the reading itself goes to WorkManager, which
// waits for a connection, so offline the bubble doesn't claim to be reading.
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        PetWidget.redraw(context, reading = online)
        Refresh.once(context)
    }
}
