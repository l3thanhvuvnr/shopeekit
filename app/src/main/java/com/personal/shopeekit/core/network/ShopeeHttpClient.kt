package com.personal.shopeekit.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Singleton OkHttp client for all Shopee HTTP calls.
 * Raw client — no Retrofit abstraction — for precise timing control.
 */
object ShopeeHttpClient {

    private const val SHOPEE_BASE_URL = "https://shopee.vn"
    private const val PING_PATH = "/api/v4/account/basic"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Build a request with common Shopee headers.
     * [extraHeaders] allows per-request overrides (e.g. X-Sap-Ri captured via mitmproxy).
     */
    fun buildRequest(
        url: String,
        userAgent: String = DEFAULT_USER_AGENT,
        cookie: String = "",
        extraHeaders: Map<String, String> = emptyMap()
    ): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("X-Shopee-Language", "vi")
            .header("Accept", "application/json")
            .apply {
                if (cookie.isNotBlank()) header("Cookie", cookie)
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
    }

    fun pingUrl(): String = "$SHOPEE_BASE_URL$PING_PATH"

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
}
