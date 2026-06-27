package com.personal.shopeekit.features.price

import android.content.Context
import com.personal.shopeekit.features.price.db.PriceDatabase
import com.personal.shopeekit.features.price.db.PriceRecord
import com.personal.shopeekit.features.price.db.TrackedProduct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single access point for price data. Keeps Room DAO calls out of the UI and
 * the worker so callers depend on this instead of the database directly.
 */
class PriceRepository(context: Context) {

    private val dao = PriceDatabase.getInstance(context).priceDao()

    // Tracked products
    fun observeActiveProducts(): Flow<List<TrackedProduct>> = dao.getActiveProducts()
    suspend fun getActiveProductsOnce(): List<TrackedProduct> = dao.getActiveProductsOnce()
    suspend fun getProduct(productId: String): TrackedProduct? = dao.getProduct(productId)
    suspend fun insertProduct(product: TrackedProduct) = dao.insertProduct(product)
    suspend fun deactivateProduct(productId: String) = dao.deactivateProduct(productId)
    suspend fun updateAlertThreshold(productId: String, threshold: Long) =
        dao.updateAlertThreshold(productId, threshold)
    suspend fun updatePollInterval(productId: String, hours: Int) =
        dao.updatePollInterval(productId, hours)

    // Price history
    fun observePriceHistory(productId: String): Flow<List<PriceRecord>> =
        dao.getPriceHistory(productId)
    suspend fun getHistoryOnce(productId: String): List<PriceRecord> =
        dao.getPriceHistory(productId).first()
    suspend fun getLatestPrice(productId: String): PriceRecord? = dao.getLatestPrice(productId)
    suspend fun getLowestPrice(productId: String): Long? = dao.getLowestPrice(productId)
}
