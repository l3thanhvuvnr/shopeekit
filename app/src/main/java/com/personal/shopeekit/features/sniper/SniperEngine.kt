package com.personal.shopeekit.features.sniper

import android.content.Context
import com.personal.shopeekit.core.time.RttMeasurer
import com.personal.shopeekit.core.time.TimeSync
import com.personal.shopeekit.service.ClickResult
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
 * Core orchestrator for VoucherSniper.
 *
 * Flow:
 *  1. arm(voucherUrl, releaseTimeMs)
 *  2. Calibrate TimeSync + RTT
 *  3. Schedule SpeculativeScheduler at (releaseTimeMs - rtt - buffer)
 *  4. On fire: trigger AccessibilityService click
 *  5. Monitor result, retry up to MAX_RETRIES
 *
 * Exposes StateFlow<SniperState> for UI observation.
 */
class SniperEngine(private val context: Context) {

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 80L   // Wait 80ms between retries
        private const val RESULT_POLL_MS = 50L   // Poll interval for result detection
        private const val RESULT_TIMEOUT_MS = 3000L  // Give up after 3s
        private const val EXTRA_JITTER_MAX_MS = 15L  // Random anti-ban jitter
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scheduler = SpeculativeScheduler()
    private var calibrationJob: Job? = null
    private var monitorJob: Job? = null

    private val _state = MutableStateFlow<SniperState>(SniperState.Idle)
    val state: StateFlow<SniperState> = _state

    /**
     * Schedule a voucher snipe.
     * @param voucherUrl Shopee voucher URL
     * @param releaseTimeMs Epoch ms when voucher becomes claimable (server time)
     * @param voucherLabel Display name (optional)
     */
    fun arm(voucherUrl: String, releaseTimeMs: Long, voucherLabel: String = "") {
        scheduler.cancel()
        calibrationJob?.cancel()
        monitorJob?.cancel()

        _state.value = SniperState.Scheduled(voucherUrl, releaseTimeMs, voucherLabel)

        calibrationJob = scope.launch {
            calibrateAndSchedule(voucherUrl, releaseTimeMs)
        }
    }

    fun disarm() {
        scheduler.cancel()
        calibrationJob?.cancel()
        monitorJob?.cancel()
        _state.value = SniperState.Idle
    }

    private suspend fun calibrateAndSchedule(voucherUrl: String, releaseTimeMs: Long) {
        // Calibrate server clock offset and RTT
        val serverOffset = TimeSync.calibrate()
        val rtt = RttMeasurer.measure()

        // Compute fire time: before T so request arrives AT T
        val jitter = (Math.random() * EXTRA_JITTER_MAX_MS).toLong()
        val lead = RttMeasurer.speculativeLeadMs()
        // Convert server release time to local device time
        val localReleaseMs = releaseTimeMs - serverOffset
        val fireAtLocal = localReleaseMs - lead + jitter

        _state.value = SniperState.Armed(
            voucherUrl = voucherUrl,
            releaseTimeMs = releaseTimeMs,
            serverOffsetMs = serverOffset,
            rttMs = rtt,
            fireAtMs = fireAtLocal
        )

        // Schedule the speculative fire
        scheduler.scheduleAt(fireAtLocal) {
            executeFire(voucherUrl, releaseTimeMs, retryCount = 0)
        }
    }

    private fun executeFire(voucherUrl: String, releaseTimeMs: Long, retryCount: Int) {
        val firedAt = System.currentTimeMillis()
        _state.value = SniperState.Firing(firedAt)

        val clicked = ShopeeAccessibilityService.triggerClaimClick()

        if (!clicked && retryCount < MAX_RETRIES) {
            // AccessibilityService not available yet — retry
            Thread.sleep(RETRY_DELAY_MS)
            executeFire(voucherUrl, releaseTimeMs, retryCount + 1)
            return
        }

        if (!clicked) {
            _state.value = SniperState.Failed(
                reason = "AccessibilityService not connected",
                retryCount = retryCount,
                lastAttemptMs = firedAt
            )
            return
        }

        // Start monitoring for result
        monitorJob = scope.launch {
            monitorResult(releaseTimeMs, firedAt, retryCount, voucherUrl)
        }
    }

    private suspend fun monitorResult(
        releaseTimeMs: Long,
        firedAt: Long,
        retryCount: Int,
        voucherUrl: String
    ) {
        val deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            when (val result = ShopeeAccessibilityService.lastClickResult.value) {
                is ClickResult.Success -> {
                    val latency = result.timestamp - releaseTimeMs
                    _state.value = SniperState.Success(
                        claimedAtMs = result.timestamp,
                        latencyMs = latency
                    )
                    return
                }
                is ClickResult.OutOfStock -> {
                    _state.value = SniperState.OutOfStock(result.timestamp)
                    return
                }
                is ClickResult.Failed -> {
                    if (retryCount < MAX_RETRIES) {
                        delay(RETRY_DELAY_MS)
                        executeFire(voucherUrl, releaseTimeMs, retryCount + 1)
                    } else {
                        _state.value = SniperState.Failed(
                            reason = result.reason,
                            retryCount = retryCount,
                            lastAttemptMs = firedAt
                        )
                    }
                    return
                }
                else -> delay(RESULT_POLL_MS)
            }
        }

        // Timeout — assume failed
        if (retryCount < MAX_RETRIES) {
            executeFire(voucherUrl, releaseTimeMs, retryCount + 1)
        } else {
            _state.value = SniperState.Failed(
                reason = "Timeout waiting for result",
                retryCount = retryCount,
                lastAttemptMs = firedAt
            )
        }
    }

    /**
     * Time remaining until fire (ms). Negative if overdue.
     */
    fun msUntilFire(): Long {
        val armed = _state.value as? SniperState.Armed ?: return -1L
        return armed.fireAtMs - System.currentTimeMillis()
    }

    /**
     * Time remaining until voucher release (ms).
     */
    fun msUntilRelease(): Long {
        return when (val s = _state.value) {
            is SniperState.Scheduled -> s.releaseTimeMs - TimeSync.serverTimeMs()
            is SniperState.Armed -> s.releaseTimeMs - TimeSync.serverTimeMs()
            else -> -1L
        }
    }
}
