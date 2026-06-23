package com.resukisu.resukisu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the root-request monitor at boot if "Notify on root requests" is on. */
class RootRequestBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val enabled = context.getSharedPreferences(RootRequestReceiver.PREFS, Context.MODE_PRIVATE)
            .getBoolean(RootRequestReceiver.KEY_ENABLED, false)
        if (enabled) RootRequestMonitorService.start(context)
    }
}
