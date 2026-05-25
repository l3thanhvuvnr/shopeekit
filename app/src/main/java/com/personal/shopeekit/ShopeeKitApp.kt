package com.personal.shopeekit

import android.app.Application
import com.personal.shopeekit.features.price.PriceHistoryFeature
import com.personal.shopeekit.features.sniper.VoucherSniperFeature

class ShopeeKitApp : Application() {

    val features: List<KitFeature> by lazy {
        listOf(
            VoucherSniperFeature(),
            PriceHistoryFeature(),
            // Future: DailyCoinsFeature(), FlashSaleFeature(), etc.
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        features.forEach { it.initialize(this) }
    }

    override fun onTerminate() {
        features.forEach { it.release() }
        super.onTerminate()
    }

    companion object {
        lateinit var instance: ShopeeKitApp
            private set
    }
}
