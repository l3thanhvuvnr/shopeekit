package com.personal.shopeekit.features.price

import com.personal.shopeekit.features.price.db.PriceDatabase
import android.content.Context

/**
 * Calculates the effective best price combining current product price + best available voucher.
 *
 * Best Price Formula:
 *   effectivePrice = currentPrice - bestVoucherDiscount
 *
 * In future: can integrate with CheckoutSniper to track available vouchers.
 */
class BestPriceCalculator(private val context: Context) {

    private val db by lazy { PriceDatabase.getInstance(context) }

    data class BestPriceResult(
        val productId: String,
        val currentPrice: Long,
        val lowestHistoricalPrice: Long,
        val voucherDiscount: Long,
        val effectivePrice: Long,
        val savingsVsOriginal: Long,
        val savingsPercent: Int,
        val isCurrentlyLowest: Boolean,
        val recommendation: String
    )

    /**
     * Get the best price breakdown for a product.
     * @param voucherDiscountVnd Amount saved from best available voucher (0 if none)
     */
    suspend fun calculate(productId: String, voucherDiscountVnd: Long = 0L): BestPriceResult? {
        val latest = db.priceDao().getLatestPrice(productId) ?: return null
        val lowestEver = db.priceDao().getLowestPrice(productId) ?: latest.price

        val effectivePrice = maxOf(0L, latest.price - voucherDiscountVnd)
        val savingsVsOriginal = latest.originalPrice - effectivePrice
        val savingsPercent = if (latest.originalPrice > 0)
            ((savingsVsOriginal.toDouble() / latest.originalPrice) * 100).toInt()
        else 0

        val isCurrentlyLowest = latest.price <= lowestEver
        val recommendation = when {
            isCurrentlyLowest && voucherDiscountVnd > 0 ->
                "🔥 Giá thấp nhất + voucher — MUA NGAY!"
            isCurrentlyLowest ->
                "✅ Đang ở giá thấp nhất lịch sử"
            effectivePrice < lowestEver ->
                "💡 Với voucher, giá thấp hơn lịch sử — nên mua"
            latest.price > lowestEver * 1.1 ->
                "⏳ Giá đang cao hơn lịch sử ${((latest.price.toDouble()/lowestEver - 1)*100).toInt()}% — chờ"
            else ->
                "📊 Giá bình thường"
        }

        return BestPriceResult(
            productId = productId,
            currentPrice = latest.price,
            lowestHistoricalPrice = lowestEver,
            voucherDiscount = voucherDiscountVnd,
            effectivePrice = effectivePrice,
            savingsVsOriginal = savingsVsOriginal,
            savingsPercent = savingsPercent,
            isCurrentlyLowest = isCurrentlyLowest,
            recommendation = recommendation
        )
    }

    /**
     * Check if now is a good time to buy (price near historical low).
     * @param tolerance 0.05 = within 5% of lowest price
     */
    suspend fun isGoodTimeToBuy(productId: String, tolerance: Double = 0.05): Boolean {
        val latest = db.priceDao().getLatestPrice(productId) ?: return false
        val lowest = db.priceDao().getLowestPrice(productId) ?: return false
        return latest.price <= lowest * (1 + tolerance)
    }
}
