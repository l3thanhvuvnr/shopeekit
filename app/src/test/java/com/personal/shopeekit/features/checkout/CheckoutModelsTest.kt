package com.personal.shopeekit.features.checkout

import org.junit.Assert.*
import org.junit.Test

class CheckoutModelsTest {

    // ─── CheckoutConfig ───────────────────────────────────────────────────────

    @Test
    fun `CheckoutConfig default retry timeout is 30 seconds`() {
        val config = CheckoutConfig(releaseTimeMs = System.currentTimeMillis() + 60_000L)
        assertEquals(30_000L, config.retryTimeoutMs)
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
        val config = CheckoutConfig(releaseTimeMs = 1L)
        assertTrue(config.voucherPreference is VoucherPreference.AutoBest)
    }

    @Test
    fun `CheckoutConfig rejects a non-positive releaseTimeMs`() {
        // A zero/negative release instant is a construction bug (uninitialised / bad
        // parse) that would make the fire window close before it opens.
        assertThrows(IllegalArgumentException::class.java) {
            CheckoutConfig(releaseTimeMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CheckoutConfig(releaseTimeMs = -1L)
        }
    }

    // ─── SnipeMode: the 2-step / 3-step safety gate ───────────────────────────

    @Test
    fun `CheckoutConfig defaults to the safe 2-step VOUCHER_ONLY mode`() {
        // Critical: an accidental arm must never place a real order.
        val config = CheckoutConfig(releaseTimeMs = 1L)
        assertEquals(SnipeMode.VOUCHER_ONLY, config.mode)
    }

    @Test
    fun `CheckoutConfig accepts the live 3-step FULL_CHECKOUT mode`() {
        val config = CheckoutConfig(releaseTimeMs = 1L, mode = SnipeMode.FULL_CHECKOUT)
        assertEquals(SnipeMode.FULL_CHECKOUT, config.mode)
    }

    // ─── VoucherApplyResult ───────────────────────────────────────────────────

    @Test
    fun `VoucherApplyResult Applied carries label and discount`() {
        val r = VoucherApplyResult.Applied(voucherLabel = "Tự động (tốt nhất)", discountText = "giảm 40.000đ")
        assertEquals("Tự động (tốt nhất)", r.voucherLabel)
        assertEquals("giảm 40.000đ", r.discountText)
    }

    @Test
    fun `VoucherApplied state stores label discount and attempts`() {
        val state = CheckoutSniperState.VoucherApplied(
            voucherLabel = "Shopee Voucher",
            discountText = "giảm 30.000đ",
            attemptCount = 2
        )
        assertEquals("Shopee Voucher", state.voucherLabel)
        assertEquals("giảm 30.000đ", state.discountText)
        assertEquals(2, state.attemptCount)
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
