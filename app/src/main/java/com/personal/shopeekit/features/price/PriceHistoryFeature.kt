package com.personal.shopeekit.features.price

import android.content.Context
import android.view.View
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.personal.shopeekit.KitFeature
import com.personal.shopeekit.R
import com.personal.shopeekit.features.price.db.PriceDatabase
import com.personal.shopeekit.features.price.db.TrackedProduct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class PriceHistoryFeature : KitFeature {
    override val featureId = "price_history"
    override val displayName = "Lịch Sử Giá"
    override val iconRes = R.drawable.ic_price_chart

    private lateinit var appContext: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val calculator by lazy { BestPriceCalculator(appContext) }
    val db by lazy { PriceDatabase.getInstance(appContext) }

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun release() {
        scope.cancel()
    }

    override fun createMainView(context: Context): View = View(context)

    /**
     * Add a product for price tracking and schedule periodic polling.
     * Extract productId and shopId from Shopee URL format:
     *   shopee.vn/{shop-name}.{shopId}.{productId}
     */
    fun trackProduct(url: String, alertThresholdVnd: Long = 0L, pollIntervalHours: Int = 4) {
        val (shopId, productId) = parseShopeeUrl(url) ?: return
        val product = TrackedProduct(
            productId = productId,
            shopId = shopId,
            productName = "Loading...",
            productUrl = url,
            alertThresholdVnd = alertThresholdVnd,
            pollIntervalHours = pollIntervalHours
        )

        scope.launch {
            db.priceDao().insertProduct(product)
            schedulePoller(productId, shopId, pollIntervalHours)
        }
    }

    fun schedulePoller(productId: String, shopId: Long, pollIntervalHours: Int) {
        val workManager = WorkManager.getInstance(appContext)
        val inputData = Data.Builder()
            .putString(PricePoller.KEY_PRODUCT_ID, productId)
            .putLong(PricePoller.KEY_SHOP_ID, shopId)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<PricePoller>(
            pollIntervalHours.toLong(), TimeUnit.HOURS
        )
            .setInputData(inputData)
            .setConstraints(constraints)
            .build()

        // UPDATE (not KEEP): if the user changes the poll interval, the existing
        // schedule must be replaced — KEEP would silently ignore the new interval.
        workManager.enqueueUniquePeriodicWork(
            "${PricePoller.WORK_NAME_PREFIX}$productId",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun stopTracking(productId: String) {
        WorkManager.getInstance(appContext)
            .cancelUniqueWork("${PricePoller.WORK_NAME_PREFIX}$productId")
        scope.launch {
            db.priceDao().deactivateProduct(productId)
        }
    }

    /**
     * Parse Shopee URL to extract (shopId, productId).
     * URL format: https://shopee.vn/product-name-i.{shopId}.{productId}
     * or:         https://shopee.vn/shop/{shopId}/product/{productId}
     */
    fun parseShopeeUrl(url: String): Pair<Long, String>? {
        // Pattern: .i.{shopId}.{productId}
        val dotPattern = Regex("""\bi\.(\d+)\.(\d+)""")
        val dotMatch = dotPattern.find(url)
        if (dotMatch != null) {
            return Pair(dotMatch.groupValues[1].toLong(), dotMatch.groupValues[2])
        }

        // Pattern: /shop/{shopId}/product/{productId}
        val pathPattern = Regex("""/shop/(\d+)/product/(\d+)""")
        val pathMatch = pathPattern.find(url)
        if (pathMatch != null) {
            return Pair(pathMatch.groupValues[1].toLong(), pathMatch.groupValues[2])
        }

        return null
    }
}
