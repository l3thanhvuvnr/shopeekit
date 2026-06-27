package com.personal.shopeekit.features.price.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PriceRecord::class, TrackedProduct::class],
    version = 2,
    exportSchema = true
)
abstract class PriceDatabase : RoomDatabase() {

    abstract fun priceDao(): PriceDao

    companion object {
        @Volatile private var INSTANCE: PriceDatabase? = null

        /**
         * v1 → v2: add (productId, timestamp) index on price_history and the
         * lastAlertAtMs column on tracked_products. Preserves all existing data
         * (no destructive fallback — price history must survive app updates).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_price_history_productId_timestamp " +
                        "ON price_history (productId, timestamp)"
                )
                db.execSQL(
                    "ALTER TABLE tracked_products ADD COLUMN lastAlertAtMs INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): PriceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PriceDatabase::class.java,
                    "shopeekit_prices.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
