package com.personal.shopeekit.features.checkout

/**
 * G1: Pure text → PlaceOrderResult parser.
 * Extracted from ShopeeAccessibilityService so it can be unit-tested without Android.
 */
object OrderResultParser {

    fun parse(screenText: String): PlaceOrderResult {
        val text = screenText.lowercase()

        return when {
            // PIN / OTP prompt — check before success to avoid misclassification
            text.contains("nhập mã pin") || text.contains("mã pin") ||
            text.contains("nhập otp") || text.contains("mã otp") ||
            text.contains("shopeepay pin") || text.contains("xác thực thanh toán") ||
            text.contains("enter pin") || text.contains("payment pin") ->
                PlaceOrderResult.RequiresPin(text.take(80))

            // Success
            text.contains("thành công") || text.contains("đã đặt hàng") ||
            text.contains("order placed") || text.contains("successfully") ->
                PlaceOrderResult.Success

            // Voucher not yet valid
            text.contains("chưa hợp lệ") || text.contains("chưa đến giờ") ||
            text.contains("not yet available") || text.contains("invalid voucher") ||
            text.contains("voucher không hợp lệ") ->
                PlaceOrderResult.VoucherNotYet

            // Out of stock
            text.contains("hết hàng") || text.contains("sold out") ||
            text.contains("out of stock") || text.contains("hết voucher") ->
                PlaceOrderResult.OutOfStock

            // Payment error
            text.contains("lỗi thanh toán") || text.contains("payment failed") ||
            text.contains("payment error") ->
                PlaceOrderResult.PaymentError

            else -> PlaceOrderResult.Unknown(text.take(100))
        }
    }
}
