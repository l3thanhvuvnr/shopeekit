package com.personal.shopeekit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.personal.shopeekit.core.logging.KitLogger
import com.personal.shopeekit.features.checkout.HumanBehavior
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeElement
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeScreen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Low-level tap + poll primitives over an [AccessibilityService], shared by
 * [VoucherApplyFlow] and [PlaceOrderFlow]. Extracted from the old god-class so
 * the two flows can be read (and reasoned about) without the service's lifecycle
 * and notification code around them. Pure relocation — behaviour unchanged.
 */
class CheckoutActuator(private val service: AccessibilityService) {

    companion object {
        // Poll cadence while waiting for a Shopee RN transition (drawer open/close,
        // order-result screen). 16ms ≈ one 60fps frame: the UI cannot change faster
        // than a frame, so polling tighter only burns CPU without detecting sooner.
        // Each poll snapshots the a11y tree (Binder IPC) + a DFS find, which
        // self-limits the effective rate on large trees. This is the main knob for
        // "detect the transition ASAP" — the drawer render/close animations
        // themselves are Shopee's and can't be shortened.
        const val POLL_STEP_MS = 16L
    }

    val root: AccessibilityNodeInfo? get() = service.rootInActiveWindow

    /**
     * Poll the active window until [element] appears on [screen], or [timeoutMs]
     * elapses. Returns the root that contains it, or null on timeout.
     */
    suspend fun waitForElement(
        screen: ShopeeScreen,
        element: ShopeeElement,
        timeoutMs: Long = 800L,
        stepMs: Long = POLL_STEP_MS
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = root
            if (r != null &&
                ShopeeUIDiscovery.find(r, screen, element, requireClickable = false, log = false) != null
            ) {
                return r
            }
            delay(stepMs)
        }
        return root
    }

    /**
     * Poll until [element] is NO LONGER present on [screen], i.e. a sheet/page has
     * closed. Returns true if it disappeared within [timeoutMs].
     *
     * Needed because the checkout stays mounted BEHIND the voucher page, so its
     * place-order button is always findable — "place-order visible again" is NOT a
     * reliable "drawer closed" signal, but "confirm button gone" is.
     */
    suspend fun waitForElementGone(
        screen: ShopeeScreen,
        element: ShopeeElement,
        timeoutMs: Long = 2000L,
        stepMs: Long = POLL_STEP_MS
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = root
            if (r == null ||
                ShopeeUIDiscovery.find(r, screen, element, requireClickable = false, log = false) == null
            ) {
                return true
            }
            delay(stepMs)
        }
        return false
    }

    private enum class GestureOutcome { COMPLETED, CANCELLED, NOT_DISPATCHED }

    /**
     * Tap a node like a human: a gesture at an off-centre point inside the
     * node's bounds with a randomised stroke duration. Falls back to
     * ACTION_CLICK if the node has no usable bounds or gesture dispatch fails.
     *
     * Off-centre, variable-duration taps avoid the pixel-perfect, fixed-length
     * signature that a server-side risk model can flag (see [HumanBehavior]).
     */
    suspend fun humanTap(node: AccessibilityNodeInfo): Boolean {
        // Re-sync the node before reading its bounds: it may have been resolved a few
        // poll cycles ago and Shopee's RN tree can have re-rendered since, leaving the
        // cached bounds stale. refresh() returns false if the node is gone.
        try { node.refresh() } catch (_: Exception) { /* best-effort */ }
        val bounds = android.graphics.Rect().also {
            try { node.getBoundsInScreen(it) } catch (_: Exception) { it.setEmpty() }
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return safePerformAction(node, AccessibilityNodeInfo.ACTION_CLICK)
        }
        val (x, y) = HumanBehavior.tapPointIn(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, HumanBehavior.tapDurationMs()))
            .build()
        // dispatchGesture is ASYNC: it returns before the stroke lands. SUSPEND (not
        // block) this coroutine until the gesture completes, so callers never race
        // ahead of the tap — that race made the engine declare the voucher "applied"
        // ~40ms before the "Đồng ý" tap even finished, so the confirm never took and
        // the drawer stayed open. Suspending also lets disarm() cancel us mid-tap.
        // withTimeoutOrNull(700) preserves the old 700ms wait ceiling.
        val outcome = withTimeoutOrNull(700L) {
            suspendCancellableCoroutine { cont ->
                val cb = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(GestureOutcome.COMPLETED)
                    }
                    override fun onCancelled(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(GestureOutcome.CANCELLED)
                    }
                }
                val dispatched = service.dispatchGesture(gesture, cb, null)
                if (!dispatched && cont.isActive) cont.resume(GestureOutcome.NOT_DISPATCHED)
            }
        }
        KitLogger.d("TAP", "@(${x.toInt()},${y.toInt()}) box=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] outcome=$outcome")
        // Fall back to ACTION_CLICK ONLY when the gesture was refused or genuinely
        // CANCELLED (never landed). On a timeout (null) with no callback the stroke was
        // already dispatched and almost certainly landed — a fallback tap here would
        // DOUBLE-tap, which on the place-order button means a duplicate order. Trust it.
        return when (outcome) {
            GestureOutcome.COMPLETED -> true
            GestureOutcome.CANCELLED -> safePerformAction(node, AccessibilityNodeInfo.ACTION_CLICK)
            GestureOutcome.NOT_DISPATCHED -> {
                KitLogger.w("TAP", "dispatch refused → ACTION_CLICK fallback")
                safePerformAction(node, AccessibilityNodeInfo.ACTION_CLICK)
            }
            null -> {
                KitLogger.w("TAP", "gesture callback timed out — assuming landed, no fallback tap")
                true
            }
        }
    }

    /**
     * Tap a node preferring the semantic click ACTION over a coordinate gesture.
     * Used for Shopee's clickable wrapper nodes (e.g. the voucher row) whose a11y
     * bounds are unreliable so a gesture would miss; ACTION_CLICK is position-free.
     * Falls back to a human gesture if the node isn't clickable or the action fails.
     */
    suspend fun tapPreferClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && safePerformAction(node, AccessibilityNodeInfo.ACTION_CLICK)) {
            KitLogger.d("TAP", "ACTION_CLICK ${node.viewIdResourceName?.substringAfterLast('/') ?: node.className}")
            return true
        }
        return humanTap(node)
    }

    /**
     * [AccessibilityNodeInfo.performAction] on a node that Shopee's RN tree has
     * recycled throws IllegalStateException; wrap it so a mid-flight window change
     * degrades to a no-op (false) instead of crashing the engine coroutine.
     */
    fun safePerformAction(node: AccessibilityNodeInfo, action: Int): Boolean =
        try {
            node.performAction(action)
        } catch (e: Exception) {
            KitLogger.w("TAP", "performAction($action) failed on stale node: ${e.message}")
            false
        }

    /**
     * One unit of harmless "I'm looking at the screen" activity, run repeatedly by
     * CheckoutSniperEngine in the ~2s before fire. Scrolls a scrollable node a little
     * forward then back so the checkout state is unchanged, but the session isn't
     * dead-still right up to the millisecond it taps.
     */
    fun warmUpNudge() {
        val r = root ?: return
        val scrollable = A11yTreeUtils.findScrollable(r) ?: return
        safePerformAction(scrollable, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id)
        android.os.SystemClock.sleep(HumanBehavior.tapDurationMs())
        safePerformAction(scrollable, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id)
    }
}
