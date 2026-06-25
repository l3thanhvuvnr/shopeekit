package com.personal.shopeekit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.personal.shopeekit.R
import com.personal.shopeekit.ui.MainActivity

/**
 * ForegroundService that keeps the CheckoutSniper engine alive during countdown.
 * MIUI kills background processes aggressively — a foreground service is mandatory.
 *
 * Bind to this service from activities to keep the process resident.
 */
class ShopeeKitForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "shopeekit_sniper"
        const val CHANNEL_NAME = "Voucher Sniper"

        const val ACTION_START = "com.personal.shopeekit.START"
        const val ACTION_STOP = "com.personal.shopeekit.STOP"
        const val ACTION_UPDATE_NOTIFICATION = "com.personal.shopeekit.UPDATE_NOTIF"

        const val EXTRA_STATUS_TEXT = "status_text"

        fun startIntent(context: Context) =
            Intent(context, ShopeeKitForegroundService::class.java).apply {
                action = ACTION_START
            }

        fun stopIntent(context: Context) =
            Intent(context, ShopeeKitForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ShopeeKitForegroundService = this@ShopeeKitForegroundService
    }

    private val binder = LocalBinder()
    private var statusText: String = "ShopeeKit đang chạy"

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // API 34+ requires explicit foregroundServiceType in startForeground()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_NOTIFICATION -> {
                statusText = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: statusText
                updateNotification(statusText)
            }
            else -> { /* Already started */ }
        }
        return START_STICKY // Restart if killed
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun updateStatus(text: String) {
        statusText = text
        updateNotification(text)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShopeeKit")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Voucher Sniper countdown"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
