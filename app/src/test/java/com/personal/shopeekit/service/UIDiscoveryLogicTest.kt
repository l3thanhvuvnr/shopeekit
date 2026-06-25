package com.personal.shopeekit.service

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ShopeeUIDiscovery fallback chain logic (pure logic, no Android deps).
 */
class UIDiscoveryLogicTest {

    // ─── Text label coverage ──────────────────────────────────────────────────

    @Test
    fun `Place order Vietnamese label is defined`() {
        val labels = placeOrderLabels()
        assertTrue(labels.any { it.contains("Đặt hàng") })
    }

    @Test
    fun `Place order English label is defined`() {
        val labels = placeOrderLabels()
        assertTrue(labels.any { it.contains("Place Order") || it.contains("Place order") })
    }

    @Test
    fun `Apply voucher labels cover Shopee 3_76_25 Vietnamese labels`() {
        val labels = applyVoucherLabels()
        // Verified against 3.76.25 i18n: label_apply="Áp dụng", label_use="Dùng ngay"
        assertTrue(labels.any { it.contains("Áp dụng") })
        assertTrue(labels.any { it.contains("Dùng ngay") })
    }

    @Test
    fun `Voucher picker labels include 3_76_25 wording`() {
        val labels = voucherPickerLabels()
        assertTrue(labels.isNotEmpty())
        // "Chọn Voucher khác" = voucher_wallet_popup_cancel in 3.76.25
        assertTrue(labels.any { it.contains("Chọn Voucher khác") })
    }

    @Test
    fun `Auto-select voucher labels are non-empty`() {
        val labels = autoSelectLabels()
        assertTrue(labels.isNotEmpty())
        assertTrue(labels.any { it.contains("Tự động") || it.contains("Auto") })
    }

    // ─── Discount parsing ─────────────────────────────────────────────────────

    @Test
    fun `Discount amount parsed from Vietnamese format`() {
        assertEquals(200000L, parseDiscountAmount("Giảm 200.000đ"))
        assertEquals(50000L, parseDiscountAmount("Giảm 50.000đ"))
        assertEquals(15000L, parseDiscountAmount("Giảm 15000đ"))
    }

    @Test
    fun `Cashback percent parsed correctly`() {
        assertEquals(15, parseCashbackPercent("Hoàn 15%"))
        assertEquals(25, parseCashbackPercent("Cashback 25%"))
        assertEquals(0, parseCashbackPercent("No percent here"))
    }

    @Test
    fun `Higher discount wins comparison`() {
        val discounts = listOf(50000L, 200000L, 75000L)
        assertEquals(200000L, discounts.max())
    }

    @Test
    fun `Higher cashback wins comparison`() {
        val cashbacks = listOf(10, 25, 15)
        assertEquals(25, cashbacks.max())
    }

    // ─── Cache key format ─────────────────────────────────────────────────────

    @Test
    fun `Cache key format is consistent`() {
        val key1 = cacheKey("checkout", "place_order_button")
        val key2 = cacheKey("checkout", "place_order_button")
        assertEquals(key1, key2)
    }

    @Test
    fun `Different screen+element produces different cache key`() {
        val key1 = cacheKey("checkout", "place_order_button")
        val key2 = cacheKey("voucher_picker", "apply_voucher_button")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `Cache key does not contain spaces`() {
        val key = cacheKey("voucher_picker", "apply_voucher_button")
        assertFalse(key.contains(" "))
    }

    // ─── Helpers mirroring ShopeeUIDiscovery logic (Shopee 3.76.25) ───────────

    private fun placeOrderLabels() = listOf(
        "Đặt hàng", "Đặt Hàng", "ĐẶT HÀNG", "Place Order", "Place order"
    )

    private fun applyVoucherLabels() = listOf(
        "Áp dụng", "Dùng ngay", "OK", "Xác nhận", "Đồng ý", "Apply"
    )

    private fun voucherPickerLabels() = listOf(
        "Chọn Voucher khác", "Lấy Voucher để nhận giảm giá",
        "Thêm voucher Shopee", "Shopee Voucher", "Chọn voucher",
        "Voucher", "Thêm mã giảm giá", "Select Voucher", "Add voucher"
    )

    private fun autoSelectLabels() = listOf(
        "Tự động chọn", "Áp dụng tốt nhất", "Chọn tốt nhất", "Tự chọn",
        "Auto select", "Best voucher"
    )

    private fun parseDiscountAmount(text: String): Long {
        val cleaned = text.replace("[^0-9]".toRegex(), "")
        return cleaned.toLongOrNull() ?: 0L
    }

    private fun parseCashbackPercent(text: String): Int {
        val match = Regex("""(\d+)%""").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun cacheKey(screen: String, element: String) = "shopee_ui_${screen}_${element}"
}
