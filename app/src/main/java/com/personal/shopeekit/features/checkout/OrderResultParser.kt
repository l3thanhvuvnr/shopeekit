package com.personal.shopeekit.features.checkout

import com.personal.shopeekit.core.logging.KitLogger

/**
 * G1: Pure text → PlaceOrderResult parser.
 * Extracted from ShopeeAccessibilityService so it can be unit-tested without Android.
 */
object OrderResultParser {

    fun parse(screenText: String, log: Boolean = true): PlaceOrderResult {
        val text = screenText.lowercase()
        val result = parseInternal(text)
        if (log) KitLogger.d("ORP", "parse → $result input='${text.take(60)}'")
        return result
    }

    private fun parseInternal(text: String): PlaceOrderResult {
        // Ground truth: Shopee VN 3.77.25 has many *failure* strings that contain
        // the word "thành công" — e.g. "Thanh toán chưa thành công", "Giao hàng
        // không thành công". A bare contains("thành công") would report Success on
        // a FAILED payment, so detect negated-success first and never let it reach
        // the success branch. (i18n: label_pay_in_advance_error_toast etc.)
        val negatedSuccess = text.contains("không thành công") || text.contains("chưa thành công")

        return when {
            // PIN / OTP prompt — check before everything to avoid misclassification
            text.contains("nhập mã pin") || text.contains("mã pin") ||
            text.contains("nhập otp") || text.contains("mã otp") ||
            text.contains("shopeepay pin") || text.contains("xác thực thanh toán") ||
            text.contains("enter pin") || text.contains("payment pin") ->
                PlaceOrderResult.RequiresPin(text.take(80))

            // Voucher exhausted / all claimed — the ITEM is still buyable, so keep this
            // distinct from a sold-out product (i18n: vlp_alert_voucher_invalid_fully_claimed
            // "đã hết lượt sử dụng", label_flash_voucher_claimed "Đã lưu hết"). Checked
            // before product OOS so "hết voucher" doesn't fall into "hết hàng".
            text.contains("hết voucher") || text.contains("hết lượt") ||
            text.contains("đã lưu hết") ->
                PlaceOrderResult.VoucherExhausted

            // Out of stock (i18n: opc_item_oos "Sản phẩm tạm hết hàng", error_buy_out_of_stock)
            text.contains("hết hàng") || text.contains("sold out") ||
            text.contains("out of stock") ->
                PlaceOrderResult.OutOfStock

            // Explicit failure — BEFORE success, so negated "thành công" & "thất bại"
            // are never read as a placed order.
            negatedSuccess || text.contains("thất bại") ||
            text.contains("lỗi thanh toán") || text.contains("payment failed") ||
            text.contains("payment error") ->
                PlaceOrderResult.PaymentError

            // Voucher not yet valid
            text.contains("chưa hợp lệ") || text.contains("chưa đến giờ") ||
            text.contains("not yet available") || text.contains("invalid voucher") ||
            text.contains("voucher không hợp lệ") ->
                PlaceOrderResult.VoucherNotYet

            // Success — safe now that negated-success routed to PaymentError above.
            text.contains("thành công") || text.contains("đã đặt hàng") ||
            text.contains("order placed") || text.contains("successfully") ||
            text.contains("mã đơn hàng") ->
                PlaceOrderResult.Success

            else -> PlaceOrderResult.Unknown(text.take(100))
        }
    }
}
