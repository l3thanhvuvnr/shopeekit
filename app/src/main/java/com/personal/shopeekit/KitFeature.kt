package com.personal.shopeekit

import android.content.Context
import android.view.View

/**
 * Base interface for all ShopeeKit features.
 * Add new features by implementing this interface and registering in ShopeeKitApp.
 */
interface KitFeature {
    val featureId: String
    val displayName: String
    val iconRes: Int

    fun initialize(context: Context)
    fun release()
    fun createMainView(context: Context): View
    fun createSettingsView(context: Context): View? = null
}
