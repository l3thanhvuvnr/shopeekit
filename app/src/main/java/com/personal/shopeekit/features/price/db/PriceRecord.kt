package com.personal.shopeekit.features.price.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single price data point for a tracked product.
 * Price stored in VND (integer, no float rounding issues).
 */
@Entity(tableName = "price_history")
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
