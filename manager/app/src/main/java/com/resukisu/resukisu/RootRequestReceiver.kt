package com.resukisu.resukisu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.resukisu.resukisu.ui.util.sentinelCloak
import kotlin.concurrent.thread

/**
 * Receives the kernel/ksud "a non-allowlisted app probed for su" broadcast and
 * raises a notification with three actions:
 *   Grant  -> add the app to the superuser allowlist
 *   Cloak  -> add the app to the Sentinel cloak list
 *   Ignore -> dismiss
 * Off unless "Notify on root requests" is enabled in Settings.
 */
class RootRequestReceiver : BroadcastReceiver() {

    companion object {
        const val PREFS = "su_notify"
        const val KEY_ENABLED = "enabled"
        private const val CHANNEL_ID = "root_requests"
        private const val EXTRA_ACTION = "su_action"
        private const val EXTRA_UID = "uid"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getIntExtra(EXTRA_UID, -1)
        if (uid < 0) return

        when (intent.getStringExtra(EXTRA_ACTION)) {
            "grant" -> {
                cancel(context, uid)
                runRoot(context, uid, granted = true)
            }
            "cloak" -> {
                cancel(context, uid)
                runRoot(context, uid, granted = false)
            }
            "ignore" -> cancel(context, uid)
            else -> postRequest(context, uid)
        }
    }

    // ---- the incoming request from ksud ----
    private fun postRequest(context: Context, uid: Int) {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)) return

        val pm = context.packageManager
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
        val label = pkg?.let {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull()
        } ?: "uid $uid"

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.su_notify_channel), NotificationManager.IMPORTANCE_HIGH)
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.su_notify_title))
            .setContentText(context.getString(R.string.su_notify_text, label))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.su_notify_text, label)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.su_notify_grant), action(context, uid, "grant"))
            .addAction(0, context.getString(R.string.su_notify_cloak), action(context, uid, "cloak"))
            .addAction(0, context.getString(R.string.su_notify_ignore), action(context, uid, "ignore"))
            .build()

        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(uid, notification) // dedup per uid
        }
    }

    private fun action(context: Context, uid: Int, what: String): PendingIntent {
        val intent = Intent(context, RootRequestReceiver::class.java)
            .putExtra(EXTRA_ACTION, what)
            .putExtra(EXTRA_UID, uid)
        // Unique request code per uid+action so PendingIntents don't collide.
        val code = uid * 8 + when (what) { "grant" -> 1; "cloak" -> 2; else -> 3 }
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancel(context: Context, uid: Int) {
        NotificationManagerCompat.from(context).cancel(uid)
    }

    // Grant (allowlist) or Cloak (Sentinel) need root work; run off the main thread.
    private fun runRoot(context: Context, uid: Int, granted: Boolean) {
        val pm = context.packageManager
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
        val label = pkg?.let {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull()
        } ?: "uid $uid"
        val pending = goAsync()
        thread {
            try {
                val ok = if (granted) {
                    pkg != null && Natives.setAppProfile(
                        Natives.getAppProfile(pkg, uid).copy(allowSu = true, currentUid = uid)
                    )
                } else {
                    sentinelCloak(uid)
                }
                android.os.Handler(context.mainLooper).post {
                    val msg = when {
                        granted && ok -> context.getString(R.string.su_notify_granted_toast, label)
                        !granted && ok -> context.getString(R.string.su_notify_cloaked_toast, label)
                        else -> context.getString(R.string.su_notify_failed_toast, label)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
