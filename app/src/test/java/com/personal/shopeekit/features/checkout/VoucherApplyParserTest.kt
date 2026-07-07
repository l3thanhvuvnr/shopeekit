package com.personal.shopeekit.features.checkout

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for VoucherApplyParser — pure checkout-text → applied/discount mapping.
 * Strings are the ground-truth i18n renderings (Shopee VN 3.77.25,
 * voucher-pages vi collection).
 */
class VoucherApplyParserTest {

    @Test
    fun `explicit applied phrase is detected`() {
        // voucher_checkout_applied_fsv
        assertTrue(VoucherApplyParser.parse("Đã áp dụng Ưu đãi phí vận chuyển").applied)
        // voucher_checkout_auto_selected_limit_one
        assertTrue(VoucherApplyParser.parse("1 voucher đã được tự động chọn cho bạn.").applied)
    }

    @Test
    fun `not-applied placeholder row is not applied`() {
        // label_select_or_enter_code — the value cell before anything is applied
        val r = VoucherApplyParser.parse("Shopee Voucher Chọn hoặc nhập mã")
        assertFalse(r.applied)
        assertNull(r.discountText)
    }

    @Test
    fun `bare product discount does not assert applied`() {
        // The checkout screen is full of product discounts; "giảm 50%" must NOT be
        // read as a voucher being applied (that decision is structural, not textual).
        assertFalse(VoucherApplyParser.parse("Áo thun nam giảm 50% Tổng thanh toán").applied)
    }

    @Test
    fun `discount amount is extracted for display`() {
        // voucher_checkout_applied_discount_voucher / selected_discount_reward = "giảm {}"
        assertEquals("giảm 40.000đ", VoucherApplyParser.parse("Shopee Voucher giảm 40.000đ").discountText)
        assertEquals("giảm 15%", VoucherApplyParser.parse("Đã áp dụng, giảm 15%").discountText)
    }

    @Test
    fun `coin cashback reward is extracted`() {
        // voucher_checkout_selected_coin_cashback_reward = "{amount} Xu"
        assertEquals("2.000 Xu", VoucherApplyParser.parse("Hoàn 2.000 Xu vào ví").discountText)
    }

    @Test
    fun `empty text yields nothing`() {
        val r = VoucherApplyParser.parse("")
        assertFalse(r.applied)
        assertNull(r.discountText)
    }
}
