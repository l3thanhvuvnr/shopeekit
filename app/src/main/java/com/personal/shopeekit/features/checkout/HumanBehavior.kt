package com.personal.shopeekit.features.checkout

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Humanises the timing and geometry of the checkout automation so the action
 * stream does not look like a fixed-interval bot.
 *
 * Why this matters: Shopee's SHPSSDK telemetry reports the set of enabled
 * accessibility services to the backend risk engine
 * (`com.shopee.shpssdk/.../wvvuwvwuv.java`). It does not block locally, but a
 * perfectly periodic 50 ms tap loop with pixel-perfect centre taps is exactly
 * the pattern a server-side risk model flags. Spreading delays over a skewed
 * distribution and jittering tap coordinates makes the behaviour resemble a
 * human under time pressure.
 *
 * Pure Kotlin (no Android deps) so the distributions are unit-testable. An
 * injectable [Random] keeps tests deterministic.
 */
object HumanBehavior {

    // Delay between applying the voucher and tapping "Đặt hàng".
    const val APPLY_TO_ORDER_MIN_MS = 60L
    const val APPLY_TO_ORDER_MEDIAN_MS = 140L
    const val APPLY_TO_ORDER_MAX_MS = 320L

    // Delay between retry attempts in the fire loop.
    const val RETRY_MIN_MS = 40L
    const val RETRY_MEDIAN_MS = 90L
    const val RETRY_MAX_MS = 220L

    // Random ± wobble applied to the speculative lead (fire time).
    const val LEAD_JITTER_MS = 6L

    /**
     * A log-normal-ish delay centred on [medianMs], clamped to [minMs, maxMs].
     *
     * Log-normal is right-skewed: most samples sit near the median, with an
     * occasional longer pause — the shape of real human reaction times, and
     * unlike [Random.nextLong] over a range it does not produce a flat
     * histogram that a model can spot.
     */
    fun nextDelayMs(
        minMs: Long,
        medianMs: Long,
        maxMs: Long,
        rng: Random = Random.Default
    ): Long {
        require(minMs in 1..medianMs && medianMs <= maxMs) {
            "Require 1 <= min <= median <= max (min=$minMs median=$medianMs max=$maxMs)"
        }
        // Box-Muller standard normal → scaled in log space around ln(median).
        val u1 = (rng.nextDouble() + 1e-9).coerceAtMost(1.0)
        val u2 = rng.nextDouble()
        val gaussian = kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        val sigma = 0.35 // spread in log space
        val sample = exp(ln(medianMs.toDouble()) + sigma * gaussian)
        return sample.roundToLong().coerceIn(minMs, maxMs)
    }

    /** Delay between voucher-apply and place-order. */
    fun applyToOrderDelayMs(rng: Random = Random.Default): Long =
        nextDelayMs(APPLY_TO_ORDER_MIN_MS, APPLY_TO_ORDER_MEDIAN_MS, APPLY_TO_ORDER_MAX_MS, rng)

    /** Delay between fire-loop retry attempts. */
    fun retryDelayMs(rng: Random = Random.Default): Long =
        nextDelayMs(RETRY_MIN_MS, RETRY_MEDIAN_MS, RETRY_MAX_MS, rng)

    /** Symmetric jitter in [-LEAD_JITTER_MS, +LEAD_JITTER_MS] applied to the fire lead. */
    fun leadJitterMs(rng: Random = Random.Default): Long =
        rng.nextLong(-LEAD_JITTER_MS, LEAD_JITTER_MS + 1)

    /** Gesture stroke duration (ms) — varies so taps aren't all the same length. */
    fun tapDurationMs(rng: Random = Random.Default): Long = rng.nextLong(40, 91)

    /**
     * An off-centre tap point inside the rectangle [left,top]-[right,bottom].
     * Stays within the inner 60% of the box (avoids edges/rounded corners) and
     * never lands on the exact geometric centre.
     *
     * Returns (x, y) as floats.
     */
    fun tapPointIn(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        rng: Random = Random.Default
    ): Pair<Float, Float> {
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        // Inner 60% band: 20%..80% of each dimension.
        val x = left + w * (0.2 + rng.nextDouble() * 0.6)
        val y = top + h * (0.2 + rng.nextDouble() * 0.6)
        return x.toFloat() to y.toFloat()
    }
}
