package com.resukisu.resukisu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.resukisu.resukisu.ui.util.SentinelHistEntry
import com.resukisu.resukisu.ui.util.getSentinelCloaked
import com.resukisu.resukisu.ui.util.getSentinelHistory
import com.resukisu.resukisu.ui.util.stopStraySulogd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches Sentinel's su-probe history (which the kernel populates whenever
 * Sentinel is on - no SU Log / sulogd involved) and raises a root-request
 * notification for each non-allowlisted, non-cloaked app that probes for su.
 * Runs only while "Notify on root requests" is enabled.
 */
class RootRequestMonitorService : Service() {

    companion object {
        private const val FG_CHANNEL = "root_monitor"
        private const val FG_ID = 0x2153 // "SU"
        private const val SU_KIND_BIT = 1 // KSU_SENTINEL_KIND_SU == 1 -> bit 0
        private const val POLL_MS = 5000L
        private const val NOTIFIED_PREFS = "su_notified"

        fun start(context: Context) {
            val i = Intent(context, RootRequestMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RootRequestMonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_ID, foregroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FG_ID, foregroundNotification())
        }
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        val seen = getSharedPreferences(NOTIFIED_PREFS, Context.MODE_PRIVATE)
        // We rely on Sentinel, never SU Log - make sure no stray sulogd lingers.
        runCatching { stopStraySulogd() }
        while (scope.isActive) {
            // Stop if the user turned the feature off.
            val enabled = getSharedPreferences(RootRequestReceiver.PREFS, Context.MODE_PRIVATE)
                .getBoolean(RootRequestReceiver.KEY_ENABLED, false)
            if (!enabled) {
                stopSelf()
                return
            }

            runCatching {
                val cloaked = getSentinelCloaked().toHashSet()
                val history: List<SentinelHistEntry> = getSentinelHistory()
                for (e in history) {
                    if ((e.kinds and SU_KIND_BIT) == 0) continue // only su-path probes
                    if (e.uid < 10000) continue                 // user apps only
                    val key = e.uid.toString()
                    when {
                        // Cloaked apps must not raise requests; clearing the mark
                        // means uncloaking restores notifications.
                        e.uid in cloaked -> seen.edit().remove(key).apply()
                        isUidGranted(e.uid) -> Unit // already has root
                        !seen.contains(key) -> {
                            RootRequestReceiver.postNotification(this, e.uid)
                            seen.edit().putBoolean(key, true).apply()
                        }
                    }
                }
            }
            delay(POLL_MS)
        }
    }

    private fun isUidGranted(uid: Int): Boolean = runCatching {
        val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return false
        Natives.getAppProfile(pkg, uid).allowSu
    }.getOrDefault(false)

    private fun foregroundNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(FG_CHANNEL, getString(R.string.su_monitor_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val builder = NotificationCompat.Builder(this, FG_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.su_monitor_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED).build()
        } else {
            builder.build()
        }
    }
}
