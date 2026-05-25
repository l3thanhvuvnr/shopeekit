package com.personal.shopeekit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the sniper schedule after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Restart foreground service so scheduled snipes survive reboot
            context.startForegroundService(ShopeeKitForegroundService.startIntent(context))
        }
    }
}
