package com.kavachvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class KavachForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "kavachvoice_protection"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "KavachVoice Protection", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("KavachVoice Active")
            .setContentText("Protecting against voice cloning fraud")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        sendBroadcast(Intent("com.kavachvoice.RESTART_SERVICE"))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
