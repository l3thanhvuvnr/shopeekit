package com.personal.shopeekit.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.personal.shopeekit.core.logging.KitLogger
import com.personal.shopeekit.core.storage.ShopeeConfig
import com.personal.shopeekit.features.checkout.PlaceOrderResult
import com.personal.shopeekit.features.checkout.VoucherApplyResult
import com.personal.shopeekit.features.checkout.VoucherPreference
import com.personal.shopeekit.ui.ShopeeCookieSyncActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Accessibility Service for ShopeeKit.
 *
 * Primary role:
 *  1. Monitor Shopee app UI state (foreground detection, cookie-sync prompt)
 *  2. Host the CheckoutSniper flows (apply best voucher + place order)
 *  3. Own the overlay window + cookie-sync notification
 *
 * The actual snipe logic lives in [CheckoutActuator] (low-level taps/polling),
 * [VoucherApplyFlow], and [PlaceOrderFlow]; this class is the AccessibilityService
 * host that owns them and exposes a static API to the engine.
 *
 * NOTE: AccessibilityService.performAction()/dispatchGesture() drive Shopee's own
 * UI code path, so each tap goes through Shopee's normal request signing (X-Sap-Ri
 * etc.) — this is why we automate via the UI instead of calling the API directly.
 */
class ShopeeAccessibilityService : AccessibilityService() {

    companion object {
        const val SHOPEE_PACKAGE = "com.shopee.vn"
        private const val TAG = "ShopeeAccess"
        private const val NOTIF_CHANNEL_SYNC = "cookie_sync"
        private const val NOTIF_ID_SYNC = 9001

        // Foreground state exposed to the UI layer
        private val _isShopeeActive = MutableStateFlow(false)
        val isShopeeActive: StateFlow<Boolean> = _isShopeeActive

        // Service instance reference (set on connect)
        @Volatile private var instance: ShopeeAccessibilityService? = null

        fun getInstance(): ShopeeAccessibilityService? = instance

        // ─── CheckoutSniper API (delegates to the extracted flows) ─────────────

        /**
         * Apply best voucher on checkout screen: open the "Shopee Voucher" row,
         * select per [preference], confirm ("Đồng ý"/OK/Áp dụng).
         *
         * [requireApplied] — verify the voucher actually shows applied on the checkout
         * before returning [VoucherApplyResult.Applied]. TRUE for the 3-step live run
         * (never place a voucher-less order). FALSE for the 2-step rehearsal: platform
         * vouchers are time-gated and only apply AT the release instant, so before then
         * "open drawer → Đồng ý" (the refresh action) is itself the pass condition —
         * requiring an applied state would wrongly fail the test.
         */
        suspend fun applyBestVoucher(
            preference: VoucherPreference,
            requireApplied: Boolean
        ): VoucherApplyResult {
            val svc = instance ?: return VoucherApplyResult.AccessibilityUnavailable
            return svc.voucherFlow.apply(preference, requireApplied)
        }

        /** Click the "Đặt hàng" button and parse result. */
        suspend fun clickPlaceOrder(): PlaceOrderResult {
            val svc = instance ?: return PlaceOrderResult.AccessibilityUnavailable
            return svc.placeOrderFlow.run()
        }

        /**
         * Check if a recent order was just placed (idempotency check).
         * Looks for order confirmation text or recent order in order list.
         */
        fun hasRecentOrder(): Boolean = instance?.placeOrderFlow?.detectRecentOrder() ?: false

        /**
         * E5: Check if the current screen is the checkout screen.
         * Guards against firing when user navigated away.
         */
        fun isOnCheckoutScreen(): Boolean = instance?.placeOrderFlow?.isOnCheckout() ?: false

        /**
         * Harmless warm-up activity before fire (anti-fraud). No-op if the service
         * isn't connected.
         */
        fun warmUpNudge() {
            instance?.actuator?.warmUpNudge()
        }

        /**
         * Pre-open the voucher drawer once before T (warm-up). No-op if the service
         * isn't connected. Never confirms a voucher. See [VoucherApplyFlow.prewarm].
         */
        suspend fun prewarmDrawer() {
            instance?.voucherFlow?.prewarm()
        }
    }

    private var overlayView: android.view.View? = null
    private var windowManager: WindowManager? = null
    private var lastSyncNotifMs: Long = 0L   // throttle: show max 1 notif per 30min

    // Cookie presence, kept fresh by a background collector so the (main-thread)
    // accessibility callback never has to block on DataStore. Starts false
    // (assume present) so we don't nag before the first read lands.
    @Volatile private var cookieBlank: Boolean = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Snipe flows, hosted by this service. Lazy so they bind to `this` service.
    private val actuator by lazy { CheckoutActuator(this) }
    private val voucherFlow by lazy { VoucherApplyFlow(actuator) }
    private val placeOrderFlow by lazy { PlaceOrderFlow(actuator) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        publishDisplaySize()
        ShopeeUIDiscovery.preloadCache()   // restore learned/pinned id hints
        setupOverlayWindow()
        createNotificationChannel()
        // Track cookie presence off the main thread — read once here and keep it
        // current, so onAccessibilityEvent never calls a blocking getCookieSync().
        serviceScope.launch {
            ShopeeConfig(this@ShopeeAccessibilityService).cookieFlow.collect { cookie ->
                cookieBlank = cookie.isBlank()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        publishDisplaySize()   // keep the off-screen reference correct after rotation
    }

    /**
     * Publish the real physical display size to [ShopeeUIDiscovery] so its off-screen
     * phantom guard has a STABLE reference. `rootInActiveWindow.getBoundsInScreen`
     * is unreliable mid-animation (Shopee's RN root can report a virtual width wider
     * than the screen — the off-screen drawer pages), which let a phantom "Đồng ý" at
     * x≈1620 on a 1080-wide screen pass as on-screen. The Display size is constant.
     */
    private fun publishDisplaySize() {
        val dm = resources.displayMetrics
        ShopeeUIDiscovery.setDisplaySize(dm.widthPixels, dm.heightPixels)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val isShopee = packageName == SHOPEE_PACKAGE
        _isShopeeActive.value = isShopee

        if (!isShopee) return

        // Auto-sync cookie notification: show when Shopee is foreground and cookie missing/stale
        maybeShowSyncNotification()
    }

    override fun onInterrupt() {
        _isShopeeActive.value = false
    }

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        removeOverlayWindow()
        super.onDestroy()
    }

    /**
     * Setup 1px overlay window for receiving ACTION_OUTSIDE touch events.
     * Pattern from android-setup-guide.md (BVC project).
     */
    private fun setupOverlayWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        overlayView = android.view.View(this)
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            // Overlay permission not granted yet
        }
    }

    private fun removeOverlayWindow() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { /* ignore */ }
        overlayView = null
    }

    // ─── Cookie Auto-Sync ─────────────────────────────────────────────────────

    /**
     * Show a notification prompting user to sync cookie when:
     *  - Shopee app just came to foreground
     *  - Cookie is blank OR not refreshed in last 12 hours
     *  - Last notification was > 30 minutes ago (throttle)
     */
    private fun maybeShowSyncNotification() {
        val now = System.currentTimeMillis()
        if (now - lastSyncNotifMs < 30 * 60 * 1000L) return // throttle 30min

        // Read the cached flag (kept fresh by the collector in onServiceConnected)
        // instead of a blocking DataStore read on the main thread.
        if (!cookieBlank) return // cookie already set, no need to bother user

        lastSyncNotifMs = now
        Log.i(TAG, "Shopee foreground, cookie missing — showing sync notification")

        val intent = Intent(this, ShopeeCookieSyncActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("ShopeeKit — Đồng bộ tài khoản")
            .setContentText("Nhấn để đồng bộ session Shopee (cần 1 lần)")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_SYNC, notif)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_SYNC,
            "Cookie Sync",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Thông báo đồng bộ session Shopee"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
