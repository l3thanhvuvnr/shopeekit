package com.personal.shopeekit.features.price.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PriceRecord::class, TrackedProduct::class],
    version = 1,
    exportSchema = false
)
abstract class PriceDatabase : RoomDatabase() {

    abstract fun priceDao(): PriceDao

    companion object {
        @Volatile private var INSTANCE: PriceDatabase? = null

        fun getInstance(context: Context): PriceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PriceDatabase::class.java,
                    "shopeekit_prices.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
