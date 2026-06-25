package com.personal.shopeekit.core.time

import android.os.Build
import android.os.Process

/**
 * High-precision timer for the checkout snipe.
 *
 * Strategy:
 *  1. Coarse Thread.sleep() until T - 50ms  → saves CPU/battery
 *  2. Busy spin-wait on System.nanoTime()    → ±2ms precision
 *
 * Thread priority: THREAD_PRIORITY_URGENT_AUDIO (highest non-root on Android)
 *
 * Key insight: we fire at (T - rttMs - bufferMs) so the action lands on
 * Shopee's server approximately at T.
 */
class SpeculativeScheduler {

    @Volatile private var cancelled = false
    private var thread: Thread? = null

    /**
     * Schedule a callback to fire at [targetEpochMs] (device local epoch ms).
     * This accounts for server time offset — pass the ADJUSTED fire time.
     *
     * The callback is invoked on a high-priority background thread.
     * Call [cancel] to abort before firing.
     */
    fun scheduleAt(targetEpochMs: Long, onFire: () -> Unit) {
        cancel() // Cancel any existing schedule

        cancelled = false
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            // Phase 1: Coarse sleep until 50ms before target
            val coarseDeadline = targetEpochMs - 50L
            while (!cancelled && System.currentTimeMillis() < coarseDeadline) {
                val remaining = coarseDeadline - System.currentTimeMillis()
                if (remaining > 0) Thread.sleep(minOf(remaining, 10L))
            }

            if (cancelled) return@Thread

            // Phase 2: Busy spin for final 50ms using nanoTime (monotonic, high-resolution)
            // Convert target epoch ms to nanoTime offset:
            // nanoTarget = nanoNow + (targetEpochMs - currentTimeMs) * 1_000_000
            val nanoTarget = System.nanoTime() +
                (targetEpochMs - System.currentTimeMillis()) * 1_000_000L

            while (!cancelled && System.nanoTime() < nanoTarget) {
                // Spin — this is intentional, keeps us on CPU for ~50ms
                // This is the price we pay for ±2ms precision
                // Thread.onSpinWait() is API 33+; on older devices the empty
                // loop body is an equally valid (if un-hinted) busy-spin.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Thread.onSpinWait() // hint JIT: this is a spin loop
                }
            }

            if (!cancelled) {
                onFire()
            }
        }.also { it.start() }
    }

    fun cancel() {
        cancelled = true
        thread?.interrupt()
        thread = null
    }

    fun isScheduled(): Boolean = thread?.isAlive == true && !cancelled
}
