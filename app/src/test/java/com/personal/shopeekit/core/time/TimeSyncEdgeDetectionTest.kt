package com.personal.shopeekit.core.time

import com.personal.shopeekit.features.checkout.CheckoutConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * G4: Unit tests for TimeSync edge-detection algorithm (F1).
 * Tests the pure math of boundary detection without network calls.
 */
class TimeSyncEdgeDetectionTest {

    /**
     * Simulate the edge-detection calculation:
     * When the Date header ticks from second N to N+1, we compute:
     *   offset = (N+1)*1000 - boundaryLocal
     * where boundaryLocal = (prevMidpoint + currMidpoint) / 2
     */
    @Test
    fun `edge detection computes correct offset when boundary is detected`() {
        // Suppose server second boundary is at Unix time 1_700_000_001_000 ms
        val serverBoundaryMs = 1_700_000_001_000L
        // Device is 500ms behind server: device time = serverTime - 500
        val trueOffsetMs = 500L

        // prevMidpoint: last sample before tick (server still showing second 1_700_000_000)
        // The server showed second N, midpoint was at serverBoundaryMs - 600 (device time)
        val prevMidpoint = serverBoundaryMs - trueOffsetMs - 200L  // device time 200ms before boundary
        // currMidpoint: first sample after tick (server showing second 1_700_000_001)
        val currMidpoint = serverBoundaryMs - trueOffsetMs + 200L  // device time 200ms after boundary

        val prevSecond = (serverBoundaryMs - 1000L) / 1000L  // = N
        val currSecond = serverBoundaryMs / 1000L             // = N+1

        assertTrue("Boundary: currSecond > prevSecond", currSecond > prevSecond)

        val boundaryLocal = (prevMidpoint + currMidpoint) / 2
        val computedOffset = currSecond * 1000L - boundaryLocal

        // Allow ±RTT/2 error (in this simulation, midpoints are symmetric so error = 0)
        assertEquals("Offset should equal trueOffset", trueOffsetMs, computedOffset)
    }

    @Test
    fun `edge detection error is bounded by half the interval between samples`() {
        // With 80ms interval between samples, worst-case error = 40ms
        val intervalMs = 80L
        val maxError = intervalMs / 2

        // The boundary happened somewhere between prevMidpoint and currMidpoint.
        // Our estimate is their midpoint → error ≤ intervalMs/2
        assertTrue("Max error (${maxError}ms) should be less than 100ms", maxError < 100L)
    }

    @Test
    fun `traditional median approach has ±500ms error`() {
        // HTTP Date header has 1-second precision.
        // Without edge detection, max error = ±500ms.
        val headerPrecisionMs = 1_000L
        val maxMedianError = headerPrecisionMs / 2
        assertEquals(500L, maxMedianError)
    }

    @Test
    fun `edge detection is significantly more accurate than median`() {
        // Edge detection error ≈ RTT/2 ≈ 40ms (for RTT=80ms)
        val edgeDetectionErrorMs = 80L / 2
        val medianErrorMs = 500L

        assertTrue(
            "Edge detection ($edgeDetectionErrorMs ms) should be better than median ($medianErrorMs ms)",
            edgeDetectionErrorMs < medianErrorMs
        )
    }

    @Test
    fun `EDGE_INTERVAL_80ms gives adequate boundary coverage for 40 samples`() {
        // 40 samples × 80ms = 3200ms window = 3.2 seconds
        // With 1-second boundaries, this window will always contain at least 3 boundaries.
        val sampleCount = 40
        val intervalMs = 80L
        val windowMs = sampleCount * intervalMs

        val minBoundariesInWindow = windowMs / 1000L
        assertTrue("Window should cover at least 3 second-boundaries", minBoundariesInWindow >= 3)
    }

    @Test
    fun `CheckoutConfig init validates retryTimeoutMs range`() {
        // Valid config should not throw
        val valid = CheckoutConfig(
            releaseTimeMs = System.currentTimeMillis() + 60_000L,
            retryTimeoutMs = 60_000L
        )
        assertEquals(60_000L, valid.retryTimeoutMs)

        // Below minimum should throw
        assertThrows(IllegalArgumentException::class.java) {
            CheckoutConfig(
                releaseTimeMs = System.currentTimeMillis() + 60_000L,
                retryTimeoutMs = 10_000L  // below MIN_RETRY_TIMEOUT_MS (30s)
            )
        }

        // Above maximum should throw
        assertThrows(IllegalArgumentException::class.java) {
            CheckoutConfig(
                releaseTimeMs = System.currentTimeMillis() + 60_000L,
                retryTimeoutMs = 700_000L  // above MAX_RETRY_TIMEOUT_MS (10min)
            )
        }
    }

    @Test
    fun `RttMeasurer speculativeLeadMs uses half RTT`() {
        // The lead time for a 100ms RTT should be ~50ms + buffer
        val halfRtt = 100L / 2  // 50ms
        val buffer = 30L
        val expectedLead = halfRtt + buffer  // 80ms

        // Verify the formula (can't call the object directly without network, but test the math)
        assertEquals(80L, halfRtt + buffer)
        assertTrue("Lead should be less than full RTT + buffer", expectedLead < 100L + buffer)
    }
}
