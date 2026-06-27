package com.personal.shopeekit.features.price

import com.personal.shopeekit.core.network.ShopeeHttpClient
import com.personal.shopeekit.core.storage.ShopeeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** A product returned by Shopee search. */
data class ShopeeSearchResult(
    val name: String,
    val price: Long,
    val shopId: Long,
    val productId: String,
    val url: String
)

/** Outcome of a search call — distinguishes empty results from real errors. */
sealed class SearchOutcome {
    data class Success(val results: List<ShopeeSearchResult>) : SearchOutcome()
    object Empty : SearchOutcome()
    data class AuthError(val message: String) : SearchOutcome()
    data class Error(val message: String) : SearchOutcome()
}

/**
 * Talks to Shopee's public search endpoint. Network lives here (not in the
 * Activity); JSON parsing is a pure function reused by tests.
 */
class ShopeeSearchRepository(private val config: ShopeeConfig) {

    companion object {
        private const val MAX_RESULTS = 20
        private const val AUTH_ERROR_CODE = 90309999
    }

    suspend fun search(keyword: String): SearchOutcome = withContext(Dispatchers.IO) {
        try {
            val cookie = config.getCookie()
            val csrfToken = Regex("""(?:^|;\s*)csrftoken=([^;]+)""").find(cookie)
                ?.groupValues?.get(1)?.trim() ?: ""

            val encodedKw = URLEncoder.encode(keyword, "UTF-8")
            val url = "${ShopeeHttpClient.baseUrl}/api/v4/search/search_items" +
                "?by=relevancy&keyword=$encodedKw&limit=$MAX_RESULTS&newest=0" +
                "&order=desc&page_type=search&scenario=PAGE_GLOBAL_SEARCH&version=2"

            val extraHeaders = mutableMapOf<String, String>()
            if (csrfToken.isNotBlank()) extraHeaders["X-CSRFToken"] = csrfToken

            val request = ShopeeHttpClient.buildRequest(url = url, cookie = cookie, extraHeaders = extraHeaders)
            ShopeeHttpClient.client.newCall(request).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type") ?: ""
                val body = response.body?.string()
                    ?: return@withContext SearchOutcome.Error("Lỗi mạng (HTTP $code, body trống)")

                parseOutcome(body, code, contentType)
            }
        } catch (e: Exception) {
            SearchOutcome.Error("Lỗi kết nối: ${e.message}")
        }
    }

    /** Pure: turn a search response into an outcome. Shared with unit tests. */
    fun parseOutcome(body: String, httpCode: Int, contentType: String): SearchOutcome {
        // Shopee error code (can arrive with HTTP 200)
        runCatching {
            val root = JSONObject(body)
            val shopeeError = root.optInt("error", 0)
            if (shopeeError != 0) {
                return when (shopeeError) {
                    AUTH_ERROR_CODE -> SearchOutcome.AuthError(
                        "Cookie có thể đã hết hạn. Vào Đồng bộ tài khoản → đăng nhập lại."
                    )
                    else -> SearchOutcome.Error("Shopee error $shopeeError (HTTP $httpCode)")
                }
            }
        }

        if (!contentType.contains("json", ignoreCase = true) && contentType.isNotEmpty()) {
            return SearchOutcome.Error(
                "Shopee trả về nội dung không hợp lệ (HTTP $httpCode). Thử lại hoặc kiểm tra kết nối."
            )
        }

        val results = parseResults(body)
        return if (results.isEmpty()) SearchOutcome.Empty else SearchOutcome.Success(results)
    }

    /** Pure: extract product rows from the search JSON body. */
    fun parseResults(json: String): List<ShopeeSearchResult> {
        return try {
            val root = JSONObject(json)
            val items = root.optJSONArray("items")
                ?: root.optJSONObject("data")?.optJSONArray("items")
                ?: return emptyList()

            val results = mutableListOf<ShopeeSearchResult>()
            for (i in 0 until minOf(items.length(), MAX_RESULTS)) {
                val obj = items.optJSONObject(i) ?: continue
                val item = obj.optJSONObject("item_basic") ?: obj
                val name = item.optString("name", "")
                if (name.isEmpty()) continue
                val price = item.optLong("price", 0L) / 100_000L
                val shopId = item.optLong("shopid", 0L)
                val productId = item.optLong("itemid", 0L).toString()
                if (shopId == 0L || productId == "0") continue
                results.add(
                    ShopeeSearchResult(
                        name = name,
                        price = price,
                        shopId = shopId,
                        productId = productId,
                        url = "https://shopee.vn/i.$shopId.$productId"
                    )
                )
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }
}
