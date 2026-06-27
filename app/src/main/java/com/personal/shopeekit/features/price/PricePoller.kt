package com.personal.shopeekit.features.price

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personal.shopeekit.core.network.ShopeeHttpClient
import com.personal.shopeekit.core.logging.KitLogger
import com.personal.shopeekit.core.storage.ShopeeConfig
import com.personal.shopeekit.features.price.db.PriceDatabase
import com.personal.shopeekit.features.price.db.PriceRecord
import com.personal.shopeekit.features.price.db.TrackedProduct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        private const val PRUNE_AGE_MS = 90L * 24 * 60 * 60 * 1000 // 90 days
        private const val ALERT_COOLDOWN_MS = 12L * 60 * 60 * 1000 // 12h between alerts
    }

    private val db by lazy { PriceDatabase.getInstance(applicationContext) }
    private val config by lazy { ShopeeConfig(applicationContext) }
    private val alertManager by lazy { PriceAlertManager(applicationContext) }

    override suspend fun doWork(): Result {
        val productId = inputData.getString(KEY_PRODUCT_ID) ?: return Result.failure()
        val shopId = inputData.getLong(KEY_SHOP_ID, -1L)
        if (shopId == -1L) return Result.failure()

        return try {
            val fetch = fetchPrice(productId, shopId)
            when (fetch) {
                is FetchResult.Ok -> {
                    val priceData = fetch.record
                    KitLogger.i("PRC", "poll $productId — price=${priceData.price}₫ name=${priceData.productName.take(30)}")
                    val dao = db.priceDao()

                    // Read previous state BEFORE inserting the new row, otherwise
                    // "is this a new low?" always compares the row against itself.
                    val product = dao.getProduct(productId)
                    val previousLowest = dao.getLowestPrice(productId)
                    val previousLatest = dao.getLatestPrice(productId)

                    dao.insertPrice(priceData)

                    // Keep product metadata fresh (name starts as "Loading...").
                    if (product != null &&
                        (product.productName != priceData.productName ||
                            product.thumbnailUrl != fetch.thumbnailUrl)
                    ) {
                        dao.updateProductMeta(productId, priceData.productName, fetch.thumbnailUrl)
                    }

                    if (product != null && product.alertThresholdVnd > 0) {
                        maybeNotify(product, priceData, previousLowest, previousLatest)
                    }

                    dao.pruneOldRecords(productId, System.currentTimeMillis() - PRUNE_AGE_MS)
                    Result.success()
                }
                FetchResult.AuthExpired -> {
                    KitLogger.e("PRC", "poll $productId — AUTH EXPIRED (401/403), stopping worker")
                    Result.failure()
                }
                FetchResult.Transient -> {
                    KitLogger.w("PRC", "poll $productId — transient error (429/5xx), retrying later")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            KitLogger.e("PRC", "poll $productId — exception: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.retry()
        }
    }

    private sealed class FetchResult {
        data class Ok(val record: PriceRecord, val thumbnailUrl: String) : FetchResult()
        object AuthExpired : FetchResult()
        object Transient : FetchResult()
    }

    private suspend fun fetchPrice(productId: String, shopId: Long): FetchResult =
        withContext(Dispatchers.IO) {
            val url = "${ShopeeHttpClient.baseUrl}$PRICE_API_PATH?item_id=$productId&shop_id=$shopId"
            val request = ShopeeHttpClient.buildRequest(
                url = url,
                extraHeaders = config.authHeaders()
            )

            ShopeeHttpClient.client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> FetchResult.AuthExpired
                    response.code == 429 || response.code >= 500 -> FetchResult.Transient
                    !response.isSuccessful -> FetchResult.Transient
                    else -> {
                        val body = response.body?.string() ?: return@use FetchResult.Transient
                        parsePriceResponse(body, productId, shopId) ?: FetchResult.Transient
                    }
                }
            }
        }

    private fun parsePriceResponse(json: String, productId: String, shopId: Long): FetchResult? {
        return try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: return null
            val item = data.optJSONObject("item") ?: return null

            val price = item.optLong("price", 0L) / 100_000L // Shopee returns price * 100000
            // price == 0 means out of stock / model-based pricing — don't pollute history.
            if (price <= 0L) return null

            val originalPrice = item.optLong("price_before_discount", 0L) / 100_000L
            val name = item.optString("name", "Unknown Product")
            val thumbnail = item.optString("image", "")
            val discountPercent = if (originalPrice > 0 && price < originalPrice)
                ((1.0 - price.toDouble() / originalPrice) * 100).toInt()
            else 0

            FetchResult.Ok(
                record = PriceRecord(
                    productId = productId,
                    shopId = shopId,
                    productName = name,
                    price = price,
                    originalPrice = if (originalPrice > 0) originalPrice else price,
                    discountPercent = discountPercent,
                    timestamp = System.currentTimeMillis()
                ),
                thumbnailUrl = thumbnail
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Notify only when the price is below the user's threshold AND it's a genuine
     * new low (vs all prior history), AND we haven't alerted within the cooldown.
     */
    private suspend fun maybeNotify(
        product: TrackedProduct,
        current: PriceRecord,
        previousLowest: Long?,
        previousLatest: PriceRecord?
    ) {
        val belowThreshold = current.price <= product.alertThresholdVnd
        val isNewLow = previousLowest == null || current.price < previousLowest
        val now = System.currentTimeMillis()
        val cooldownOver = now - product.lastAlertAtMs >= ALERT_COOLDOWN_MS

        if (belowThreshold && isNewLow && cooldownOver) {
            alertManager.notifyPriceDrop(
                productId = product.productId,
                productName = current.productName,
                currentPrice = current.price,
                previousPrice = previousLatest?.price ?: current.originalPrice,
                threshold = product.alertThresholdVnd
            )
            db.priceDao().updateLastAlert(product.productId, now)
        }
    }
}
