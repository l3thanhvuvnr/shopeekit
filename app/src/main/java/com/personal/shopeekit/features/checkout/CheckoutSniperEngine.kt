package com.personal.shopeekit.features.checkout

import com.personal.shopeekit.core.logging.KitLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Orchestrates the checkout snipe sequence.
 *
 * Flow:
 *  1. arm(config) → calibrate TimeSync (server clock) + RTT
 *  2. Schedule fire just AFTER T (releaseTimeMs + commit margin), widened by the
 *     clock's own uncertainty — the snipe is gated on SERVER time, not the device clock.
 *  3. On fire: verify on checkout screen → open voucher drawer (AutoBest) → confirm.
 *     FULL mode additionally hard-gates the order to server-time ≥ T + margin.
 *  4. If not yet live / rejected: reopen the drawer fresh → retry until T + retryTimeout
 *     (catches Shopee releasing the voucher LATE — the common case).
 *  5. Before each retry: idempotency check (order already placed?)
 *  6. Stop on: SUCCESS / OUT_OF_STOCK / PAYMENT_ERROR / REQUIRES_PIN / WINDOW_END / MAX_ATTEMPTS
 */
class CheckoutSniperEngine(
    private val driver: CheckoutUiDriver = AccessibilityCheckoutUiDriver,
    private val clock: SniperClock = RealSniperClock(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    companion object {
        private const val WARMUP_LEAD_MS = 2_000L
        private const val WARMUP_STEP_MS = 350L
        // Only pre-open the drawer if there's this much lead before T, so the
        // prewarm (open + dismiss, ~1-1.5s) finishes comfortably before fire. An arm
        // made closer to T than this skips prewarm and falls back to a cold open.
        private const val PREWARM_MIN_LEAD_MS = 1_500L
        // E1: cap attempts to prevent runaway retries / double-order
        private const val MAX_ATTEMPTS = 25
        // E1: after tapping place-order, wait this long to detect success screen
        private const val POST_TAP_CHECK_MS = 1_500L
        // Snipe release timing (server-clock gated). Fire/commit this far AFTER the
        // release instant T. Shopee's voucher backend commonly lags wall-clock 00:00
        // by a few hundred ms; ordering before the voucher is live would buy without
        // it. Kept small so we stay among the first once it unlocks.
        private const val COMMIT_MARGIN_MS = 200L
    }

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var calibrationJob: Job? = null
    private var fireJob: Job? = null
    private var warmUpJob: Job? = null

    private val _state = MutableStateFlow<CheckoutSniperState>(CheckoutSniperState.Idle)
    val state: StateFlow<CheckoutSniperState> = _state

    fun arm(config: CheckoutConfig) {
        KitLogger.i("ENG", "arm() — releaseAt=${config.releaseTimeMs} timeout=${config.retryTimeoutMs}ms pref=${config.voucherPreference}")
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
        clock.cancelSchedule()
        calibrationJob?.cancel()
        fireJob?.cancel()
        warmUpJob?.cancel()
    }

    private suspend fun calibrateAndSchedule(config: CheckoutConfig) {
        val serverOffset = clock.calibrate()
        val rtt = clock.measureRtt()
        val jitter = HumanBehavior.leadJitterMs()

        // The snipe is gated on SERVER time inside the fire loop: open the voucher
        // drawer only AFTER T (so AutoBest sees the freshly-live voucher) and never
        // order before T. So fire just AFTER T, widened by the clock's own uncertainty
        // (edge-detected ≈ ±40ms, coarse median ≈ ±500ms) so a fast/skewed device clock
        // still can't open the drawer before the voucher unlocks. (The old "fire at
        // T − networkLead" is correct for landing ONE request at T, but wrong for a UI
        // interaction that needs Shopee's server state to have already flipped.)
        val clockErrMs = if (clock.isRefined) 60L else 500L
        val commitMargin = if (config.mode == SnipeMode.FULL_CHECKOUT) COMMIT_MARGIN_MS + clockErrMs else 0L

        val localReleaseMs = config.releaseTimeMs - serverOffset
        val fireAtLocal = localReleaseMs + commitMargin + jitter

        _state.value = CheckoutSniperState.Armed(
            config = config,
            fireAtMs = fireAtLocal,
            rttMs = rtt,
            serverOffsetMs = serverOffset
        )

        KitLogger.i("ENG", "calibrated — offset=${serverOffset}ms RTT=${rtt}ms clock=${if (clock.isRefined) "refined±${clockErrMs}" else "coarse±${clockErrMs}"}ms commitMargin=${commitMargin}ms fireAt=$fireAtLocal jitter=$jitter")

        warmUpJob = scope.launch { runWarmUp(fireAtLocal) }

        clock.scheduleAt(fireAtLocal) {
            fireJob = scope.launch { executeFireLoop(config) }
        }
    }

    private suspend fun runWarmUp(fireAtLocal: Long) {
        while (clock.nowMs() < fireAtLocal - WARMUP_LEAD_MS) {
            delay(200L)
        }
        // One-shot drawer prewarm at the start of the warm-up window, only if there's
        // comfortable lead so it finishes well before T. Warms the RN drawer + TLS
        // connection so the real open at T is faster. Best-effort (never confirms).
        if (clock.nowMs() < fireAtLocal - PREWARM_MIN_LEAD_MS) {
            driver.prewarmDrawer()
        }
        while (clock.nowMs() < fireAtLocal - 200L) {
            driver.warmUpNudge()
            delay(WARMUP_STEP_MS + HumanBehavior.leadJitterMs() * 4)
        }
    }

    private suspend fun executeFireLoop(config: CheckoutConfig) {
        // E5: screen guard — bail if not on checkout screen before first tap
        if (!driver.isOnCheckoutScreen()) {
            KitLogger.w("ENG", "ABORT — not on checkout screen, disarming")
            _state.value = CheckoutSniperState.Failed(
                reason = "Không ở màn thanh toán — hãy mở Shopee về trang checkout trước",
                attemptCount = 0
            )
            return
        }

        // Server-time release window: keep retrying from now until T + retryTimeout,
        // keyed to Shopee's clock so a LATE voucher release (the common case) is still
        // caught. Each attempt reopens the drawer, so AutoBest re-selects whatever is
        // the best voucher currently live.
        val windowEndServer = config.releaseTimeMs + config.retryTimeoutMs
        var attempt = 0

        // Baseline for the idempotency check: an order confirmation already visible
        // NOW — before we've placed anything — is a LEFTOVER (a manual order, or the
        // detectCheckoutScreen guard matching "đặt hàng" inside "đặt hàng thành công"),
        // never ours. Only a confirmation that appears AFTER we start counts, so record
        // the pre-existing state and disable the shortcut when it's already set.
        val orderPresentAtStart = config.mode == SnipeMode.FULL_CHECKOUT &&
            driver.hasRecentOrder()
        if (orderPresentAtStart) {
            KitLogger.w("ENG", "order confirmation already on screen at fire start — idempotency shortcut disabled to avoid false success")
        }

        KitLogger.i("ENG", "fire — nowServer=${clock.serverNowMs()} T=${config.releaseTimeMs} window=until T+${config.retryTimeoutMs}ms clock=${if (clock.isRefined) "refined" else "coarse"}")

        while (clock.serverNowMs() < windowEndServer && attempt < MAX_ATTEMPTS) {
            attempt++
            val attemptMs = clock.nowMs()
            val rel = clock.serverNowMs() - config.releaseTimeMs
            KitLogger.d("ENG", "attempt #$attempt / $MAX_ATTEMPTS mode=${config.mode} T${if (rel >= 0) "+" else ""}${rel}ms")

            // E1: IDEMPOTENCY CHECK — detect order already placed before attempting.
            // Only meaningful when we actually place orders; in the 2-step rehearsal
            // we never order, so a leftover confirmation must not read as our success.
            if (config.mode == SnipeMode.FULL_CHECKOUT && !orderPresentAtStart &&
                driver.hasRecentOrder()
            ) {
                _state.value = CheckoutSniperState.Success(
                    latencyMs = latencyFromReleaseMs(config),
                    appliedVoucher = null,
                    detectedExisting = true
                )
                return
            }

            _state.value = CheckoutSniperState.Firing(attempt, attemptMs)

            // Step 1+2: apply best voucher (open "Shopee Voucher" → select → confirm).
            // Only the 3-step live run must verify the voucher actually applied before
            // ordering; the 2-step rehearsal passes on a successful open+confirm (the
            // voucher is time-gated and only applies at the release instant).
            _state.value = CheckoutSniperState.ApplyingVoucher
            val requireApplied = config.mode == SnipeMode.FULL_CHECKOUT
            val voucher = driver.applyBestVoucher(config.voucherPreference, requireApplied)

            // Service down is fatal — retrying can't help.
            if (voucher is VoucherApplyResult.AccessibilityUnavailable) {
                _state.value = CheckoutSniperState.Failed(
                    reason = "AccessibilityService chưa kết nối",
                    attemptCount = attempt
                )
                return
            }

            // Voucher step didn't complete → retry the whole pass. NEVER fall through
            // to place-order on a failed voucher apply (that would checkout without
            // the voucher the user is sniping).
            if (voucher !is VoucherApplyResult.Applied) {
                val retryMs = HumanBehavior.retryDelayMs()
                KitLogger.w("ENG", "voucher apply not complete: ${voucher::class.simpleName} — retry in ${retryMs}ms")
                _state.value = CheckoutSniperState.RetryLoop(
                    attemptCount = attempt,
                    nextRetryMs = clock.nowMs() + retryMs,
                    lastError = voucherFailReason(voucher)
                )
                delay(retryMs)
                continue
            }

            val appliedVoucher = voucher.voucherLabel

            // ── 2-step rehearsal: stop here, before the irreversible order ──
            if (config.mode == SnipeMode.VOUCHER_ONLY) {
                KitLogger.i("ENG", "VOUCHER_ONLY — applied ($appliedVoucher / ${voucher.discountText}); stopping before place-order")
                _state.value = CheckoutSniperState.VoucherApplied(
                    voucherLabel = appliedVoucher,
                    discountText = voucher.discountText,
                    attemptCount = attempt
                )
                return
            }

            // ── HARD SERVER-TIME GATE (FULL only) ──
            // Never place the irreversible order before T. If this apply happened
            // pre-release (fired early / coarse clock), the voucher list was fetched
            // before unlock, so AutoBest may hold a stale/lesser voucher — hold to
            // T + margin, then loop to RE-APPLY (reopen the drawer) so AutoBest
            // re-selects the now-live best voucher. This pre-release apply is never ordered.
            val nowServer = clock.serverNowMs()
            val commitAtServer = config.releaseTimeMs + COMMIT_MARGIN_MS
            if (nowServer < commitAtServer) {
                val holdMs = (commitAtServer - nowServer).coerceIn(0L, config.retryTimeoutMs)
                KitLogger.i("ENG", "pre-release apply (T${nowServer - config.releaseTimeMs}ms) — hold ${holdMs}ms & re-apply; NOT ordering yet")
                _state.value = CheckoutSniperState.RetryLoop(
                    attemptCount = attempt,
                    nextRetryMs = clock.nowMs() + holdMs,
                    lastError = "Chờ tới giờ mở voucher..."
                )
                delay(holdMs)
                continue
            }

            delay(HumanBehavior.applyToOrderDelayMs())

            // E5b: the screen can change between attempts (a Shopee interstitial, the
            // voucher drawer, navigation). Re-verify we're still on checkout right
            // before the irreversible tap — the entry guard alone doesn't cover later
            // attempts, and clickPlaceOrder would otherwise fire on an unexpected screen.
            if (!driver.isOnCheckoutScreen()) {
                val retryMs = HumanBehavior.retryDelayMs()
                KitLogger.w("ENG", "left checkout screen before place-order — retry in ${retryMs}ms")
                _state.value = CheckoutSniperState.RetryLoop(
                    attemptCount = attempt,
                    nextRetryMs = clock.nowMs() + retryMs,
                    lastError = "Không còn ở màn thanh toán, thử lại..."
                )
                delay(retryMs)
                continue
            }

            // Bail if disarmed in the window between the server-time gate and the tap:
            // cancellation is cooperative, so without this an in-flight disarm() could
            // still let the order tap land after the user cancelled.
            coroutineContext.ensureActive()

            // ── 3-step live run: place the real order ──
            _state.value = CheckoutSniperState.PlacingOrder
            val result = driver.clickPlaceOrder()

            when (result) {
                is PlaceOrderResult.Success -> {
                    val latency = latencyFromReleaseMs(config)
                    KitLogger.i("ENG", "SUCCESS latency=${latency}ms voucher=$appliedVoucher")
                    _state.value = CheckoutSniperState.Success(
                        latencyMs = latency,
                        appliedVoucher = appliedVoucher
                    )
                    return
                }

                // E2: PIN/OTP → stop immediately, notify user
                is PlaceOrderResult.RequiresPin -> {
                    KitLogger.w("ENG", "STOP — PIN/OTP prompt detected: ${result.hint.take(60)}")
                    _state.value = CheckoutSniperState.RequiresPin(result.hint)
                    return
                }

                is PlaceOrderResult.VoucherNotYet -> {
                    val retryMs = HumanBehavior.retryDelayMs()
                    _state.value = CheckoutSniperState.RetryLoop(
                        attemptCount = attempt,
                        nextRetryMs = clock.nowMs() + retryMs,
                        lastError = "Voucher chưa hợp lệ, thử lại..."
                    )
                    delay(retryMs)
                    continue
                }

                is PlaceOrderResult.OutOfStock -> {
                    _state.value = CheckoutSniperState.OutOfStock(clock.nowMs())
                    return
                }

                is PlaceOrderResult.VoucherExhausted -> {
                    KitLogger.w("ENG", "STOP — voucher exhausted / hết lượt")
                    _state.value = CheckoutSniperState.VoucherExhausted(clock.nowMs())
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

                // Refused a low-confidence guess for the destructive tap — retrying
                // won't help (same tree), so stop rather than risk the wrong button.
                is PlaceOrderResult.NeedsCalibration -> {
                    KitLogger.e("ENG", "STOP — place-order not confidently identified (no resource-id/text match)")
                    _state.value = CheckoutSniperState.Failed(
                        reason = "Không nhận diện chắc chắn nút Đặt hàng — kiểm tra lại màn thanh toán Shopee rồi thử lại.",
                        attemptCount = attempt
                    )
                    return
                }

                is PlaceOrderResult.Unknown -> {
                    KitLogger.d("ENG", "Unknown result: ${result.message.take(80)}, polling ${POST_TAP_CHECK_MS}ms for success screen")
                    // E1: after Unknown, watch for the success screen before retrying
                    // (prevents double-order when the order was placed but the network
                    // response lagged). POLL with early-exit-to-Success rather than a
                    // flat sleep: it catches a success screen that renders at ~300ms and
                    // navigates away before the ceiling (which a single late check would
                    // miss → wrong retry → double order), and shaves the happy path.
                    // NOTE: the loop MUST re-read the clock (POST_TAP_CHECK_MS is the
                    // ceiling before ANY retry — the anti-double-order guard is preserved).
                    val postTapDeadline = clock.nowMs() + POST_TAP_CHECK_MS
                    while (clock.nowMs() < postTapDeadline) {
                        if (driver.hasRecentOrder()) {
                            _state.value = CheckoutSniperState.Success(
                                latencyMs = latencyFromReleaseMs(config),
                                appliedVoucher = appliedVoucher,
                                detectedExisting = true
                            )
                            return
                        }
                        delay(120)
                    }

                    val retryMs = HumanBehavior.retryDelayMs()
                    _state.value = CheckoutSniperState.RetryLoop(
                        attemptCount = attempt,
                        nextRetryMs = clock.nowMs() + retryMs,
                        lastError = result.message
                    )
                    delay(retryMs)
                }
            }
        }

        val reason = when {
            attempt >= MAX_ATTEMPTS ->
                "Đã thử $MAX_ATTEMPTS lần, dừng để tránh đặt trùng đơn"
            // Loop condition was false on entry → the window had already closed before
            // a single attempt ran. Almost always a stale release time (arm happened
            // after T, or the device clock jumped). Say so instead of implying we tried.
            attempt == 0 ->
                "Giờ mở đã qua trước khi bắt đầu — kiểm tra lại thời gian voucher rồi ARM lại"
            else ->
                "Hết cửa sổ ${config.retryTimeoutMs / 1000}s sau giờ mở — voucher chưa mở hoặc đã hết lượt"
        }

        KitLogger.e("ENG", "FAILED — $reason after $attempt attempts")
        _state.value = CheckoutSniperState.Failed(reason = reason, attemptCount = attempt)
    }

    fun msUntilRelease(): Long {
        return when (val s = _state.value) {
            is CheckoutSniperState.Armed -> s.config.releaseTimeMs - clock.serverNowMs()
            else -> -1L
        }
    }

    /**
     * Latency of an event vs the release instant T, measured on Shopee's clock.
     * Must use [SniperClock.serverNowMs] (not the local clock): releaseTimeMs is a
     * server-epoch instant, so subtracting the raw device time would be off by the
     * whole serverOffset (which can be hundreds of ms on a skewed clock).
     */
    private fun latencyFromReleaseMs(config: CheckoutConfig): Long =
        clock.serverNowMs() - config.releaseTimeMs

    /** Human-facing reason for a non-fatal voucher-apply miss, shown during retries. */
    private fun voucherFailReason(result: VoucherApplyResult): String = when (result) {
        is VoucherApplyResult.PickerNotFound ->
            "Không thấy dòng 'Shopee Voucher' — kiểm tra đang ở màn thanh toán"
        is VoucherApplyResult.DrawerNotOpened ->
            "Danh sách voucher chưa mở (đang tải), thử lại..."
        is VoucherApplyResult.ConfirmNotFound ->
            "Không thấy nút 'Đồng ý'/Áp dụng trong danh sách voucher, thử lại..."
        is VoucherApplyResult.NothingApplied ->
            "Chưa có voucher nào được áp (chưa tới giờ / không đủ điều kiện), thử lại..."
        else -> "Áp voucher chưa xong, thử lại..."
    }
}
