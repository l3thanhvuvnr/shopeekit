package com.personal.shopeekit.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personal.shopeekit.ShopeeKitApp
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

// One DataStore per feature to avoid key conflicts
val Context.sniperDataStore: DataStore<Preferences> by preferencesDataStore(name = "sniper_prefs")
val Context.priceDataStore: DataStore<Preferences> by preferencesDataStore(name = "price_prefs")
val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "config_prefs")
val Context.uiDiscoveryDataStore: DataStore<Preferences> by preferencesDataStore(name = "ui_discovery_prefs")

/**
 * Singleton accessor for UI Discovery cache (ShopeeUIDiscovery backing store).
 */
object AppDataStore {

    private val context get() = ShopeeKitApp.instance

    suspend fun getString(key: String): String? {
        val prefKey = stringPreferencesKey(key)
        return context.uiDiscoveryDataStore.data
            .map { it[prefKey] }
            .firstOrNull()
    }

    suspend fun setString(key: String, value: String) {
        val prefKey = stringPreferencesKey(key)
        context.uiDiscoveryDataStore.edit { prefs ->
            prefs[prefKey] = value
        }
    }

    suspend fun remove(key: String) {
        val prefKey = stringPreferencesKey(key)
        context.uiDiscoveryDataStore.edit { prefs ->
            prefs.remove(prefKey)
        }
    }
}
