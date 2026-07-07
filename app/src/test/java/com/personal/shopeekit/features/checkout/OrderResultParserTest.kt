package com.personal.shopeekit.features.checkout

import org.junit.Assert.*
import org.junit.Test

/**
 * G4: Tests for OrderResultParser — pure text → PlaceOrderResult mapping.
 */
class OrderResultParserTest {

    @Test
    fun `Vietnamese success text returns Success`() {
        assertEquals(PlaceOrderResult.Success, OrderResultParser.parse("Đặt hàng thành công!"))
        assertEquals(PlaceOrderResult.Success, OrderResultParser.parse("Bạn đã đặt hàng"))
    }

    @Test
    fun `English success text returns Success`() {
        assertEquals(PlaceOrderResult.Success, OrderResultParser.parse("Order placed successfully"))
        assertEquals(PlaceOrderResult.Success, OrderResultParser.parse("Successfully placed"))
    }

    @Test
    fun `Vietnamese voucher-not-yet returns VoucherNotYet`() {
        assertEquals(PlaceOrderResult.VoucherNotYet, OrderResultParser.parse("Voucher chưa hợp lệ"))
        assertEquals(PlaceOrderResult.VoucherNotYet, OrderResultParser.parse("Chưa đến giờ áp dụng"))
        assertEquals(PlaceOrderResult.VoucherNotYet, OrderResultParser.parse("Voucher không hợp lệ"))
    }

    @Test
    fun `Out of stock text returns OutOfStock`() {
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("Hết hàng rồi"))
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("Sold out"))
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("Out of stock"))
    }

    @Test
    fun `voucher exhausted is distinct from product out-of-stock`() {
        // "hết voucher"/"hết lượt"/"đã lưu hết" = voucher all claimed (item still
        // buyable), NOT a sold-out product. Must NOT collapse into OutOfStock.
        assertEquals(PlaceOrderResult.VoucherExhausted, OrderResultParser.parse("Hết voucher rồi"))
        assertEquals(PlaceOrderResult.VoucherExhausted, OrderResultParser.parse("Voucher đã hết lượt sử dụng"))
        assertEquals(PlaceOrderResult.VoucherExhausted, OrderResultParser.parse("Đã lưu hết"))
        // product OOS stays OutOfStock even though both mention "hết"
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("Sản phẩm tạm hết hàng"))
    }

    @Test
    fun `Payment error text returns PaymentError`() {
        assertEquals(PlaceOrderResult.PaymentError, OrderResultParser.parse("Lỗi thanh toán"))
        assertEquals(PlaceOrderResult.PaymentError, OrderResultParser.parse("Payment failed"))
    }

    @Test
    fun `PIN prompt text returns RequiresPin before checking success`() {
        // PIN must be checked BEFORE success to avoid misclassifying "thành công" on same screen
        val result = OrderResultParser.parse("Nhập mã PIN để xác thực thanh toán thành công")
        assertTrue("Expected RequiresPin but got $result", result is PlaceOrderResult.RequiresPin)
    }

    @Test
    fun `OTP prompt returns RequiresPin`() {
        val result = OrderResultParser.parse("Nhập OTP Mã OTP đã được gửi đến")
        assertTrue(result is PlaceOrderResult.RequiresPin)
    }

    @Test
    fun `ShopeePay PIN prompt returns RequiresPin`() {
        val result = OrderResultParser.parse("ShopeePay PIN")
        assertTrue(result is PlaceOrderResult.RequiresPin)
    }

    @Test
    fun `Empty text returns Unknown`() {
        val result = OrderResultParser.parse("")
        assertTrue(result is PlaceOrderResult.Unknown)
    }

    @Test
    fun `Checkout screen text returns Unknown not Success`() {
        // "Đặt hàng" button text should not match success
        val result = OrderResultParser.parse("Đặt hàng Chọn địa chỉ giao hàng Tổng thanh toán 100.000đ")
        // Does NOT contain "thành công" / "đã đặt hàng" → Unknown
        assertTrue("Expected Unknown but got $result", result is PlaceOrderResult.Unknown)
    }

    @Test
    fun `Case insensitive matching works`() {
        assertEquals(PlaceOrderResult.Success, OrderResultParser.parse("THÀNH CÔNG"))
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("HẾT HÀNG"))
    }

    @Test
    fun `negated success is PaymentError not Success`() {
        // Ground truth (i18n): failure strings that contain "thành công" must not
        // be read as a placed order. label_pay_in_advance_error_toast etc.
        assertEquals(
            PlaceOrderResult.PaymentError,
            OrderResultParser.parse("Thanh toán chưa thành công. Vui lòng thử cách khác")
        )
        assertEquals(
            PlaceOrderResult.PaymentError,
            OrderResultParser.parse("Giao hàng không thành công")
        )
        assertTrue(OrderResultParser.parse("Giao dịch thất bại") is PlaceOrderResult.PaymentError)
    }

    @Test
    fun `temporarily out of stock is detected`() {
        // i18n: opc_item_oos = "Sản phẩm tạm hết hàng", opc_item_oos_banner
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("Sản phẩm tạm hết hàng"))
        assertEquals(PlaceOrderResult.OutOfStock, OrderResultParser.parse("Phân loại này tạm hết hàng"))
    }
}
