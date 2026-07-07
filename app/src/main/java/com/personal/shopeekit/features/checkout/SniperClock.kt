package com.personal.shopeekit.features.checkout

import com.personal.shopeekit.core.time.RttMeasurer
import com.personal.shopeekit.core.time.SpeculativeScheduler
import com.personal.shopeekit.core.time.TimeSync

/**
 * Time + scheduling surface [CheckoutSniperEngine] depends on, behind an
 * interface so the engine's timing logic (server-clock gating, fire scheduling,
 * retry window) is unit-testable with a fake clock instead of real network
 * calibration and a wall-clock spin-wait.
 *
 * The snipe is gated on SERVER time, so the engine reads [serverNowMs] for its
 * release-window checks and [nowMs] (device epoch) only for local scheduling.
 */
interface SniperClock {

    /** Device wall-clock epoch ms (`System.currentTimeMillis()`). */
    fun nowMs(): Long

    /** Shopee server epoch ms — device clock + calibrated offset. */
    fun serverNowMs(): Long

    /** True once the offset was computed via edge-detection (sub-second). */
    val isRefined: Boolean

    /**
     * Ensure a fresh calibration, then return the server-minus-local offset ms.
     * Called once at arm time.
     */
    suspend fun calibrate(): Long

    /** Measure round-trip time to the server (ms). */
    suspend fun measureRtt(): Long

    /**
     * Fire [onFire] on a high-priority thread at device-epoch [atMs]. Replaces
     * any pending schedule.
     */
    fun scheduleAt(atMs: Long, onFire: () -> Unit)

    /** Cancel a pending [scheduleAt]. */
    fun cancelSchedule()
}

/**
 * Production [SniperClock] wrapping [TimeSync] (server offset via HTTP Date
 * edge-detection), [RttMeasurer], and a [SpeculativeScheduler] (nanoTime
 * spin-wait). The scheduler is per-instance so [cancelSchedule] is isolated.
 */
class RealSniperClock : SniperClock {

    private val scheduler = SpeculativeScheduler()

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun serverNowMs(): Long = TimeSync.serverTimeMs()

    override val isRefined: Boolean get() = TimeSync.isRefined

    override suspend fun calibrate(): Long {
        // F3: ensure fresh calibration immediately before scheduling.
        TimeSync.ensureFresh()
        return TimeSync.calibrate()
    }

    override suspend fun measureRtt(): Long = RttMeasurer.measure()

    override fun scheduleAt(atMs: Long, onFire: () -> Unit) = scheduler.scheduleAt(atMs, onFire)

    override fun cancelSchedule() = scheduler.cancel()
}
