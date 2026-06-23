package com.resukisu.resukisu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Receives the kernel/ksud "an app was denied root" broadcast and raises a
 * Magisk-style notification so the user can grant it. Off unless the user has
 * enabled "Notify on root requests" in Settings.
 */
class RootRequestReceiver : BroadcastReceiver() {

    companion object {
        const val PREFS = "su_notify"
        const val KEY_ENABLED = "enabled"
        private const val CHANNEL_ID = "root_requests"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getIntExtra("uid", -1)
        if (uid < 0) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return // off by default

        val pm = context.packageManager
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
        val label = pkg?.let {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull()
        } ?: "uid $uid"

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.su_notify_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )

        // Tap opens the manager so the user can grant root for the app.
        val launch = pm.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent()
        val pending = PendingIntent.getActivity(
            context,
            uid,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.su_notify_title))
            .setContentText(context.getString(R.string.su_notify_text, label))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.su_notify_text, label)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(uid, notification) // dedup per uid
        }
    }
}
