package com.cristopher.localdrop.data.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps the LocalDrop process alive while its local HTTP receiver and NSD listener are active. */
class TransferService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "LocalDrop en red local", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Disponible en la red local"))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("LocalDrop")
        .setContentText(text)
        .setOngoing(true)
        .build()
    companion object {
        private const val CHANNEL_ID = "localdrop_transfer"
        private const val NOTIFICATION_ID = 43
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) { context.stopService(Intent(context, TransferService::class.java)) }
    }
}
