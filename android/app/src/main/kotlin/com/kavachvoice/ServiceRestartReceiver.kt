package com.kavachvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.kavachvoice.RESTART_SERVICE") {
            context.startForegroundService(Intent(context, KavachForegroundService::class.java))
        }
    }
}
