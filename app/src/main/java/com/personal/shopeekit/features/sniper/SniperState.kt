package com.personal.shopeekit.features.sniper

/**
 * State machine for VoucherSniper.
 * Used as StateFlow value in SniperEngine.
 */
sealed class SniperState {
    /** No snipe scheduled. */
    object Idle : SniperState()

    /** Snipe is scheduled but calibration not yet done. */
    data class Scheduled(
        val voucherUrl: String,
        val releaseTimeMs: Long,
        val voucherLabel: String = ""
    ) : SniperState()

    /** Calibration done, spin-wait thread is running. */
    data class Armed(
        val voucherUrl: String,
        val releaseTimeMs: Long,
        val serverOffsetMs: Long,
        val rttMs: Long,
        val fireAtMs: Long  // local epoch ms when we will fire
    ) : SniperState()

    /** Click dispatched to AccessibilityService. */
    data class Firing(val firedAtMs: Long) : SniperState()

    /** Claim succeeded (detected from Shopee UI feedback). */
    data class Success(
        val claimedAtMs: Long,
        val latencyMs: Long  // ms from releaseTime to claim
    ) : SniperState()

    /** All retries exhausted. */
    data class Failed(
        val reason: String,
        val retryCount: Int,
        val lastAttemptMs: Long
    ) : SniperState()

    /** Voucher out of stock — nothing to retry. */
    data class OutOfStock(val detectedAtMs: Long) : SniperState()

    /** Auth token expired — user must re-capture via mitmproxy. */
    object TokenExpired : SniperState()
}
