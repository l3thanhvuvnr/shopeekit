package com.personal.shopeekit.features.price

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personal.shopeekit.core.network.ShopeeHttpClient
import com.personal.shopeekit.core.storage.ShopeeConfig
import com.personal.shopeekit.features.price.db.PriceDatabase
import com.personal.shopeekit.features.price.db.PriceRecord
import com.personal.shopeekit.features.price.db.TrackedProduct
import org.json.JSONObject

/**
 * WorkManager worker that polls Shopee product prices.
 *
 * Shopee product price API (no X-Sap-Ri required for public data):
 *   GET https://shopee.vn/api/v4/pdp/get_pc?item_id={productId}&shop_id={shopId}
 *
 * Scheduled by PriceHistoryFeature at the interval set per product.
 */
class PricePoller(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME_PREFIX = "price_poll_"
        const val KEY_PRODUCT_ID = "product_id"
        const val KEY_SHOP_ID = "shop_id"

        private const val PRICE_API_PATH = "/api/v4/pdp/get_pc"
    }

    private val db by lazy { PriceDatabase.getInstance(applicationContext) }
    private val config by lazy { ShopeeConfig(applicationContext) }
    private val alertManager by lazy { PriceAlertManager(applicationContext) }

    override suspend fun doWork(): Result {
        val productId = inputData.getString(KEY_PRODUCT_ID) ?: return Result.failure()
        val shopId = inputData.getLong(KEY_SHOP_ID, -1L)
        if (shopId == -1L) return Result.failure()

        return try {
            val priceData = fetchPrice(productId, shopId) ?: return Result.retry()

            // Save to DB
            db.priceDao().insertPrice(priceData)

            // Check alert threshold
            val product = db.priceDao().getProduct(productId)
            if (product != null && product.alertThresholdVnd > 0) {
                checkAndNotifyAlert(product, priceData)
            }

            // Prune records older than 90 days
            val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            db.priceDao().pruneOldRecords(productId, cutoff)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun fetchPrice(productId: String, shopId: Long): PriceRecord? {
        val url = "${ShopeeHttpClient.baseUrl}$PRICE_API_PATH?item_id=$productId&shop_id=$shopId"
        val request = ShopeeHttpClient.buildRequest(
            url = url,
            extraHeaders = config.buildAuthHeaders()
        )

        val response = ShopeeHttpClient.client.newCall(request).execute()
        val body = response.body?.string() ?: return null
        response.close()

        return parsePriceResponse(body, productId, shopId)
    }

    private fun parsePriceResponse(json: String, productId: String, shopId: Long): PriceRecord? {
        return try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: return null
            val item = data.optJSONObject("item") ?: return null

            val price = item.optLong("price", 0L) / 100_000L // Shopee returns price * 100000
            val originalPrice = item.optLong("price_before_discount", 0L) / 100_000L
            val name = item.optString("name", "Unknown Product")
            val discountPercent = if (originalPrice > 0 && price < originalPrice)
                ((1.0 - price.toDouble() / originalPrice) * 100).toInt()
            else 0

            PriceRecord(
                productId = productId,
                shopId = shopId,
                productName = name,
                price = price,
                originalPrice = if (originalPrice > 0) originalPrice else price,
                discountPercent = discountPercent,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun checkAndNotifyAlert(product: TrackedProduct, current: PriceRecord) {
        val previous = db.priceDao().getLatestPrice(product.productId)
        val isNewLow = previous == null || current.price < previous.price
        val belowThreshold = current.price <= product.alertThresholdVnd

        if (belowThreshold && isNewLow) {
            alertManager.notifyPriceDrop(
                productId = product.productId,
                productName = current.productName,
                currentPrice = current.price,
                previousPrice = previous?.price ?: current.originalPrice,
                threshold = product.alertThresholdVnd
            )
        }
    }
}
