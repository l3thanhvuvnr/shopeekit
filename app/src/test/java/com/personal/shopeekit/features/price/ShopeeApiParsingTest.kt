package com.personal.shopeekit.features.price

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Shopee public API response parsing.
 * Tests pure parsing logic — no network calls.
 */
class ShopeeApiParsingTest {

    // ─── Search API response parsing ────────────────────────────────────────────

    @Test
    fun `parseSearchResults handles top-level items format (real Shopee API)`() {
        // Real Shopee search API returns items at top level: {"items":[{item_basic:{...}},...]}
        val json = buildSearchResponseTopLevel(
            name = "Tai Nghe Bluetooth AirPods",
            price = 29900000L,
            shopId = 123456L,
            itemId = 987654L
        )
        val results = parseSearchResults(json)
        assertEquals(1, results.size)
        val r = results[0]
        assertEquals("Tai Nghe Bluetooth AirPods", r.name)
        assertEquals(299L, r.price)
        assertEquals(123456L, r.shopId)
        assertEquals("987654", r.productId)
        assertEquals("https://shopee.vn/i.123456.987654", r.url)
    }

    @Test
    fun `parseSearchResults handles data-wrapped format (fallback)`() {
        // Fallback format: {"data":{"items":[...]}}
        val json = buildSearchResponse(
            name = "Tai Nghe Bluetooth AirPods",
            price = 29900000L,
            shopId = 123456L,
            itemId = 987654L
        )
        val results = parseSearchResults(json)
        assertEquals(1, results.size)
        assertEquals("Tai Nghe Bluetooth AirPods", results[0].name)
        assertEquals(299L, results[0].price)
    }

    @Test
    fun `parseSearchResults extracts name, price, shopId, productId`() {
        val json = buildSearchResponseTopLevel(
            name = "Tai Nghe Bluetooth AirPods",
            price = 29900000L,   // 299k VND × 100000
            shopId = 123456L,
            itemId = 987654L
        )
        val results = parseSearchResults(json)
        assertEquals(1, results.size)
        val r = results[0]
        assertEquals("Tai Nghe Bluetooth AirPods", r.name)
        assertEquals(299L, r.price)  // after / 100000
        assertEquals(123456L, r.shopId)
        assertEquals("987654", r.productId)
        assertEquals("https://shopee.vn/i.123456.987654", r.url)
    }

    @Test
    fun `parseSearchResults skips items with empty name`() {
        val json = buildSearchResponseTopLevel(name = "", price = 10000000L, shopId = 1L, itemId = 2L)
        val results = parseSearchResults(json)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseSearchResults skips items with zero shopId`() {
        val json = buildSearchResponseTopLevel(name = "Product", price = 10000000L, shopId = 0L, itemId = 2L)
        val results = parseSearchResults(json)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseSearchResults skips items with zero itemId`() {
        val json = buildSearchResponseTopLevel(name = "Product", price = 10000000L, shopId = 1L, itemId = 0L)
        val results = parseSearchResults(json)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseSearchResults returns empty list on malformed JSON`() {
        val results = parseSearchResults("{not valid json}")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseSearchResults returns empty list when data missing`() {
        val results = parseSearchResults("""{"error": 0}""")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `price is correctly divided from Shopee micro-unit`() {
        // Shopee stores 150000 VND as 15000000000 (×100000)
        val json = buildSearchResponseTopLevel(name = "Test", price = 15000000000L, shopId = 1L, itemId = 1L)
        val results = parseSearchResults(json)
        assertEquals(1, results.size)
        assertEquals(150000L, results[0].price)
    }

    @Test
    fun `parseSearchResults caps at 20 results`() {
        val itemsArray = (1..30).joinToString(",") { i ->
            buildItemBasicJson("Product $i", 10000000L, i.toLong(), i.toLong())
        }
        // Top-level format
        val json = """{"items":[${itemsArray}],"total_count":30}"""
        val results = parseSearchResults(json)
        assertTrue("Should be capped at 20, got ${results.size}", results.size <= 20)
    }

    @Test
    fun `parseSearchResults handles item_basic absent (flat item object)`() {
        // Some API versions return flat items without item_basic wrapper
        val json = """{"items":[{"name":"Laptop Gaming","price":5000000000,"shopid":111,"itemid":222}]}"""
        val results = parseSearchResults(json)
        assertEquals(1, results.size)
        assertEquals("Laptop Gaming", results[0].name)
        assertEquals(50000L, results[0].price)
    }

    // ─── Price API response parsing ───────────────────────────────────────────

    @Test
    fun `parsePriceResponse extracts price and name correctly`() {
        val json = buildPriceResponse(
            price = 29900000L,
            originalPrice = 39900000L,
            name = "iPhone 15 Pro"
        )
        val record = parsePriceResponse(json, "itemId123", 456L)
        assertNotNull(record)
        assertEquals(299L, record!!.price)
        assertEquals(399L, record.originalPrice)
        assertEquals("iPhone 15 Pro", record.productName)
        assertEquals(25, record.discountPercent)
    }

    @Test
    fun `parsePriceResponse returns null when data missing`() {
        val record = parsePriceResponse("""{"error": 1}""", "id", 1L)
        assertNull(record)
    }

    @Test
    fun `parsePriceResponse calculates zero discount when no original price`() {
        val json = buildPriceResponse(price = 10000000L, originalPrice = 0L, name = "Item")
        val record = parsePriceResponse(json, "id", 1L)
        assertNotNull(record)
        assertEquals(0, record!!.discountPercent)
    }

    // ─── URL parsing ───────────────────────────────────────────────────────────

    @Test
    fun `parseShopeeUrl handles dot-format URL`() {
        val feature = PriceHistoryFeature()
        val result = feature.parseShopeeUrl("https://shopee.vn/product-name-i.12345.67890")
        assertNotNull(result)
        assertEquals(12345L, result!!.first)
        assertEquals("67890", result.second)
    }

    @Test
    fun `parseShopeeUrl handles path-format URL`() {
        val feature = PriceHistoryFeature()
        val result = feature.parseShopeeUrl("https://shopee.vn/shop/12345/product/67890")
        assertNotNull(result)
        assertEquals(12345L, result!!.first)
        assertEquals("67890", result.second)
    }

    @Test
    fun `parseShopeeUrl returns null for non-Shopee URL`() {
        val feature = PriceHistoryFeature()
        assertNull(feature.parseShopeeUrl("https://lazada.vn/product/12345"))
        assertNull(feature.parseShopeeUrl("https://example.com"))
        assertNull(feature.parseShopeeUrl("not a url"))
    }

    @Test
    fun `parseShopeeUrl handles URL with query params`() {
        val feature = PriceHistoryFeature()
        val result = feature.parseShopeeUrl("https://shopee.vn/some-product-i.11111.22222?sp_atk=abc&xptdk=xyz")
        assertNotNull(result)
        assertEquals(11111L, result!!.first)
        assertEquals("22222", result.second)
    }

    // ─── Helpers: mirror Activity parsing logic for pure unit testing ──────────

    data class SearchResult(
        val name: String,
        val price: Long,
        val shopId: Long,
        val productId: String,
        val url: String
    )

    data class PriceRecord(
        val productId: String,
        val shopId: Long,
        val productName: String,
        val price: Long,
        val originalPrice: Long,
        val discountPercent: Int
    )

    private fun parseSearchResults(json: String): List<SearchResult> {
        return try {
            val root = JSONObject(json)
            // Real Shopee API: items at top level {"items":[...]}
            // Fallback: {"data":{"items":[...]}}
            val items = root.optJSONArray("items")
                ?: root.optJSONObject("data")?.optJSONArray("items")
                ?: return emptyList()
            val results = mutableListOf<SearchResult>()
            for (i in 0 until minOf(items.length(), 20)) {
                val obj = items.optJSONObject(i) ?: continue
                val item = obj.optJSONObject("item_basic") ?: obj
                val name = item.optString("name", "")
                if (name.isEmpty()) continue
                val price = item.optLong("price", 0L) / 100_000L
                val shopId = item.optLong("shopid", 0L)
                val productId = item.optLong("itemid", 0L).toString()
                if (shopId == 0L || productId == "0") continue
                val url = "https://shopee.vn/i.$shopId.$productId"
                results.add(SearchResult(name, price, shopId, productId, url))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePriceResponse(json: String, productId: String, shopId: Long): PriceRecord? {
        return try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: return null
            val item = data.optJSONObject("item") ?: return null
            val price = item.optLong("price", 0L) / 100_000L
            val originalPrice = item.optLong("price_before_discount", 0L) / 100_000L
            val name = item.optString("name", "Unknown Product")
            val discountPercent = if (originalPrice > 0 && price < originalPrice)
                ((1.0 - price.toDouble() / originalPrice) * 100).toInt()
            else 0
            PriceRecord(
                productId = productId,
                shopId = shopId,
                productName = name,
                price = price,
                originalPrice = if (originalPrice > 0) originalPrice else price,
                discountPercent = discountPercent
            )
        } catch (e: Exception) {
            null
        }
    }

    // ─── JSON builders for test fixtures ──────────────────────────────────────

    private fun buildItemBasicJson(name: String, price: Long, shopId: Long, itemId: Long): String {
        return """{"item_basic":{"name":"$name","price":$price,"shopid":$shopId,"itemid":$itemId}}"""
    }

    /** Real Shopee search API format: {"items":[{item_basic:{...}}],"total_count":N} */
    private fun buildSearchResponseTopLevel(name: String, price: Long, shopId: Long, itemId: Long): String {
        return """{"items":[${buildItemBasicJson(name, price, shopId, itemId)}],"total_count":1}"""
    }

    /** Legacy fallback format: {"data":{"items":[...]}} */
    private fun buildSearchResponse(name: String, price: Long, shopId: Long, itemId: Long): String {
        return """{"data":{"items":[${buildItemBasicJson(name, price, shopId, itemId)}]}}"""
    }

    private fun buildPriceResponse(price: Long, originalPrice: Long, name: String): String {
        return """{"data":{"item":{"price":$price,"price_before_discount":$originalPrice,"name":"$name"}}}"""
    }
}
