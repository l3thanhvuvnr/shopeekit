package com.personal.shopeekit.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the scored matching that replaced the old
 * substring-first-match discovery (the source of wrong-button taps).
 */
class UiMatchTest {

    // ─── normalize ────────────────────────────────────────────────────────────

    @Test fun `normalize strips Vietnamese diacritics and case`() {
        assertEquals("dat hang", UiMatch.normalize("Đặt Hàng"))
        assertEquals("dat hang", UiMatch.normalize("ĐẶT HÀNG"))
        assertEquals("ap dung", UiMatch.normalize("Áp dụng"))
        assertEquals("dung ngay", UiMatch.normalize("Dùng ngay"))
    }

    @Test fun `normalize collapses whitespace and handles null`() {
        assertEquals("dat hang", UiMatch.normalize("  Đặt   hàng  "))
        assertEquals("", UiMatch.normalize(null))
        assertEquals("", UiMatch.normalize("   "))
    }

    // ─── textScore: the core fix ──────────────────────────────────────────────

    @Test fun `exact label match scores 1`() {
        assertEquals(1.0, UiMatch.textScore("Đặt hàng", "Đặt hàng"), 0.0001)
        assertEquals(1.0, UiMatch.textScore("OK", "OK"), 0.0001)
        assertEquals(1.0, UiMatch.textScore("Áp dụng", "Áp dụng"), 0.0001)
    }

    @Test fun `order success text does not clear the place-order threshold`() {
        // "Đặt hàng thành công" must NOT be treated as the "Đặt hàng" button.
        val s = UiMatch.textScore("Đặt hàng thành công", "Đặt hàng")
        assertTrue("score was $s", s < UiMatch.TEXT_ACCEPT_THRESHOLD)
    }

    @Test fun `generic OK label does not match unrelated words`() {
        // The classic false positive: substring "ok" inside "Book"/"Tokopedia".
        assertTrue(UiMatch.textScore("Book", "OK") < UiMatch.TEXT_ACCEPT_THRESHOLD)
        assertTrue(UiMatch.textScore("Tokopedia", "OK") < UiMatch.TEXT_ACCEPT_THRESHOLD)
    }

    @Test fun `bare Voucher label does not match a long voucher sentence strongly`() {
        val s = UiMatch.textScore("Lấy Voucher để nhận giảm giá vận chuyển", "Voucher")
        assertTrue("score was $s", s < UiMatch.TEXT_ACCEPT_THRESHOLD)
    }

    @Test fun `a button whose text is the label wins`() {
        assertTrue(UiMatch.bestTextScore("Đặt hàng", listOf("Đặt hàng", "Place Order")) >= 0.99)
        assertTrue(UiMatch.bestTextScore("Place order", listOf("Đặt hàng", "Place Order")) >= 0.99)
    }

    // ─── negatives ────────────────────────────────────────────────────────────

    @Test fun `hasNegative vetoes false positives diacritic-insensitively`() {
        assertTrue(UiMatch.hasNegative("Đặt hàng thành công", listOf("thành công")))
        assertTrue(UiMatch.hasNegative("dat hang thanh cong", listOf("thành công")))
        assertFalse(UiMatch.hasNegative("Đặt hàng", listOf("thành công")))
    }

    // ─── positionScore ────────────────────────────────────────────────────────

    @Test fun `identical position scores 1, far position scores low`() {
        assertEquals(1.0, UiMatch.positionScore(0.5f, 0.9f, 0.9f, 0.07f, 0.5f, 0.9f, 0.9f, 0.07f), 0.0001)
        assertTrue(UiMatch.positionScore(0.1f, 0.1f, 0.2f, 0.05f, 0.5f, 0.9f, 0.9f, 0.07f) < 0.5)
    }

    // ─── buttonShapeScore ─────────────────────────────────────────────────────

    @Test fun `a tall container scores lower than a wide short bar`() {
        val container = UiMatch.buttonShapeScore(0.9f, 0.5f)   // full-height → height contributes 0
        val bar = UiMatch.buttonShapeScore(0.9f, 0.07f)        // typical CTA bar
        assertTrue("container=$container", container <= 0.45)
        assertTrue("bar=$bar", bar > 0.8)
        assertTrue(bar > container)
    }

    @Test fun `zero-size node is not button-shaped`() {
        assertEquals(0.0, UiMatch.buttonShapeScore(0f, 0f), 0.0001)
    }

    // ─── ground truth (Shopee VN 3.77.25 bundled i18n) ────────────────────────
    // These lock in the authoritative labels extracted from
    // strings/@shopee-rn/{checkout,voucher-pages}/i18n/*vi*.json so a Shopee
    // rename is caught by a failing test, not a silent wrong tap.

    @Test fun `platform voucher matches exactly, shop voucher does not`() {
        // label_opc_platform_voucher = "Shopee Voucher" (target)
        assertEquals(1.0, UiMatch.textScore("Shopee Voucher", "Shopee Voucher"), 0.0001)
        // label_opc_shop_voucher = "Voucher của Shop" (must NOT match the platform label)
        assertTrue(UiMatch.textScore("Voucher của Shop", "Shopee Voucher") < UiMatch.TEXT_ACCEPT_THRESHOLD)
        // and the shop row is explicitly vetoed
        assertTrue(UiMatch.hasNegative("Voucher của Shop", listOf("của shop", "shop voucher")))
        assertFalse(UiMatch.hasNegative("Shopee Voucher", listOf("của shop", "shop voucher")))
    }

    @Test fun `place-order matches uppercase i18n form, Mua sau is not place-order`() {
        // label_opc_place_order renders "ĐẶT HÀNG" (uppercase) — normalize makes it equal
        assertEquals(1.0, UiMatch.textScore("ĐẶT HÀNG", "Đặt hàng"), 0.0001)
        // label_opc_cancel_place_order = "Mua sau" (cancel) — never the place-order button
        assertEquals(0.0, UiMatch.textScore("Mua sau", "Đặt hàng"), 0.0001)
        assertTrue(UiMatch.hasNegative("Mua sau", listOf("mua sau")))
    }

    @Test fun `free-shipping hint mentioning Shopee Voucher stays below threshold`() {
        // msg_use_free_shipping_voucher = "Đừng bỏ lỡ mã Freeship ở mục Shopee Voucher"
        val s = UiMatch.textScore("Đừng bỏ lỡ mã Freeship ở mục Shopee Voucher", "Shopee Voucher")
        assertTrue("hint scored $s, should be < threshold", s < UiMatch.TEXT_ACCEPT_THRESHOLD)
    }

    // ─── row-label matching (label + value concatenated in one a11y node) ─────

    @Test fun `concatenated voucher row dilutes textScore below threshold`() {
        // The real bug: with a voucher applied, the row's a11y text is
        // "Shopee Voucher Miễn Phí Vận Chuyển", whose coverage-weighted score is
        // ~0.68 < 0.70 — which is why plain text matching never tapped the row.
        val s = UiMatch.textScore("Shopee Voucher Miễn Phí Vận Chuyển", "Shopee Voucher")
        assertTrue("row scored $s", s < UiMatch.TEXT_ACCEPT_THRESHOLD)
    }

    @Test fun `startsWithPhrase matches the row label prefix but not a trailing mention`() {
        // Row: label is the prefix (applied and not-yet-applied value cells).
        assertTrue(UiMatch.startsWithPhrase("Shopee Voucher Miễn Phí Vận Chuyển", "Shopee Voucher"))
        assertTrue(UiMatch.startsWithPhrase("Shopee Voucher Chọn hoặc nhập mã", "Shopee Voucher"))
        assertTrue(UiMatch.startsWithPhrase("SHOPEE VOUCHER", "Shopee Voucher"))
        // Hint banner mentions it at the END → must NOT be treated as the row.
        assertFalse(UiMatch.startsWithPhrase("Đừng bỏ lỡ mã Freeship ở mục Shopee Voucher", "Shopee Voucher"))
        // Shop row is a different label.
        assertFalse(UiMatch.startsWithPhrase("Voucher của Shop Chọn hoặc nhập mã", "Shopee Voucher"))
        // No partial-word false positive.
        assertFalse(UiMatch.startsWithPhrase("Shopee Voucherz", "Shopee Voucher"))
    }

    @Test fun `startsWithAny matches when any label is a prefix`() {
        assertTrue(UiMatch.startsWithAny("Shopee Voucher Miễn Phí Vận Chuyển", listOf("Shopee Voucher")))
        assertFalse(UiMatch.startsWithAny("Voucher của Shop", listOf("Shopee Voucher")))
    }
}
