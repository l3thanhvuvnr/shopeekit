package com.personal.shopeekit.features.checkout

import android.content.Context
import android.view.View
import com.personal.shopeekit.KitFeature
import com.personal.shopeekit.R

class CheckoutSniperFeature : KitFeature {
    override val featureId = "checkout_sniper"
    override val displayName = "Checkout Sniper"
    override val iconRes = R.drawable.ic_sniper

    lateinit var engine: CheckoutSniperEngine
        private set

    override fun initialize(context: Context) {
        engine = CheckoutSniperEngine()
    }

    override fun release() {
        engine.destroy()
    }

    override fun createMainView(context: Context): View = View(context)
}
