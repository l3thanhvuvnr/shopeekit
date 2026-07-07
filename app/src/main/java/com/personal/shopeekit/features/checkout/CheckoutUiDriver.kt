package com.personal.shopeekit.features.checkout

import com.personal.shopeekit.service.ShopeeAccessibilityService

/**
 * The set of on-device UI actions [CheckoutSniperEngine] needs to drive the
 * snipe. Extracted as an interface so the engine's state machine can be unit
 * tested on the JVM with a fake driver — the real one goes through Shopee's own
 * UI via [ShopeeAccessibilityService], which needs a device.
 *
 * The two heavy actions ([applyBestVoucher], [clickPlaceOrder]) are `suspend`:
 * they poll the a11y tree for UI transitions, so making them suspend lets the
 * engine cancel them the instant `disarm()` runs (cooperative cancellation at
 * each poll), instead of only at the engine's own delay boundaries.
 */
interface CheckoutUiDriver {

    /** True if the current screen is Shopee's checkout (guard before firing). */
    fun isOnCheckoutScreen(): Boolean

    /** True if an order confirmation is on screen now (idempotency check). */
    fun hasRecentOrder(): Boolean

    /**
     * Open the "Shopee Voucher" row, select per [preference], confirm.
     * [requireApplied] gates whether the checkout must actually show the voucher
     * applied before returning [VoucherApplyResult.Applied] (true = 3-step live).
     */
    suspend fun applyBestVoucher(
        preference: VoucherPreference,
        requireApplied: Boolean
    ): VoucherApplyResult

    /** Tap "Đặt hàng" and parse the order result. */
    suspend fun clickPlaceOrder(): PlaceOrderResult

    /** One unit of harmless pre-fire warm-up activity (anti-fraud). */
    fun warmUpNudge()
}

/**
 * Production [CheckoutUiDriver] backed by [ShopeeAccessibilityService]. Thin
 * forwarder — all the real work lives in the service (and its extracted flows).
 */
object AccessibilityCheckoutUiDriver : CheckoutUiDriver {
    override fun isOnCheckoutScreen(): Boolean =
        ShopeeAccessibilityService.isOnCheckoutScreen()

    override fun hasRecentOrder(): Boolean =
        ShopeeAccessibilityService.hasRecentOrder()

    override suspend fun applyBestVoucher(
        preference: VoucherPreference,
        requireApplied: Boolean
    ): VoucherApplyResult =
        ShopeeAccessibilityService.applyBestVoucher(preference, requireApplied)

    override suspend fun clickPlaceOrder(): PlaceOrderResult =
        ShopeeAccessibilityService.clickPlaceOrder()

    override fun warmUpNudge() =
        ShopeeAccessibilityService.warmUpNudge()
}
