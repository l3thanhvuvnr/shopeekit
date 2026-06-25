package com.personal.shopeekit.features.checkout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tests for [HumanBehavior] — the anti-fraud timing/geometry humaniser.
 * Pure Kotlin, deterministic via a seeded [Random].
 */
class HumanBehaviorTest {

    // ─── Delay distribution ───────────────────────────────────────────────────

    @Test
    fun `applyToOrder delay always within bounds`() {
        val rng = Random(42)
        repeat(2000) {
            val d = HumanBehavior.applyToOrderDelayMs(rng)
            assertTrue(
                "delay $d out of [${HumanBehavior.APPLY_TO_ORDER_MIN_MS}, ${HumanBehavior.APPLY_TO_ORDER_MAX_MS}]",
                d in HumanBehavior.APPLY_TO_ORDER_MIN_MS..HumanBehavior.APPLY_TO_ORDER_MAX_MS
            )
        }
    }

    @Test
    fun `retry delay always within bounds`() {
        val rng = Random(7)
        repeat(2000) {
            val d = HumanBehavior.retryDelayMs(rng)
            assertTrue(d in HumanBehavior.RETRY_MIN_MS..HumanBehavior.RETRY_MAX_MS)
        }
    }

    @Test
    fun `delay is not a fixed value - distribution varies`() {
        val rng = Random(123)
        val samples = (1..200).map { HumanBehavior.retryDelayMs(rng) }.toSet()
        // A bot signature would be a single repeated value; we want spread.
        assertTrue("expected varied delays, got ${samples.size} distinct", samples.size > 20)
    }

    @Test
    fun `delay clusters near the median (right-skewed)`() {
        val rng = Random(999)
        val samples = (1..5000).map { HumanBehavior.retryDelayMs(rng) }
        val median = HumanBehavior.RETRY_MEDIAN_MS
        // Majority should land within a reasonable band around the median,
        // not be uniformly spread across the whole range.
        val nearMedian = samples.count { it in (median - 40)..(median + 60) }
        assertTrue("only $nearMedian/5000 near median", nearMedian > samples.size / 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nextDelayMs rejects invalid bounds`() {
        HumanBehavior.nextDelayMs(minMs = 100, medianMs = 50, maxMs = 200)
    }

    // ─── Lead jitter ──────────────────────────────────────────────────────────

    @Test
    fun `lead jitter stays within symmetric window`() {
        val rng = Random(1)
        repeat(1000) {
            val j = HumanBehavior.leadJitterMs(rng)
            assertTrue(j in -HumanBehavior.LEAD_JITTER_MS..HumanBehavior.LEAD_JITTER_MS)
        }
    }

    @Test
    fun `lead jitter produces both signs`() {
        val rng = Random(2)
        val values = (1..500).map { HumanBehavior.leadJitterMs(rng) }
        assertTrue("expected negative jitter", values.any { it < 0 })
        assertTrue("expected positive jitter", values.any { it > 0 })
    }

    // ─── Tap geometry ─────────────────────────────────────────────────────────

    @Test
    fun `tap point lands inside the node bounds`() {
        val rng = Random(5)
        repeat(1000) {
            val (x, y) = HumanBehavior.tapPointIn(100, 1800, 980, 1920, rng)
            assertTrue("x=$x", x in 100f..980f)
            assertTrue("y=$y", y in 1800f..1920f)
        }
    }

    @Test
    fun `tap point stays within inner band (off-edge)`() {
        val rng = Random(6)
        // Box 0..1000 wide → inner 60% band is 200..800.
        repeat(1000) {
            val (x, _) = HumanBehavior.tapPointIn(0, 0, 1000, 1000, rng)
            assertTrue("x=$x outside inner band", x in 200f..800f)
        }
    }

    @Test
    fun `tap point is not always the exact centre`() {
        val rng = Random(8)
        val centre = 500f
        val xs = (1..200).map { HumanBehavior.tapPointIn(0, 0, 1000, 1000, rng).first }
        assertTrue("all taps hit dead centre", xs.any { it != centre })
    }

    @Test
    fun `tap duration is varied and within range`() {
        val rng = Random(11)
        val durations = (1..200).map { HumanBehavior.tapDurationMs(rng) }
        assertTrue(durations.all { it in 40..90 })
        assertTrue("durations should vary", durations.toSet().size > 10)
    }

    // ─── Determinism with a seed (so production behaviour is reproducible in tests) ─

    @Test
    fun `same seed yields same sequence`() {
        val a = Random(2026)
        val b = Random(2026)
        repeat(50) {
            assertEquals(HumanBehavior.retryDelayMs(a), HumanBehavior.retryDelayMs(b))
        }
    }

    @Test
    fun `different seeds yield different sequences`() {
        val rngA = Random(1)
        val rngB = Random(2)
        val a = (1..50).map { HumanBehavior.retryDelayMs(rngA) }
        val b = (1..50).map { HumanBehavior.retryDelayMs(rngB) }
        assertNotEquals(a, b)
    }
}
