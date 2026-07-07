package com.personal.shopeekit.service

import com.personal.shopeekit.core.logging.KitLogger
import com.personal.shopeekit.features.checkout.OrderResultParser
import com.personal.shopeekit.features.checkout.PlaceOrderResult
import com.personal.shopeekit.service.CheckoutActuator.Companion.POLL_STEP_MS
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeElement
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeScreen
import kotlinx.coroutines.delay

/**
 * The place-order flow + the two checkout screen probes (idempotency, screen
 * guard), extracted verbatim from ShopeeAccessibilityService. Drives the UI via a
 * [CheckoutActuator]. No behaviour change.
 */
class PlaceOrderFlow(private val act: CheckoutActuator) {

    companion object {
        // Ceiling for the order-result screen after tapping place-order. Polled with
        // early-exit, so the happy path returns as soon as the result renders
        // (~150-400ms) rather than always paying a flat wait.
        private const val ORDER_RESULT_TIMEOUT_MS = 800L
    }

    suspend fun run(): PlaceOrderResult {
        val root = act.root ?: return PlaceOrderResult.AccessibilityUnavailable

        val match = ShopeeUIDiscovery.findMatch(
            root, ShopeeScreen.CHECKOUT, ShopeeElement.PLACE_ORDER_BUTTON
        ) ?: return PlaceOrderResult.AccessibilityUnavailable

        KitLogger.i(
            "SNIPE",
            "place-order resolved by ${match.source} conf=${"%.2f".format(match.confidence)} → ${match.label}"
        )

        // FAIL SAFE: never tap a pure heuristic guess for the destructive
        // place-order action — that's exactly how a wrong button gets pressed.
        // If we can't identify it by a user pin or a real text match, refuse and
        // ask the user to calibrate the button once (guaranteed-correct path).
        if (match.source == ShopeeUIDiscovery.MatchSource.HEURISTIC) {
            KitLogger.w("SNIPE", "place-order only matched by heuristic — refusing to tap; calibration needed")
            return PlaceOrderResult.NeedsCalibration
        }

        val clicked = act.humanTap(match.node)
        if (!clicked) return PlaceOrderResult.AccessibilityUnavailable

        // Poll for the order-result screen and return the moment a terminal result
        // (success / PIN / error / OOS) renders, instead of always paying a flat
        // 500ms. The plain checkout screen parses as Unknown, so we keep polling
        // until Shopee navigates — the happy path returns in ~150-400ms, and a PIN
        // prompt is caught the instant it appears.
        return waitForOrderResult()
    }

    /**
     * Poll the active window until [OrderResultParser] yields a terminal result
     * (anything other than [PlaceOrderResult.Unknown]) or [timeoutMs] elapses.
     * Early-exit keeps post-tap latency at the result's real render time rather
     * than a fixed wait. Parsing is quiet (log=false) to avoid flooding the Debug
     * Log with one line per poll; the resolved result is logged once.
     */
    private suspend fun waitForOrderResult(
        timeoutMs: Long = ORDER_RESULT_TIMEOUT_MS,
        stepMs: Long = POLL_STEP_MS
    ): PlaceOrderResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: PlaceOrderResult = PlaceOrderResult.Unknown("no result yet")
        while (System.currentTimeMillis() < deadline) {
            val root = act.root
            if (root != null) {
                val r = OrderResultParser.parse(A11yTreeUtils.allText(root), log = false)
                if (r !is PlaceOrderResult.Unknown) {
                    KitLogger.i("SNIPE", "order result: ${r::class.simpleName} after ${System.currentTimeMillis() - (deadline - timeoutMs)}ms")
                    return r
                }
                last = r
            }
            delay(stepMs)
        }
        KitLogger.d("SNIPE", "order-result poll timed out → Unknown")
        return last
    }

    /**
     * Detect if an order was recently created (handles network lag edge case).
     * Looks for order success screen or recent order in "Đơn hàng của tôi".
     */
    fun detectRecentOrder(): Boolean {
        val root = act.root ?: return false
        val text = A11yTreeUtils.allText(root).lowercase()

        return text.contains("đặt hàng thành công") ||
            text.contains("order placed successfully") ||
            text.contains("đã đặt hàng") ||
            text.contains("mã đơn hàng") ||
            text.contains("order id") ||
            text.contains("order #")
    }

    /** E5: check current screen has checkout signals (before firing). */
    fun isOnCheckout(): Boolean {
        val root = act.root ?: return false
        val text = A11yTreeUtils.allText(root).lowercase()
        // Must have place-order button text OR order total visible
        return text.contains("đặt hàng") || text.contains("place order") ||
            text.contains("tổng thanh toán") || text.contains("total payment")
    }
}
