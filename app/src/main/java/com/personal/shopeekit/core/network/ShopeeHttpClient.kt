package com.personal.shopeekit.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

import com.personal.shopeekit.BuildConfig

/**
 * Singleton OkHttp client for all Shopee HTTP calls.
 *
 * BASE URL strategy (baked in at build time via BuildConfig):
 *  - Release build (điện thoại thật): https://shopee.vn  — gọi thẳng, cookie không đi qua trung gian
 *  - Debug build   (emulator/PC):     Cloudflare Worker relay — bypass Kaspersky HTTPS inspection
 *
 * User có thể override URL thủ công qua TokenSetupActivity (lưu vào ShopeeConfig).
 * Nếu đã có giá trị lưu thì ưu tiên giá trị đó, không dùng BuildConfig.
 */
object ShopeeHttpClient {

    private const val TAG = "ShopeeHttpClient"
    private const val PING_PATH = "/api/v4/account/basic"

    /** Base URL — set từ BuildConfig lúc build, có thể override bằng ShopeeConfig */
    @Volatile
    var baseUrl: String = BuildConfig.SHOPEE_BASE_URL
        private set

    val client: OkHttpClient = if (BuildConfig.USE_RELAY) buildTrustAllClient() else buildDefaultClient()

    /**
     * Gọi trong Application.onCreate() để load URL override từ preferences (nếu có).
     * Nếu user chưa set thủ công → giữ nguyên BuildConfig.SHOPEE_BASE_URL.
     */
    fun init(context: Context) {
        try {
            val config = com.personal.shopeekit.core.storage.ShopeeConfig(context)
            val saved = config.getBaseUrlSync()
            // Chỉ override nếu user đã tự config với một URL KHÁC base build-time
            // (tránh coi giá trị mặc định/relay là "user override").
            if (saved.isNotBlank() && saved.trimEnd('/') != BuildConfig.SHOPEE_BASE_URL.trimEnd('/')) {
                baseUrl = saved.trimEnd('/')
                Log.i(TAG, "Base URL overridden by user config: $baseUrl")
            } else {
                Log.i(TAG, "Base URL from BuildConfig: $baseUrl (relay=${BuildConfig.USE_RELAY})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load base URL from config, using BuildConfig default: $e")
        }
    }

    /**
     * OkHttpClient that bypasses SSL certificate validation.
     * ONLY used in debug builds routing through the Cloudflare relay, where
     * Kaspersky KES injects its own cert and would otherwise cause SSLHandshakeException.
     * Never used in release builds — see [buildDefaultClient].
     */
    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun buildTrustAllClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").also {
            it.init(null, arrayOf<TrustManager>(trustAll), null)
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * Standard OkHttpClient with system certificate validation.
     * Used in release builds where traffic goes directly to shopee.vn —
     * full SSL/TLS validation is required to protect the session cookie.
     */
    private fun buildDefaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Build a request with common Shopee anti-bot headers.
     * [extraHeaders] allows per-request overrides.
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
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", "https://shopee.vn/")
            .header("Origin", "https://shopee.vn")
            .apply {
                if (cookie.isNotBlank()) header("Cookie", cookie)
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
    }

    fun pingUrl(): String = "$baseUrl$PING_PATH"

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
}

