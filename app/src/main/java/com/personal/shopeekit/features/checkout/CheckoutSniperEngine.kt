package com.personal.shopeekit.features.checkout

import android.content.Context
import com.personal.shopeekit.core.time.RttMeasurer
import com.personal.shopeekit.core.time.SpeculativeScheduler
import com.personal.shopeekit.core.time.TimeSync
import com.personal.shopeekit.service.ShopeeAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the checkout snipe sequence.
 *
 * Flow:
 *  1. arm(config) → calibrate TimeSync + RTT
 *  2. Schedule fire at (releaseTimeMs - rtt - buffer)
 *  3. On fire: verify on checkout screen → apply best voucher + place order
 *  4. If rejected: re-scan voucher list fresh → retry every 50ms
 *  5. Before each retry: idempotency check (order already placed?)
 *  6. Stop on: SUCCESS / OUT_OF_STOCK / PAYMENT_ERROR / REQUIRES_PIN / TIMEOUT / MAX_ATTEMPTS
 */
class CheckoutSniperEngine(private val context: Context) {

    companion object {
        private const val WARMUP_LEAD_MS = 2_000L
        private const val WARMUP_STEP_MS = 350L
        // E1: cap attempts to prevent runaway retries / double-order
        private const val MAX_ATTEMPTS = 25
        // E1: after tapping place-order, wait this long to detect success screen
        private const val POST_TAP_CHECK_MS = 1_500L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scheduler = SpeculativeScheduler()
    private var calibrationJob: Job? = null
    private var fireJob: Job? = null
    private var warmUpJob: Job? = null

    private val _state = MutableStateFlow<CheckoutSniperState>(CheckoutSniperState.Idle)
    val state: StateFlow<CheckoutSniperState> = _state

    fun arm(config: CheckoutConfig) {
        cancelInternalJobs()
        _state.value = CheckoutSniperState.Armed(
            config = config,
            fireAtMs = 0L,
            rttMs = 0L,
            serverOffsetMs = 0L
        )
        calibrationJob = scope.launch { calibrateAndSchedule(config) }
    }

    fun disarm() {
        cancelInternalJobs()
        _state.value = CheckoutSniperState.Idle
    }

    /** Release all resources. Call from CheckoutSniperFeature.release(). */
    fun destroy() {
        cancelInternalJobs()
        scope.cancel()
    }

    private fun cancelInternalJobs() {
        scheduler.cancel()
        calibrationJob?.cancel()
        fireJob?.cancel()
        warmUpJob?.cancel()
    }

    private suspend fun calibrateAndSchedule(config: CheckoutConfig) {
        // F3: ensure fresh calibration immediately before scheduling
        TimeSync.ensureFresh()
        val serverOffset = TimeSync.calibrate()
        val rtt = RttMeasurer.measure()
        val jitter = HumanBehavior.leadJitterMs()
        val lead = RttMeasurer.speculativeLeadMs()

        val localReleaseMs = config.releaseTimeMs - serverOffset
        val fireAtLocal = localReleaseMs - lead + jitter

        _state.value = CheckoutSniperState.Armed(
            config = config,
            fireAtMs = fireAtLocal,
            rttMs = rtt,
            serverOffsetMs = serverOffset
        )

        warmUpJob = scope.launch { runWarmUp(fireAtLocal) }

        scheduler.scheduleAt(fireAtLocal) {
            fireJob = scope.launch { executeFireLoop(config) }
        }
    }

    private suspend fun runWarmUp(fireAtLocal: Long) {
        while (System.currentTimeMillis() < fireAtLocal - WARMUP_LEAD_MS) {
            delay(200L)
        }
        while (System.currentTimeMillis() < fireAtLocal - 200L) {
            ShopeeAccessibilityService.warmUpNudge()
            delay(WARMUP_STEP_MS + HumanBehavior.leadJitterMs() * 4)
        }
    }

    private suspend fun executeFireLoop(config: CheckoutConfig) {
        // E5: screen guard — bail if not on checkout screen before first tap
        if (!ShopeeAccessibilityService.isOnCheckoutScreen()) {
            _state.value = CheckoutSniperState.Failed(
                reason = "Không ở màn thanh toán — hãy mở Shopee về trang checkout trước",
                attemptCount = 0
            )
            return
        }

        val deadline = System.currentTimeMillis() + config.retryTimeoutMs
        var attempt = 0

        while (System.currentTimeMillis() < deadline && attempt < MAX_ATTEMPTS) {
            attempt++
            val attemptMs = System.currentTimeMillis()

            // E1: IDEMPOTENCY CHECK — detect order already placed before attempting
            if (ShopeeAccessibilityService.hasRecentOrder()) {
                _state.value = CheckoutSniperState.Success(
                    latencyMs = attemptMs - config.releaseTimeMs,
                    appliedVoucher = null,
                    detectedExisting = true
                )
                return
            }

            _state.value = CheckoutSniperState.Firing(attempt, attemptMs)

            // Step 1: Apply best voucher (fresh scan every attempt)
            _state.value = CheckoutSniperState.ApplyingVoucher
            val appliedVoucher = ShopeeAccessibilityService.applyBestVoucher(config.voucherPreference)

            delay(HumanBehavior.applyToOrderDelayMs())

            // Step 2: Place order
            _state.value = CheckoutSniperState.PlacingOrder
            val result = ShopeeAccessibilityService.clickPlaceOrder()

            when (result) {
                is PlaceOrderResult.Success -> {
                    _state.value = CheckoutSniperState.Success(
                        latencyMs = System.currentTimeMillis() - config.releaseTimeMs,
                        appliedVoucher = appliedVoucher
                    )
                    return
                }

                // E2: PIN/OTP → stop immediately, notify user
                is PlaceOrderResult.RequiresPin -> {
                    _state.value = CheckoutSniperState.RequiresPin(result.hint)
                    return
                }

                is PlaceOrderResult.VoucherNotYet -> {
                    val retryMs = HumanBehavior.retryDelayMs()
                    _state.value = CheckoutSniperState.RetryLoop(
                        attemptCount = attempt,
                        nextRetryMs = System.currentTimeMillis() + retryMs,
                        lastError = "Voucher chưa hợp lệ, thử lại..."
                    )
                    delay(retryMs)
                    continue
                }

                is PlaceOrderResult.OutOfStock -> {
                    _state.value = CheckoutSniperState.OutOfStock(System.currentTimeMillis())
                    return
                }

                is PlaceOrderResult.PaymentError -> {
                    _state.value = CheckoutSniperState.Failed(
                        reason = "Lỗi thanh toán",
                        attemptCount = attempt
                    )
                    return
                }

                is PlaceOrderResult.AccessibilityUnavailable -> {
                    _state.value = CheckoutSniperState.Failed(
                        reason = "AccessibilityService chưa kết nối",
                        attemptCount = attempt
                    )
                    return
                }

                is PlaceOrderResult.Unknown -> {
                    // E1: after Unknown, wait briefly to detect success screen before retrying
                    // (prevents double-order when order placed but network response delayed)
                    delay(POST_TAP_CHECK_MS)
                    if (ShopeeAccessibilityService.hasRecentOrder()) {
                        _state.value = CheckoutSniperState.Success(
                            latencyMs = System.currentTimeMillis() - config.releaseTimeMs,
                            appliedVoucher = appliedVoucher,
                            detectedExisting = true
                        )
                        return
                    }

                    val retryMs = HumanBehavior.retryDelayMs()
                    _state.value = CheckoutSniperState.RetryLoop(
                        attemptCount = attempt,
                        nextRetryMs = System.currentTimeMillis() + retryMs,
                        lastError = result.message
                    )
                    delay(retryMs)
                }
            }
        }

        val reason = if (attempt >= MAX_ATTEMPTS)
            "Đã thử $MAX_ATTEMPTS lần, dừng để tránh đặt trùng đơn"
        else
            "Hết thời gian retry (${config.retryTimeoutMs / 1000}s)"

        _state.value = CheckoutSniperState.Failed(reason = reason, attemptCount = attempt)
    }

    fun msUntilRelease(): Long {
        return when (val s = _state.value) {
            is CheckoutSniperState.Armed -> s.config.releaseTimeMs - TimeSync.serverTimeMs()
            else -> -1L
        }
    }
}
