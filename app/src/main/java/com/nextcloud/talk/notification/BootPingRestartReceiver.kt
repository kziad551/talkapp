package com.nextcloud.talk.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootPingRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootPingRestartReceiver", "Boot completed – re-starting poller")
            context.startForegroundService(Intent(context, PingForegroundService::class.java))
        }
    }
}
