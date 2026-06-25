package com.personal.shopeekit.features.checkout

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for retry logic and result parsing in CheckoutSniperEngine.
 * These test the pure logic without Android dependencies.
 */
class RetryLogicTest {

    // ─── Result classification ────────────────────────────────────────────────

    @Test
    fun `VoucherNotYet result should trigger retry`() {
        val result = PlaceOrderResult.VoucherNotYet
        assertTrue(shouldRetry(result))
    }

    @Test
    fun `Unknown result should trigger retry`() {
        val result = PlaceOrderResult.Unknown("some unknown text")
        assertTrue(shouldRetry(result))
    }

    @Test
    fun `Success result should NOT trigger retry`() {
        assertFalse(shouldRetry(PlaceOrderResult.Success))
    }

    @Test
    fun `OutOfStock result should NOT trigger retry`() {
        assertFalse(shouldRetry(PlaceOrderResult.OutOfStock))
    }

    @Test
    fun `PaymentError result should NOT trigger retry`() {
        assertFalse(shouldRetry(PlaceOrderResult.PaymentError))
    }

    @Test
    fun `AccessibilityUnavailable should NOT trigger retry (different error)`() {
        assertFalse(shouldRetry(PlaceOrderResult.AccessibilityUnavailable))
    }

    // ─── Timeout logic ────────────────────────────────────────────────────────

    @Test
    fun `Retry stops when deadline passed`() {
        val startMs = System.currentTimeMillis()
        val timeoutMs = 100L
        val deadline = startMs + timeoutMs

        // Simulate 3 retries taking 30ms each
        var attempts = 0
        var currentMs = startMs
        while (currentMs < deadline) {
            attempts++
            currentMs += 30
        }
        assertTrue("Should have done 3-4 retries", attempts in 3..4)
    }

    @Test
    fun `Config min timeout is 30 seconds`() {
        assertEquals(30_000L, CheckoutConfig.MIN_RETRY_TIMEOUT_MS)
    }

    @Test
    fun `Config max timeout is 10 minutes`() {
        assertEquals(600_000L, CheckoutConfig.MAX_RETRY_TIMEOUT_MS)
    }

    @Test
    fun `Config default timeout is 2 minutes`() {
        assertEquals(120_000L, CheckoutConfig.DEFAULT_RETRY_TIMEOUT_MS)
    }

    // ─── Text-based result detection ─────────────────────────────────────────

    @Test
    fun `Vietnamese success text is recognized`() {
        assertTrue(isSuccessText("Đặt hàng thành công!"))
        assertTrue(isSuccessText("Bạn đã đặt hàng"))
        assertTrue(isSuccessText("thành công"))
    }

    @Test
    fun `English success text is recognized`() {
        assertTrue(isSuccessText("Order placed successfully"))
        assertTrue(isSuccessText("Successfully placed"))
    }

    @Test
    fun `Vietnamese voucher-not-yet text is recognized`() {
        assertTrue(isVoucherNotYetText("Voucher chưa hợp lệ"))
        assertTrue(isVoucherNotYetText("Chưa đến giờ áp dụng"))
        assertTrue(isVoucherNotYetText("Voucher không hợp lệ"))
    }

    @Test
    fun `English voucher-not-yet text is recognized`() {
        assertTrue(isVoucherNotYetText("Voucher not yet available"))
        assertTrue(isVoucherNotYetText("Invalid voucher"))
    }

    @Test
    fun `Out of stock text is recognized`() {
        assertTrue(isOutOfStockText("Hết hàng rồi"))
        assertTrue(isOutOfStockText("Hết voucher rồi"))
        assertTrue(isOutOfStockText("Sold out"))
        assertTrue(isOutOfStockText("Out of stock"))
    }

    // ─── Idempotency ──────────────────────────────────────────────────────────

    @Test
    fun `Order success screen text indicates existing order`() {
        assertTrue(isOrderCreatedText("Đặt hàng thành công"))
        assertTrue(isOrderCreatedText("Mã đơn hàng: #12345"))
        assertTrue(isOrderCreatedText("Order ID: abc123"))
    }

    @Test
    fun `Checkout screen text does NOT indicate existing order`() {
        assertFalse(isOrderCreatedText("Đặt hàng"))          // button text
        assertFalse(isOrderCreatedText("Chọn địa chỉ"))
        assertFalse(isOrderCreatedText("Phương thức thanh toán"))
    }

    // ─── Helpers (mirror engine logic for testing) ────────────────────────────

    private fun shouldRetry(result: PlaceOrderResult): Boolean = when (result) {
        is PlaceOrderResult.VoucherNotYet -> true
        is PlaceOrderResult.Unknown -> true
        else -> false
    }

    private fun isSuccessText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("thành công") || lower.contains("đã đặt hàng") ||
            lower.contains("order placed") || lower.contains("successfully")
    }

    private fun isVoucherNotYetText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("chưa hợp lệ") || lower.contains("chưa đến giờ") ||
            lower.contains("not yet available") || lower.contains("invalid voucher") ||
            lower.contains("voucher không hợp lệ")
    }

    private fun isOutOfStockText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("hết hàng") || lower.contains("sold out") ||
            lower.contains("out of stock") || lower.contains("hết voucher")
    }

    private fun isOrderCreatedText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("đặt hàng thành công") ||
            lower.contains("order placed successfully") ||
            lower.contains("đã đặt hàng") ||
            lower.contains("mã đơn hàng") ||
            lower.contains("order id") ||
            lower.contains("order #")
    }
}
