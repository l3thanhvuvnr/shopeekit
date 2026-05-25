package com.personal.shopeekit.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// One DataStore per feature to avoid key conflicts
val Context.sniperDataStore: DataStore<Preferences> by preferencesDataStore(name = "sniper_prefs")
val Context.priceDataStore: DataStore<Preferences> by preferencesDataStore(name = "price_prefs")
val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "config_prefs")
