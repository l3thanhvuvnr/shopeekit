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

data class CheckoutConfig(
    val releaseTimeMs: Long,
    val voucherPreference: VoucherPreference = VoucherPreference.AutoBest,
    val retryTimeoutMs: Long = DEFAULT_RETRY_TIMEOUT_MS,
    val selectedProductIds: List<String> = emptyList()
) {
    init {
        require(retryTimeoutMs in MIN_RETRY_TIMEOUT_MS..MAX_RETRY_TIMEOUT_MS) {
            "retryTimeoutMs must be ${MIN_RETRY_TIMEOUT_MS}–${MAX_RETRY_TIMEOUT_MS} ms"
        }
    }

    companion object {
        const val DEFAULT_RETRY_TIMEOUT_MS = 120_000L  // 2 minutes
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

    data class OutOfStock(val timestamp: Long) : CheckoutSniperState()

    data class Failed(
        val reason: String,
        val attemptCount: Int,
        val lastAttemptMs: Long = System.currentTimeMillis()
    ) : CheckoutSniperState()

    // ShopeePay PIN/OTP prompt — loop stopped, user must enter manually
    data class RequiresPin(val hint: String = "") : CheckoutSniperState()
}

/** Result from a single place-order attempt */
sealed class PlaceOrderResult {
    object Success : PlaceOrderResult()
    object VoucherNotYet : PlaceOrderResult()   // voucher not yet valid, retry
    object OutOfStock : PlaceOrderResult()
    object PaymentError : PlaceOrderResult()
    object AccessibilityUnavailable : PlaceOrderResult()
    // PIN/OTP prompt appeared — stop loop, notify user to enter manually
    data class RequiresPin(val hint: String = "") : PlaceOrderResult()
    data class Unknown(val message: String) : PlaceOrderResult()
}
