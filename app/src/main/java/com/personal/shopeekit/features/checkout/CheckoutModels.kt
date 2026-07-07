package com.personal.shopeekit.features.checkout

sealed class VoucherPreference {
    /** Let Shopee's own "Tự động chọn" algorithm pick the best combo */
    object AutoBest : VoucherPreference()

    /** Pick voucher with highest absolute discount amount */
    object MaxDiscount : VoucherPreference()

    /** Pick voucher with highest cashback percentage */
    object MaxCashback : VoucherPreference()

    /** User specifies exact voucher code */
    data class ManualCode(val code: String) : VoucherPreference()
}

/**
 * How far the snipe sequence is allowed to go. The place-order tap is
 * irreversible on real Shopee (it creates an actual order), so the flow is
 * split into a safe rehearsal and the live run:
 *
 *  - [VOUCHER_ONLY] — 2 steps: open "Shopee Voucher" → chọn → "Đồng ý"/Áp dụng,
 *    then STOP before "Đặt hàng". Lets the user verify voucher detection & timing
 *    on the real app with zero risk of placing an order.
 *  - [FULL_CHECKOUT] — 3 steps: the above, and only if the voucher actually
 *    applied, tap "Đặt hàng" to place the real order.
 *
 * Default is [VOUCHER_ONLY] so an accidental arm can never buy anything; the
 * user must consciously switch to [FULL_CHECKOUT].
 */
enum class SnipeMode {
    VOUCHER_ONLY,
    FULL_CHECKOUT
}

data class CheckoutConfig(
    val releaseTimeMs: Long,
    val voucherPreference: VoucherPreference = VoucherPreference.AutoBest,
    val retryTimeoutMs: Long = DEFAULT_RETRY_TIMEOUT_MS,
    val mode: SnipeMode = SnipeMode.VOUCHER_ONLY,
    val selectedProductIds: List<String> = emptyList()
) {
    init {
        require(retryTimeoutMs in MIN_RETRY_TIMEOUT_MS..MAX_RETRY_TIMEOUT_MS) {
            "retryTimeoutMs must be ${MIN_RETRY_TIMEOUT_MS}–${MAX_RETRY_TIMEOUT_MS} ms"
        }
        // releaseTimeMs is a server-epoch instant; a non-positive value is a
        // construction bug (uninitialised / bad parse) that would make the fire
        // window close before it opens and report a misleading "hết cửa sổ".
        require(releaseTimeMs > 0L) {
            "releaseTimeMs must be a positive epoch millis (was $releaseTimeMs)"
        }
    }

    companion object {
        const val DEFAULT_RETRY_TIMEOUT_MS = 30_000L   // 30 seconds — covers Shopee's typical late voucher release
        const val MIN_RETRY_TIMEOUT_MS = 30_000L       // 30 seconds
        const val MAX_RETRY_TIMEOUT_MS = 600_000L      // 10 minutes
    }
}

sealed class CheckoutSniperState {
    object Idle : CheckoutSniperState()

    data class Armed(
        val config: CheckoutConfig,
        val fireAtMs: Long,
        val rttMs: Long,
        val serverOffsetMs: Long
    ) : CheckoutSniperState()

    data class Firing(
        val attemptCount: Int,
        val lastAttemptMs: Long
    ) : CheckoutSniperState()

    object ApplyingVoucher : CheckoutSniperState()
    object PlacingOrder : CheckoutSniperState()

    /**
     * Terminal success for [SnipeMode.VOUCHER_ONLY]: the voucher was applied and
     * the loop stopped *before* placing an order (the safe 2-step rehearsal).
     */
    data class VoucherApplied(
        val voucherLabel: String?,
        val discountText: String?,
        val attemptCount: Int
    ) : CheckoutSniperState()

    data class RetryLoop(
        val attemptCount: Int,
        val nextRetryMs: Long,
        val lastError: String
    ) : CheckoutSniperState()

    data class Success(
        val latencyMs: Long,
        val appliedVoucher: String?,
        val savedAmount: Long = 0L,
        val detectedExisting: Boolean = false  // true = idempotency check detected order
    ) : CheckoutSniperState()

    /** The product itself is sold out ("hết hàng") — ordering can't succeed. */
    data class OutOfStock(val timestamp: Long) : CheckoutSniperState()

    /**
     * The voucher is exhausted / all claimed ("hết voucher", "đã hết lượt") — distinct
     * from a sold-out product: the item is still buyable, just without this voucher, so
     * the UI can offer to place the order anyway.
     */
    data class VoucherExhausted(val timestamp: Long) : CheckoutSniperState()

    data class Failed(
        val reason: String,
        val attemptCount: Int,
        val lastAttemptMs: Long = System.currentTimeMillis()
    ) : CheckoutSniperState()

    // ShopeePay PIN/OTP prompt — loop stopped, user must enter manually
    data class RequiresPin(val hint: String = "") : CheckoutSniperState()
}

/**
 * Outcome of one voucher-apply pass (open "Shopee Voucher" → select → confirm).
 * Structured so the engine can (a) stop-and-report in the 2-step rehearsal and
 * (b) refuse to place a real order in the 3-step run when the voucher step did
 * not actually complete — never checkout without the voucher the user is here for.
 */
sealed class VoucherApplyResult {
    /**
     * The drawer opened, the selection was confirmed, and control returned to
     * checkout. [discountText] is best-effort ("giảm 40.000đ" / "Đã áp dụng…") —
     * null just means we couldn't read the amount, not that it failed.
     */
    data class Applied(val voucherLabel: String?, val discountText: String?) : VoucherApplyResult()

    /** The "Shopee Voucher" row wasn't on screen (not on checkout, or hidden). */
    object PickerNotFound : VoucherApplyResult()

    /** Tapped the row but the voucher drawer never rendered (RN still loading / no network). */
    object DrawerNotOpened : VoucherApplyResult()

    /** Drawer opened but no confirm button ("Đồng ý"/OK/Áp dụng) could be found. */
    object ConfirmNotFound : VoucherApplyResult()

    /**
     * The drawer opened and we confirmed, but afterwards NO platform voucher is
     * actually applied on checkout — the "Shopee Voucher" row still shows the empty
     * "Chọn hoặc nhập mã" placeholder (Shopee auto-selected nothing: no eligible or
     * not-yet-valid voucher). Distinct from [Applied] so the flow retries and, in the
     * 3-step run, never places a voucher-less order. "Drawer closed" alone was a
     * false-positive: a bare confirm with nothing selected also closes the drawer.
     */
    object NothingApplied : VoucherApplyResult()

    /** AccessibilityService not connected. */
    object AccessibilityUnavailable : VoucherApplyResult()
}

/** Result from a single place-order attempt */
sealed class PlaceOrderResult {
    object Success : PlaceOrderResult()
    object VoucherNotYet : PlaceOrderResult()   // voucher not yet valid, retry
    object OutOfStock : PlaceOrderResult()       // product sold out ("hết hàng")
    object VoucherExhausted : PlaceOrderResult() // voucher all claimed ("hết voucher"/"đã hết lượt")
    object PaymentError : PlaceOrderResult()
    object AccessibilityUnavailable : PlaceOrderResult()
    // Place-order button could only be guessed heuristically — refuse to tap and
    // ask the user to calibrate rather than risk pressing the wrong element.
    object NeedsCalibration : PlaceOrderResult()
    // PIN/OTP prompt appeared — stop loop, notify user to enter manually
    data class RequiresPin(val hint: String = "") : PlaceOrderResult()
    data class Unknown(val message: String) : PlaceOrderResult()
}
