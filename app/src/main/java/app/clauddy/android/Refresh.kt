package app.clauddy.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// One reading of every connected service: ask each account, keep the result,
// redraw the widget and say what changed. WorkManager runs it every 15
// minutes (Android's floor); the app and a tap on the widget run it at once.
object Refresh {
    // one reading at a time in the whole process: two at once would both
    // spend the same refresh token, and the loser would read as logged out
    private val lock = Mutex()

    suspend fun now(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock { readAll(context) }
        PetWidget.redraw(context)
    }

    /** Asks once whether the waiting Codex code was approved; the app polls with this. */
    suspend fun pollCodex(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock { pollCodex(Store(context)) }
    }

    private fun pollCodex(store: Store) {
        val dc = store.codexPending ?: return
        try {
            CodexAccount(store).poll(dc)
        } catch (e: Exception) {
            // a failed connection keeps the code waiting; an expired or refused one is gone
            if (store.codexPending == null) store.setError(Provider.Codex, "Codex: ${e.message}")
        }
    }

    private fun readAll(context: Context) {
        val store = Store(context)
        // a Codex code approved while the app was closed still connects
        pollCodex(store)
        for (p in store.connected()) {
            val before = store.usage(p)
            try {
                val u = p.account(store).fetchUsage()
                store.setUsage(p, u)
                if (rose(before, u)) store.setRisenAt(p, u.readAt)
                store.setError(p, null)
                alert(context, store, p, before, u)
            } catch (e: AuthError) {
                if (e.status == 401) store.logout(p)
                store.setError(p, if (e.status == 401) "${p.label}: signed out, connect again" else "${p.label}: ${e.message}")
            } catch (e: Exception) {
                // an answer in a shape we don't know: say so, keep the account
                store.setError(p, "${p.label}: unexpected answer (${e.javaClass.simpleName})")
            }
        }
    }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** A reading now, in WorkManager's hands rather than a broadcast's few seconds. */
    fun once(context: Context) {
        val req = OneTimeWorkRequestBuilder<Worker>().setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork("refresh-now", ExistingWorkPolicy.KEEP, req)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            now(applicationContext)
            return Result.success()
        }
    }

    /**
     * The phone can't see the computer's logs, so work is read off the
     * account: the session % went up since the reading before (within the
     * same window; a reset is a drop, not work).
     */
    fun rose(before: Usage?, now: Usage) = before != null && now.session.pct > before.session.pct

    /**
     * The session window started over since `before`: its reset time went
     * by, or (without one) the % fell a long way from a high reading.
     */
    fun newWindow(before: Usage, now: Usage): Boolean {
        val resetAt = before.session.resetsAt
        if (resetAt != null) return now.readAt >= resetAt
        return before.session.pct >= 50 && now.session.pct < before.session.pct - 25
    }

    // The desktop's headline moments: past the fire threshold, at the limit,
    // and a fresh window. Each fires once per session window and service.
    private fun alert(context: Context, store: Store, p: Provider, before: Usage?, u: Usage) {
        val pct = u.session.pct
        val name = "Your ${p.label} ${if (u.weeklyOnly) "week" else "session"}"
        val reset = before != null && newWindow(before, u)
        if (reset) {
            store.setAlerted(p, 0)
            if (level(before!!.session.pct) > 0) notify(context, "${p.label}: fresh window!", "$name is back to ${pctText(pct)}.")
        }
        val level = level(pct)
        if (level > store.alerted(p)) {
            store.setAlerted(p, level)
            val resets = u.session.resetsAt?.let { " It resets in ${untilText(it)}." } ?: ""
            if (level == 100) notify(context, "${p.label}: that's the limit", "$name is maxed out.$resets")
            else notify(context, "${p.label} at ${pctText(pct)}, getting warm", "$name is past 90%.$resets")
        }
    }

    private fun notify(context: Context, title: String, text: String) {
        // the permission exists from Android 13; before that the app's switch decides
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel("alerts", "Usage alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, "alerts")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(title.hashCode(), n)
    }
}
