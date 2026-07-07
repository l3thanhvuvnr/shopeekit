package com.personal.shopeekit.features.checkout

/**
 * Pure text → "was a voucher applied?" parser for the checkout screen, extracted
 * so it can be unit-tested without Android.
 *
 * Ground truth (Shopee VN 3.77.25 bundled i18n, voucher-pages vi collection):
 * once a platform voucher is applied, the checkout "Shopee Voucher" row shows
 * one of:
 *   - voucher_checkout_applied_fsv          = "Đã áp dụng Ưu đãi phí vận chuyển"
 *   - voucher_checkout_applied_discount_voucher / selected_discount_reward = "giảm {amount}"
 *   - voucher_checkout_auto_selected_limit_one = "1 voucher đã được tự động chọn cho bạn."
 *   - voucher_checkout_selected_coin_cashback_reward = "{amount} Xu"
 * Before applying, the row's value cell is label_select_or_enter_code =
 * "Chọn hoặc nhập mã" (an explicit not-applied marker).
 */
object VoucherApplyParser {

    data class Result(
        /** Strong positive signal that a voucher is currently applied. */
        val applied: Boolean,
        /** Human-readable discount/reward text for display, if one was found. */
        val discountText: String?
    )

    // "giảm 40.000đ", "giảm 40.000₫", "giảm 15%"
    private val discountAmount =
        Regex("""giảm\s+[\d.,]+\s*(?:đ|₫|%|k)""", RegexOption.IGNORE_CASE)
    // "-₫40.000", "-40.000đ"
    private val minusAmount =
        Regex("""-\s*[₫đ]?\s*[\d.,]+\s*[₫đk]?""", RegexOption.IGNORE_CASE)
    // "1.234 Xu" (coin cashback reward)
    private val coinReward =
        Regex("""[\d.,]+\s*xu""", RegexOption.IGNORE_CASE)

    fun parse(checkoutText: String): Result {
        val text = checkoutText.lowercase()

        // "applied" is asserted ONLY on an explicit Shopee applied-voucher phrase.
        // We deliberately do NOT infer it from a "giảm {amount}" token: the checkout
        // screen is full of ordinary product discounts ("giảm 50%"), so that would
        // false-positive. The engine relies on the structural signal (drawer closed
        // after confirm) to decide success; this flag is a stronger corroboration.
        val applied = text.contains("đã áp dụng") ||
            text.contains("tự động chọn") ||
            text.contains("voucher applied")

        // Best-effort display text only (may pick up a product discount if no
        // voucher reward is present — it never gates the flow).
        val discount = discountAmount.find(checkoutText)?.value?.trim()
            ?: coinReward.find(checkoutText)?.value?.trim()
            ?: minusAmount.find(checkoutText)?.value?.trim()

        return Result(applied = applied, discountText = discount)
    }
}
