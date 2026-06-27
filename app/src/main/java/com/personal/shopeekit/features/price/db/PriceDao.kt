package com.personal.shopeekit.features.price.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceDao {

    // --- PriceRecord ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrice(record: PriceRecord)

    @Query("SELECT * FROM price_history WHERE productId = :productId ORDER BY timestamp DESC")
    fun getPriceHistory(productId: String): Flow<List<PriceRecord>>

    @Query("SELECT * FROM price_history WHERE productId = :productId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestPrice(productId: String): PriceRecord?

    @Query("SELECT MIN(price) FROM price_history WHERE productId = :productId")
    suspend fun getLowestPrice(productId: String): Long?

    @Query("DELETE FROM price_history WHERE productId = :productId AND timestamp < :beforeMs")
    suspend fun pruneOldRecords(productId: String, beforeMs: Long)

    // --- TrackedProduct ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: TrackedProduct)

    @Query("SELECT * FROM tracked_products WHERE isActive = 1 ORDER BY addedAt DESC")
    fun getActiveProducts(): Flow<List<TrackedProduct>>

    @Query("SELECT * FROM tracked_products WHERE isActive = 1")
    suspend fun getActiveProductsOnce(): List<TrackedProduct>

    @Query("SELECT * FROM tracked_products WHERE productId = :productId")
    suspend fun getProduct(productId: String): TrackedProduct?

    @Query("UPDATE tracked_products SET isActive = 0 WHERE productId = :productId")
    suspend fun deactivateProduct(productId: String)

    @Query("UPDATE tracked_products SET alertThresholdVnd = :threshold WHERE productId = :productId")
    suspend fun updateAlertThreshold(productId: String, threshold: Long)

    @Query("UPDATE tracked_products SET pollIntervalHours = :hours WHERE productId = :productId")
    suspend fun updatePollInterval(productId: String, hours: Int)

    @Query("UPDATE tracked_products SET productName = :name, thumbnailUrl = :thumbnailUrl WHERE productId = :productId")
    suspend fun updateProductMeta(productId: String, name: String, thumbnailUrl: String)

    @Query("UPDATE tracked_products SET lastAlertAtMs = :timestamp WHERE productId = :productId")
    suspend fun updateLastAlert(productId: String, timestamp: Long)
}
