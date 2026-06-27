package com.personal.shopeekit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.personal.shopeekit.R
import com.personal.shopeekit.core.storage.ShopeeConfig
import com.personal.shopeekit.features.checkout.HumanBehavior
import com.personal.shopeekit.features.checkout.OrderResultParser
import com.personal.shopeekit.features.checkout.PlaceOrderResult
import com.personal.shopeekit.features.checkout.VoucherPreference
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeElement
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeScreen
import com.personal.shopeekit.ui.ShopeeCookieSyncActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accessibility Service for ShopeeKit.
 *
 * Primary role:
 *  1. Monitor Shopee app UI state (foreground detection, cookie-sync prompt)
 *  2. Drive the CheckoutSniper flow: apply best voucher + place order
 *  3. Detect order success / out-of-stock / payment errors from UI feedback
 *
 * NOTE: AccessibilityService.performAction() drives Shopee's own UI code path,
 * so each tap goes through Shopee's normal request signing (X-Sap-Ri etc.) —
 * this is why we automate via the UI instead of calling the API directly.
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

        // ─── CheckoutSniper API ───────────────────────────────────────────────

        /**
         * Apply best voucher on checkout screen.
         * Uses UIDiscovery multi-strategy to find voucher picker + voucher items.
         * Returns display name of applied voucher or null.
         */
        fun applyBestVoucher(preference: VoucherPreference): String? {
            val svc = instance ?: return null
            return svc.performApplyBestVoucher(preference)
        }

        /**
         * Click the "Đặt hàng" button and parse result.
         */
        fun clickPlaceOrder(): PlaceOrderResult {
            val svc = instance ?: return PlaceOrderResult.AccessibilityUnavailable
            return svc.performPlaceOrder()
        }

        /**
         * Check if a recent order was just placed (idempotency check).
         * Looks for order confirmation text or recent order in order list.
         */
        fun hasRecentOrder(): Boolean {
            val svc = instance ?: return false
            return svc.detectRecentOrderCreated()
        }

        /**
         * E5: Check if the current screen is the checkout screen.
         * Guards against firing when user navigated away.
         */
        fun isOnCheckoutScreen(): Boolean {
            val svc = instance ?: return false
            return svc.detectCheckoutScreen()
        }

        /**
         * Harmless warm-up activity before fire (anti-fraud). No-op if the
         * service isn't connected. See [performWarmUpNudge].
         */
        fun warmUpNudge() {
            instance?.performWarmUpNudge()
        }
    }

    private var overlayView: android.view.View? = null
    private var windowManager: WindowManager? = null
    private var lastSyncNotifMs: Long = 0L   // throttle: show max 1 notif per 30min

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setupOverlayWindow()
        createNotificationChannel()
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
        removeOverlayWindow()
        super.onDestroy()
    }

    // ─── CheckoutSniper: Voucher Apply ────────────────────────────────────────

    /**
     * Apply best voucher on the current checkout screen.
     * Steps: open picker → select best → tap Apply
     */
    private fun performApplyBestVoucher(preference: VoucherPreference): String? {
        val root = rootInActiveWindow ?: return null

        // Step 1: Open voucher picker (tap "Chọn Voucher khác" / picker row)
        val pickerRow = ShopeeUIDiscovery.find(root, ShopeeScreen.CHECKOUT, ShopeeElement.VOUCHER_PICKER_ROW)
        pickerRow?.let { humanTap(it) }
        // Wait for the picker sheet to render (RN loads async) instead of a fixed sleep.
        val updatedRoot = waitForElement(ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON)
            ?: rootInActiveWindow ?: return null

        // Step 2: Select best voucher
        // E4: scroll the picker list to trigger RN lazy-render before collecting items
        scrollVoucherList(updatedRoot)
        val appliedName = when (preference) {
            is VoucherPreference.AutoBest -> clickAutoSelectButton(updatedRoot)
            is VoucherPreference.MaxDiscount -> selectVoucherByMaxDiscount(updatedRoot)
            is VoucherPreference.MaxCashback -> selectVoucherByMaxCashback(updatedRoot)
            is VoucherPreference.ManualCode -> applyManualCode(updatedRoot, preference.code)
        }

        // Step 3: Tap "Áp dụng"
        val afterSelectRoot = rootInActiveWindow ?: return appliedName
        val applyBtn = ShopeeUIDiscovery.find(
            afterSelectRoot, ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON
        )
        applyBtn?.let { humanTap(it) }

        return appliedName
    }

    /**
     * Poll the active window until [element] appears on [screen], or [timeoutMs]
     * elapses. Returns the root that contains it, or null on timeout.
     * Uses SystemClock.sleep (OK on non-main thread called from AccessibilityService
     * callback) rather than Thread.sleep to avoid spurious ANR traces.
     */
    private fun waitForElement(
        screen: ShopeeScreen,
        element: ShopeeElement,
        timeoutMs: Long = 800L,
        stepMs: Long = 50L
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && ShopeeUIDiscovery.find(root, screen, element, requireClickable = false) != null) {
                return root
            }
            android.os.SystemClock.sleep(stepMs)
        }
        return rootInActiveWindow
    }

    private fun clickAutoSelectButton(root: AccessibilityNodeInfo): String? {
        val autoBtn = ShopeeUIDiscovery.find(
            root, ShopeeScreen.VOUCHER_PICKER, ShopeeElement.AUTO_SELECT_VOUCHER
        )
        return if (autoBtn != null) {
            humanTap(autoBtn)
            "auto-selected"
        } else {
            // Fallback: max discount
            selectVoucherByMaxDiscount(root)
        }
    }

    private fun selectVoucherByMaxDiscount(root: AccessibilityNodeInfo): String? {
        val items = ShopeeUIDiscovery.findAll(root, ShopeeScreen.VOUCHER_LIST, ShopeeElement.VOUCHER_LIST_ITEM)
        if (items.isEmpty()) return null

        // Parse discount amounts from each item's subtree
        val best = items.maxByOrNull { extractDiscountAmount(it) } ?: return null
        humanTap(best)
        return best.text?.toString() ?: extractDiscountText(best)
    }

    private fun selectVoucherByMaxCashback(root: AccessibilityNodeInfo): String? {
        val items = ShopeeUIDiscovery.findAll(root, ShopeeScreen.VOUCHER_LIST, ShopeeElement.VOUCHER_LIST_ITEM)
        if (items.isEmpty()) return null

        val best = items.maxByOrNull { extractCashbackPercent(it) } ?: return null
        humanTap(best)
        return best.text?.toString() ?: extractDiscountText(best)
    }

    private fun applyManualCode(root: AccessibilityNodeInfo, code: String): String? {
        // Find voucher code input field
        val inputNode = traverseForInput(root) ?: return null
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
        }
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return code
    }

    /** Extract absolute discount amount (e.g. "Giảm 200.000đ" → 200000) */
    private fun extractDiscountAmount(node: AccessibilityNodeInfo): Long {
        val text = extractDiscountText(node) ?: return 0L
        val cleaned = text.replace("[^0-9]".toRegex(), "")
        return cleaned.toLongOrNull() ?: 0L
    }

    /** Extract cashback percent (e.g. "Hoàn 15%" → 15) */
    private fun extractCashbackPercent(node: AccessibilityNodeInfo): Int {
        val text = extractDiscountText(node) ?: return 0
        val match = Regex("""(\d+)%""").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractDiscountText(node: AccessibilityNodeInfo): String? {
        if (node.text != null) return node.text.toString()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = extractDiscountText(child)
            if (text != null) return text
        }
        return null
    }

    private fun traverseForInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = traverseForInput(child)
            if (result != null) return result
        }
        return null
    }

    // ─── CheckoutSniper: Place Order ─────────────────────────────────────────

    private fun performPlaceOrder(): PlaceOrderResult {
        val root = rootInActiveWindow ?: return PlaceOrderResult.AccessibilityUnavailable

        val placeOrderBtn = ShopeeUIDiscovery.find(
            root, ShopeeScreen.CHECKOUT, ShopeeElement.PLACE_ORDER_BUTTON
        ) ?: return PlaceOrderResult.AccessibilityUnavailable

        val clicked = humanTap(placeOrderBtn)
        if (!clicked) return PlaceOrderResult.AccessibilityUnavailable

        // Wait for UI feedback (toast / dialog / PIN prompt)
        android.os.SystemClock.sleep(500)

        return parseOrderResult()
    }

    /**
     * Tap a node like a human: a gesture at an off-centre point inside the
     * node's bounds with a randomised stroke duration. Falls back to
     * ACTION_CLICK if the node has no usable bounds or gesture dispatch fails.
     *
     * Off-centre, variable-duration taps avoid the pixel-perfect, fixed-length
     * signature that a server-side risk model can flag (see [HumanBehavior]).
     */
    private fun humanTap(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val (x, y) = HumanBehavior.tapPointIn(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, HumanBehavior.tapDurationMs()))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        // If gesture dispatch returned false (e.g. another gesture in flight), fall back.
        return if (dispatched) true else node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun parseOrderResult(): PlaceOrderResult {
        val root = rootInActiveWindow ?: return PlaceOrderResult.Unknown("No root")
        return OrderResultParser.parse(extractAllText(root))
    }

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        if (node.text != null) sb.append(node.text).append(" ")
        if (node.contentDescription != null) sb.append(node.contentDescription).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(extractAllText(child))
        }
        return sb.toString()
    }

    // ─── CheckoutSniper: Idempotency Check ───────────────────────────────────

    /**
     * Detect if an order was recently created (handles network lag edge case).
     * Looks for order success screen or recent order in "Đơn hàng của tôi".
     */
    private fun detectRecentOrderCreated(): Boolean {
        val root = rootInActiveWindow ?: return false
        val text = extractAllText(root).lowercase()

        return text.contains("đặt hàng thành công") ||
            text.contains("order placed successfully") ||
            text.contains("đã đặt hàng") ||
            text.contains("mã đơn hàng") ||
            text.contains("order id") ||
            text.contains("order #")
    }

    // E5: check current screen has checkout signals (before firing)
    private fun detectCheckoutScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        val text = extractAllText(root).lowercase()
        // Must have place-order button text OR order total visible
        return text.contains("đặt hàng") || text.contains("place order") ||
            text.contains("tổng thanh toán") || text.contains("total payment")
    }

    // ─── CheckoutSniper: Warm-up (anti-fraud) ────────────────────────────────

    /**
     * E4: Scroll voucher picker list down then back to trigger RN lazy rendering.
     * Without this, items at the bottom of the list may not yet be in the a11y tree.
     */
    private fun scrollVoucherList(root: AccessibilityNodeInfo) {
        val scrollable = findScrollable(root) ?: return
        scrollable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id)
        android.os.SystemClock.sleep(150)
        scrollable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id)
        android.os.SystemClock.sleep(100)
    }

    /**
     * One unit of harmless "I'm looking at the screen" activity, run repeatedly
     * by CheckoutSniperEngine in the ~2s before fire. Scrolls a scrollable node
     * a little forward then back so the checkout state is unchanged, but the
     * session isn't dead-still right up to the millisecond it taps.
     */
    private fun performWarmUpNudge() {
        val root = rootInActiveWindow ?: return
        val scrollable = findScrollable(root) ?: return
        scrollable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id)
        android.os.SystemClock.sleep(HumanBehavior.tapDurationMs())
        scrollable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
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

        val config = ShopeeConfig(this)
        val cookie = config.getCookieSync()
        val needsSync = cookie.isBlank()

        if (!needsSync) return // cookie already set, no need to bother user

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
