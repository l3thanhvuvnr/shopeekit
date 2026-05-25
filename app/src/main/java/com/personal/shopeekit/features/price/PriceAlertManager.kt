package com.personal.shopeekit.features.price

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.personal.shopeekit.R

/**
 * Sends notifications when product price drops below threshold.
 */
class PriceAlertManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "price_alerts"
        const val CHANNEL_NAME = "Price Alerts"
    }

    init {
        createChannel()
    }

    fun notifyPriceDrop(
        productId: String,
        productName: String,
        currentPrice: Long,
        previousPrice: Long,
        threshold: Long
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val drop = previousPrice - currentPrice
        val notifId = productId.hashCode()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_price_drop)
            .setContentTitle("📉 Giá giảm: $productName")
            .setContentText(
                "₫${formatPrice(currentPrice)} (-₫${formatPrice(drop)}) " +
                "• Ngưỡng: ₫${formatPrice(threshold)}"
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Giá hiện tại: ₫${formatPrice(currentPrice)}\n" +
                "Giá trước: ₫${formatPrice(previousPrice)}\n" +
                "Giảm: ₫${formatPrice(drop)} (${((drop.toDouble()/previousPrice)*100).toInt()}%)"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(notifId, notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Price drop alerts for tracked products"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun formatPrice(vnd: Long): String {
        return String.format("%,d", vnd).replace(',', '.')
    }
}
