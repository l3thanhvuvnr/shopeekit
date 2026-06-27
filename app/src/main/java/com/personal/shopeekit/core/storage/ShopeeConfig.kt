package com.personal.shopeekit.core.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Stores Shopee auth config captured via mitmproxy.
 * All values come from real Shopee app traffic — required for authenticated requests.
 */
class ShopeeConfig(private val context: Context) {

    companion object {
        private val KEY_COOKIE = stringPreferencesKey("cookie")
        private val KEY_USER_AGENT = stringPreferencesKey("user_agent")
        private val KEY_X_SAP_RI = stringPreferencesKey("x_sap_ri")  // Last captured; short-lived
        private val KEY_AF_AC = stringPreferencesKey("af_ac")
        private val KEY_SHOPEE_BASE_URL = stringPreferencesKey("shopee_base_url")

        // Default URL = shopee.vn trực tiếp (release).
        // Debug build override được inject qua BuildConfig.SHOPEE_BASE_URL trong ShopeeHttpClient.
        private const val DEFAULT_BASE_URL = "https://shopee.vn"
        private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 16; Xiaomi 15) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    val cookieFlow: Flow<String> = context.configDataStore.data
        .map { it[KEY_COOKIE] ?: "" }

    val userAgentFlow: Flow<String> = context.configDataStore.data
        .map { it[KEY_USER_AGENT] ?: DEFAULT_UA }

    val baseUrlFlow: Flow<String> = context.configDataStore.data
        .map { it[KEY_SHOPEE_BASE_URL] ?: DEFAULT_BASE_URL }

    // Suspend accessors — prefer these everywhere we're already in a coroutine.
    suspend fun getCookie(): String = cookieFlow.first()
    suspend fun getUserAgent(): String = userAgentFlow.first()
    suspend fun getBaseUrl(): String = baseUrlFlow.first()

    /** Auth headers for an authenticated request. Safe to call from a coroutine. */
    suspend fun authHeaders(): Map<String, String> = mapOf(
        "Cookie" to getCookie(),
        "User-Agent" to getUserAgent()
    ).filterValues { it.isNotBlank() }

    // Sync accessors for non-coroutine contexts (use sparingly — never on the main thread).
    fun getCookieSync(): String = runBlocking { cookieFlow.first() }
    fun getUserAgentSync(): String = runBlocking { userAgentFlow.first() }
    fun getBaseUrlSync(): String = runBlocking { baseUrlFlow.first() }

    fun isConfigured(): Boolean = getCookieSync().isNotBlank()

    suspend fun saveCookie(cookie: String) {
        context.configDataStore.edit { it[KEY_COOKIE] = cookie }
    }

    suspend fun saveUserAgent(ua: String) {
        context.configDataStore.edit { it[KEY_USER_AGENT] = ua }
    }

    suspend fun saveXSapRi(xSapRi: String) {
        context.configDataStore.edit { it[KEY_X_SAP_RI] = xSapRi }
    }

    suspend fun saveAfAc(afAc: String) {
        context.configDataStore.edit { it[KEY_AF_AC] = afAc }
    }

    suspend fun saveBaseUrl(url: String) {
        context.configDataStore.edit { it[KEY_SHOPEE_BASE_URL] = url }
    }

    fun buildAuthHeaders(): Map<String, String> = mapOf(
        "Cookie" to getCookieSync(),
        "User-Agent" to getUserAgentSync()
    ).filterValues { it.isNotBlank() }
}
