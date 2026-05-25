package com.personal.shopeekit.features.sniper

import android.content.Context
import android.view.View
import com.personal.shopeekit.KitFeature
import com.personal.shopeekit.R

class VoucherSniperFeature : KitFeature {
    override val featureId = "voucher_sniper"
    override val displayName = "Voucher Sniper"
    override val iconRes = R.drawable.ic_sniper

    lateinit var engine: SniperEngine
        private set

    override fun initialize(context: Context) {
        engine = SniperEngine(context)
    }

    override fun release() {
        engine.disarm()
    }

    override fun createMainView(context: Context): View {
        // Returns a placeholder — actual UI is SniperSetupActivity
        return View(context)
    }
}
