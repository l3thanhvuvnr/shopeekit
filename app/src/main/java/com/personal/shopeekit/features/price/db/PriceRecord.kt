package com.personal.shopeekit.features.price.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single price data point for a tracked product.
 * Price stored in VND (integer, no float rounding issues).
 *
 * Indexed on (productId, timestamp) — every history/latest query filters by
 * productId and orders by timestamp, so this avoids a full-table scan as the
 * table grows (≈540 rows per product over 90 days at 4h polling).
 */
@Entity(
    tableName = "price_history",
    indices = [Index(value = ["productId", "timestamp"])]
)
data class PriceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    val shopId: Long,
    val productName: String,
    val price: Long,           // current price in VND
    val originalPrice: Long,   // original price before discount
    val discountPercent: Int,  // 0-100
    val timestamp: Long        // epoch ms
)
