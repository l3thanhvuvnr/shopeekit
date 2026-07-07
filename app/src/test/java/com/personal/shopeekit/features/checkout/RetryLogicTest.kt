package com.personal.shopeekit.features.checkout

import org.junit.Assert.*
import org.junit.Test

/**
 * Config invariants + result-text classification for the checkout flow.
 *
 * Terminal-vs-retry behaviour (the old locally-mirrored `shouldRetry`) is now
 * covered by [CheckoutSniperEngineTest] against the real engine; text→result
 * classification is asserted against the real [OrderResultParser] here so these
 * can't drift from production logic.
 */
class RetryLogicTest {

    // ─── Config invariants ────────────────────────────────────────────────────

    @Test
    fun `Config min timeout is 30 seconds`() {
        assertEquals(30_000L, CheckoutConfig.MIN_RETRY_TIMEOUT_MS)
    }

    @Test
    fun `Config max timeout is 10 minutes`() {
        assertEquals(600_000L, CheckoutConfig.MAX_RETRY_TIMEOUT_MS)
    }

    @Test
    fun `Config default timeout is 30 seconds`() {
        assertEquals(30_000L, CheckoutConfig.DEFAULT_RETRY_TIMEOUT_MS)
    }

    // ─── Result classification (real parser) ──────────────────────────────────

    @Test
    fun `Vietnamese success text is recognized`() {
        assertEquals(PlaceOrderResult.Success, OrderResultParser.parse("Đặt hàng thành công!"))
        assertEquals(PlaceOrderResult.Success, OrderResultParser.parse("Bạn đã đặt hàng"))
    }

    @Test
    fun `English success text is recognized`() {
        assertEquals(PlaceOrderResult.Success, OrderResultParser.parse("Order placed successfully"))
        assertEquals(PlaceOrderResult.Success, OrderResultParser.parse("Successfully placed"))
    }

    @Test
    fun `Vietnamese voucher-not-yet text is recognized`() {
        assertEquals(PlaceOrderResult.VoucherNotYet, OrderResultParser.parse("Voucher chưa hợp lệ"))
        assertEquals(PlaceOrderResult.VoucherNotYet, OrderResultParser.parse("Chưa đến giờ áp dụng"))
        assertEquals(PlaceOrderResult.VoucherNotYet, OrderResultParser.parse("Voucher không hợp lệ"))
    }

    @Test
    fun `English voucher-not-yet text is recognized`() {
        assertEquals(PlaceOrderResult.VoucherNotYet, OrderResultParser.parse("Voucher not yet available"))
        assertEquals(PlaceOrderResult.VoucherNotYet, OrderResultParser.parse("Invalid voucher"))
    }

    @Test
    fun `Out of stock vs voucher exhausted are distinct`() {
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("Hết hàng rồi"))
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("Sold out"))
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("Out of stock"))
        assertEquals(PlaceOrderResult.VoucherExhausted, OrderResultParser.parse("Hết voucher rồi"))
    }
}
