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
 *  3. On fire: apply best voucher + place order
 *  4. If rejected: re-scan voucher list fresh → retry every 50ms
 *  5. Before each retry: idempotency check (order already placed?)
 *  6. Stop on: SUCCESS / OUT_OF_STOCK / PAYMENT_ERROR / TIMEOUT
 */
class CheckoutSniperEngine(private val context: Context) {

    companion object {
        // Warm-up window before fire: mimic a human glancing at the checkout
        // screen instead of standing perfectly still then tapping at T.
        private const val WARMUP_LEAD_MS = 2_000L
        private const val WARMUP_STEP_MS = 350L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scheduler = SpeculativeScheduler()
    private var calibrationJob: Job? = null
    private var fireJob: Job? = null

    private val _state = MutableStateFlow<CheckoutSniperState>(CheckoutSniperState.Idle)
    val state: StateFlow<CheckoutSniperState> = _state

    fun arm(config: CheckoutConfig) {
        cancel()
        _state.value = CheckoutSniperState.Armed(
            config = config,
            fireAtMs = 0L,
            rttMs = 0L,
            serverOffsetMs = 0L
        )

        calibrationJob = scope.launch {
            calibrateAndSchedule(config)
        }
    }

    fun disarm() {
        cancel()
        _state.value = CheckoutSniperState.Idle
    }

    private fun cancel() {
        scheduler.cancel()
        calibrationJob?.cancel()
        fireJob?.cancel()
    }

    private suspend fun calibrateAndSchedule(config: CheckoutConfig) {
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

        // Warm-up: in the last ~2s before fire, perform harmless reads/scrolls so
        // the session shows human-like activity rather than a dead-still wait.
        scope.launch { runWarmUp(fireAtLocal) }

        scheduler.scheduleAt(fireAtLocal) {
            fireJob = scope.launch {
                executeFireLoop(config)
            }
        }
    }

    /**
     * Gentle, low-frequency UI activity in the warm-up window before fire.
     * Reads the node tree and nudges a small scroll — enough to look alive,
     * not enough to disturb the checkout state.
     */
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
        val deadline = System.currentTimeMillis() + config.retryTimeoutMs
        var attempt = 0

        while (System.currentTimeMillis() < deadline) {
            attempt++
            val attemptMs = System.currentTimeMillis()

            // IDEMPOTENCY CHECK — detect order already placed (network lag edge case)
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
            val appliedVoucher = applyBestVoucher(config.voucherPreference)

            // Human-like delay between apply and place order (right-skewed).
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

                is PlaceOrderResult.VoucherNotYet -> {
                    // Voucher delayed — retry with fresh voucher scan next iteration
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
                    // Unknown result — retry
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

        _state.value = CheckoutSniperState.Failed(
            reason = "Hết thời gian retry (${config.retryTimeoutMs / 1000}s)",
            attemptCount = attempt
        )
    }

    /**
     * Apply best voucher via AccessibilityService.
     * Returns display name of applied voucher, or null.
     */
    private suspend fun applyBestVoucher(preference: VoucherPreference): String? {
        return ShopeeAccessibilityService.applyBestVoucher(preference)
    }

    /**
     * Time remaining until voucher release (ms). Negative if overdue.
     */
    fun msUntilRelease(): Long {
        return when (val s = _state.value) {
            is CheckoutSniperState.Armed -> s.config.releaseTimeMs - TimeSync.serverTimeMs()
            else -> -1L
        }
    }
}
