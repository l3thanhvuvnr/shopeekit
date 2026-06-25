package com.personal.shopeekit.features.checkout

import org.junit.Assert.*
import org.junit.Test

class CheckoutModelsTest {

    // ─── CheckoutConfig ───────────────────────────────────────────────────────

    @Test
    fun `CheckoutConfig default retry timeout is 2 minutes`() {
        val config = CheckoutConfig(releaseTimeMs = System.currentTimeMillis() + 60_000L)
        assertEquals(120_000L, config.retryTimeoutMs)
    }

    @Test
    fun `CheckoutConfig allows custom retry timeout within bounds`() {
        val config = CheckoutConfig(
            releaseTimeMs = System.currentTimeMillis() + 60_000L,
            retryTimeoutMs = 300_000L
        )
        assertEquals(300_000L, config.retryTimeoutMs)
    }

    @Test
    fun `CheckoutConfig default voucher preference is AutoBest`() {
        val config = CheckoutConfig(releaseTimeMs = 0L)
        assertTrue(config.voucherPreference is VoucherPreference.AutoBest)
    }

    // ─── VoucherPreference ────────────────────────────────────────────────────

    @Test
    fun `ManualCode stores code correctly`() {
        val pref = VoucherPreference.ManualCode("SHOP25K")
        assertEquals("SHOP25K", pref.code)
    }

    @Test
    fun `VoucherPreference types are distinct`() {
        val prefs = listOf(
            VoucherPreference.AutoBest,
            VoucherPreference.MaxDiscount,
            VoucherPreference.MaxCashback,
            VoucherPreference.ManualCode("X")
        )
        // Each is different type
        assertEquals(4, prefs.map { it::class }.toSet().size)
    }

    // ─── PlaceOrderResult ─────────────────────────────────────────────────────

    @Test
    fun `PlaceOrderResult VoucherNotYet signals retry`() {
        val result = PlaceOrderResult.VoucherNotYet
        assertTrue(result is PlaceOrderResult.VoucherNotYet)
    }

    @Test
    fun `PlaceOrderResult Success signals done`() {
        val result = PlaceOrderResult.Success
        assertTrue(result is PlaceOrderResult.Success)
    }

    @Test
    fun `PlaceOrderResult Unknown carries message`() {
        val result = PlaceOrderResult.Unknown("unexpected response")
        assertEquals("unexpected response", result.message)
    }

    // ─── CheckoutSniperState ──────────────────────────────────────────────────

    @Test
    fun `Success state stores latency and voucher`() {
        val state = CheckoutSniperState.Success(
            latencyMs = 12L,
            appliedVoucher = "SHOP25K",
            savedAmount = 25000L
        )
        assertEquals(12L, state.latencyMs)
        assertEquals("SHOP25K", state.appliedVoucher)
        assertEquals(25000L, state.savedAmount)
        assertFalse(state.detectedExisting)
    }

    @Test
    fun `Success state with idempotency flag`() {
        val state = CheckoutSniperState.Success(
            latencyMs = 0L,
            appliedVoucher = null,
            detectedExisting = true
        )
        assertTrue(state.detectedExisting)
    }

    @Test
    fun `Failed state stores attempt count`() {
        val state = CheckoutSniperState.Failed("Timeout", attemptCount = 42)
        assertEquals(42, state.attemptCount)
        assertEquals("Timeout", state.reason)
    }

    @Test
    fun `RetryLoop state tracks attempt and error`() {
        val state = CheckoutSniperState.RetryLoop(
            attemptCount = 5,
            nextRetryMs = 1000L,
            lastError = "Voucher chưa hợp lệ"
        )
        assertEquals(5, state.attemptCount)
        assertEquals("Voucher chưa hợp lệ", state.lastError)
    }
}
