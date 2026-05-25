package com.personal.shopeekit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accessibility Service for ShopeeKit.
 *
 * Primary role:
 *  1. Monitor Shopee app UI state
 *  2. Find and click the voucher claim button when triggered by SniperEngine
 *  3. Detect claim success/fail from UI feedback
 *
 * NOTE: AccessibilityService.performAction() generates a valid X-Sap-Ri internally
 * via Shopee's own code path — this is why we use it instead of direct OkHttp for
 * the voucher claim endpoint.
 */
class ShopeeAccessibilityService : AccessibilityService() {

    companion object {
        const val SHOPEE_PACKAGE = "com.shopee.vn"

        // State exposed to SniperEngine
        private val _isShopeeActive = MutableStateFlow(false)
        private val _lastClickResult = MutableStateFlow<ClickResult>(ClickResult.None)
        private val _claimButtonVisible = MutableStateFlow(false)

        val isShopeeActive: StateFlow<Boolean> = _isShopeeActive
        val lastClickResult: StateFlow<ClickResult> = _lastClickResult
        val claimButtonVisible: StateFlow<Boolean> = _claimButtonVisible

        // Service instance reference (set on connect)
        @Volatile private var instance: ShopeeAccessibilityService? = null

        fun getInstance(): ShopeeAccessibilityService? = instance

        /**
         * Trigger the claim click from SniperEngine.
         * Returns true if click was dispatched.
         */
        fun triggerClaimClick(): Boolean {
            val svc = instance ?: return false
            return svc.performClaimClick()
        }
    }

    private var overlayView: android.view.View? = null
    private var windowManager: WindowManager? = null

    // Button resource IDs commonly used by Shopee for voucher claim
    private val claimButtonIds = listOf(
        "com.shopee.vn:id/btn_claim",
        "com.shopee.vn:id/claim_voucher_btn",
        "com.shopee.vn:id/tv_claim",
        "com.shopee.vn:id/btn_get_voucher"
    )

    // Text labels for claim buttons (language-aware fallback)
    private val claimButtonTexts = listOf(
        "Lấy voucher", "Nhận", "Lấy", "Claim", "Get"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setupOverlayWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val isShopee = packageName == SHOPEE_PACKAGE
        _isShopeeActive.value = isShopee

        if (!isShopee) return

        // Check if claim button is currently visible
        val rootNode = rootInActiveWindow ?: return
        val claimNode = findClaimButton(rootNode)
        _claimButtonVisible.value = claimNode != null
        rootNode.recycle()

        // Detect success/fail feedback from Shopee UI
        detectClaimFeedback(event)
    }

    override fun onInterrupt() {
        _isShopeeActive.value = false
    }

    override fun onDestroy() {
        instance = null
        removeOverlayWindow()
        super.onDestroy()
    }

    /**
     * Finds the voucher claim button in the current Shopee UI.
     */
    private fun findClaimButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Try by resource ID first (faster)
        for (id in claimButtonIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val node = nodes.first()
                if (node.isEnabled && node.isClickable) return node
                node.recycle()
            }
        }

        // Fallback: find by text
        for (text in claimButtonTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                val node = nodes.first()
                if (node.isEnabled && node.isClickable) return node
                node.recycle()
            }
        }

        return null
    }

    /**
     * Perform the claim click. Called by SniperEngine at the precise moment.
     * Returns true if click was dispatched.
     */
    fun performClaimClick(): Boolean {
        val root = rootInActiveWindow ?: return false
        val claimNode = findClaimButton(root)
        root.recycle()

        return if (claimNode != null) {
            val success = claimNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            claimNode.recycle()
            if (success) _lastClickResult.value = ClickResult.Clicked(System.currentTimeMillis())
            success
        } else {
            // Fallback: gesture click at center of screen
            performCenterClick()
        }
    }

    /**
     * Fallback gesture click at screen center if accessibility node not found.
     */
    private fun performCenterClick(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val display = windowManager?.defaultDisplay ?: return false
        @Suppress("DEPRECATION")
        val centerX = display.width / 2f
        @Suppress("DEPRECATION")
        val centerY = display.height / 2f

        val path = Path().apply { moveTo(centerX, centerY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Detect success/fail feedback from Shopee UI after claim attempt.
     * Looks for toast messages, snackbars, or dialog text.
     */
    private fun detectClaimFeedback(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val text = event.text?.joinToString(" ") ?: return
        when {
            text.contains("thành công", ignoreCase = true) ||
            text.contains("đã lấy", ignoreCase = true) ||
            text.contains("successfully", ignoreCase = true) -> {
                _lastClickResult.value = ClickResult.Success(System.currentTimeMillis())
            }
            text.contains("hết", ignoreCase = true) ||
            text.contains("đã hết", ignoreCase = true) ||
            text.contains("out of stock", ignoreCase = true) -> {
                _lastClickResult.value = ClickResult.OutOfStock(System.currentTimeMillis())
            }
            text.contains("lỗi", ignoreCase = true) ||
            text.contains("thất bại", ignoreCase = true) -> {
                _lastClickResult.value = ClickResult.Failed(System.currentTimeMillis(), text)
            }
        }
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
}

sealed class ClickResult {
    object None : ClickResult()
    data class Clicked(val timestamp: Long) : ClickResult()
    data class Success(val timestamp: Long) : ClickResult()
    data class OutOfStock(val timestamp: Long) : ClickResult()
    data class Failed(val timestamp: Long, val reason: String) : ClickResult()
}
