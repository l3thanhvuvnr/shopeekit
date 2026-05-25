package com.personal.shopeekit.features.price.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A product the user wants to track price for.
 */
@Entity(tableName = "tracked_products")
data class TrackedProduct(
    @PrimaryKey val productId: String,
    val shopId: Long,
    val productName: String,
    val productUrl: String,
    val thumbnailUrl: String = "",
    /** Alert when price drops below this. 0 = disabled. */
    val alertThresholdVnd: Long = 0L,
    /** How often to poll (hours). */
    val pollIntervalHours: Int = 4,
    val addedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)
