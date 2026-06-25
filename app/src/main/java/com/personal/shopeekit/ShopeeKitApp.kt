package com.personal.shopeekit

import android.app.Application
import com.personal.shopeekit.core.network.ShopeeHttpClient
import com.personal.shopeekit.features.checkout.CheckoutSniperFeature
import com.personal.shopeekit.features.price.PriceHistoryFeature

class ShopeeKitApp : Application() {

    val features: List<KitFeature> by lazy {
        listOf(
            PriceHistoryFeature(),
            CheckoutSniperFeature(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Load configurable base URL (relay proxy) before any network calls
        ShopeeHttpClient.init(this)
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
