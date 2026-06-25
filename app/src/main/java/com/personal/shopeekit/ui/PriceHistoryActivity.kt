package com.personal.shopeekit.ui

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.personal.shopeekit.R
import com.personal.shopeekit.ShopeeKitApp
import com.personal.shopeekit.core.network.ShopeeHttpClient
import com.personal.shopeekit.features.price.PriceHistoryFeature
import com.personal.shopeekit.features.price.db.TrackedProduct
import com.personal.shopeekit.ui.views.LineChartView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PriceHistoryActivity : AppCompatActivity() {

    private val feature by lazy {
        (application as ShopeeKitApp).features.filterIsInstance<PriceHistoryFeature>().first()
    }

    // Tab buttons
    private lateinit var btnTabTracking: MaterialButton
    private lateinit var btnTabSearch: MaterialButton

    // Panels
    private lateinit var panelTracking: LinearLayout
    private lateinit var panelSearch: LinearLayout

    // Tracking panel
    private lateinit var containerProducts: LinearLayout
    private lateinit var tvEmptyTracking: TextView

    // Tracks per-product price-history coroutine jobs so they can be cancelled
    // before renderProductList() rebuilds the list (prevents coroutine leak).
    private val productJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    // Search panel
    private lateinit var etSearchQuery: EditText
    private lateinit var btnSearch: MaterialButton
    private lateinit var btnAddByUrl: MaterialButton
    private lateinit var progressSearch: ProgressBar
    private lateinit var scrollResults: ScrollView
    private lateinit var containerSearchResults: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_price_history)
        supportActionBar?.hide()
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            ?.setNavigationOnClickListener { finish() }

        bindViews()
        setupTabs()
        setupSearch()
        observeProducts()
    }

    private fun bindViews() {
        btnTabTracking = findViewById(R.id.btnTabTracking)
        btnTabSearch = findViewById(R.id.btnTabSearch)
        panelTracking = findViewById(R.id.panelTracking)
        panelSearch = findViewById(R.id.panelSearch)
        containerProducts = findViewById(R.id.containerProducts)
        tvEmptyTracking = findViewById(R.id.tvEmptyTracking)
        etSearchQuery = findViewById(R.id.etSearchQuery)
        btnSearch = findViewById(R.id.btnSearch)
        btnAddByUrl = findViewById(R.id.btnAddByUrl)
        progressSearch = findViewById(R.id.progressSearch)
        scrollResults = findViewById(R.id.scrollResults)
        containerSearchResults = findViewById(R.id.containerSearchResults)
    }

    private fun setupTabs() {
        btnTabTracking.setOnClickListener { showTab(isTracking = true) }
        btnTabSearch.setOnClickListener { showTab(isTracking = false) }
    }

    private fun showTab(isTracking: Boolean) {
        if (isTracking) {
            panelTracking.visibility = View.VISIBLE
            panelSearch.visibility = View.GONE
            btnTabTracking.setBackgroundTintList(getColorStateList(R.color.shopee_orange))
            btnTabTracking.setTextColor(getColor(android.R.color.white))
            // reset search button to outlined
            btnTabSearch.backgroundTintList = null
            btnTabSearch.setTextColor(getColor(R.color.shopee_orange))
        } else {
            panelTracking.visibility = View.GONE
            panelSearch.visibility = View.VISIBLE
            btnTabSearch.setBackgroundTintList(getColorStateList(R.color.shopee_orange))
            btnTabSearch.setTextColor(getColor(android.R.color.white))
            // reset tracking button to outlined
            btnTabTracking.backgroundTintList = null
            btnTabTracking.setTextColor(getColor(R.color.shopee_orange))
            // Focus search field
            etSearchQuery.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupSearch() {
        btnSearch.setOnClickListener { performSearch() }
        btnAddByUrl.setOnClickListener { showUrlDialog() }

        etSearchQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                performSearch()
                true
            } else false
        }
    }

    private fun performSearch() {
        val query = etSearchQuery.text.toString().trim()
        if (query.isEmpty()) return

        // Dismiss keyboard
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etSearchQuery.windowToken, 0)

        // Check cookie before searching — Shopee search requires login cookie
        val config = com.personal.shopeekit.core.storage.ShopeeConfig(this)
        if (config.getCookieSync().isBlank()) {
            showCookieSetupDialog(onConfigured = { doSearch(query) })
            return
        }

        doSearch(query)
    }

    private fun doSearch(query: String) {
        progressSearch.visibility = View.VISIBLE
        containerSearchResults.removeAllViews()

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { searchShopeeProducts(query) }
            progressSearch.visibility = View.GONE

            when {
                results.isNotEmpty() -> results.forEach { addSearchResultRow(it) }
                lastSearchError != null -> {
                    addSearchErrorState(lastSearchError!!)
                    // If it's an auth error, show the cookie sync hint
                    if (lastSearchError!!.contains("đăng nhập") || lastSearchError!!.contains("90309999")) {
                        Toast.makeText(
                            this@PriceHistoryActivity,
                            "⚠️ Cần đồng bộ lại tài khoản Shopee",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                else -> addSearchEmptyState()
            }
        }
    }

    private fun addSearchErrorState(error: String) {
        val tv = TextView(this).apply {
            text = "❌ $error"
            textSize = 14f
            setTextColor(getColor(R.color.status_failed))
            setPadding(24, 32, 24, 24)
            setLineSpacing(4f, 1f)
        }
        containerSearchResults.addView(tv)

        // Add re-sync button if auth error
        if (error.contains("đăng nhập") || error.contains("90309999")) {
            val btn = com.google.android.material.button.MaterialButton(this).apply {
                text = "🔄 Đồng bộ tài khoản Shopee"
                textSize = 14f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(24, 8, 24, 24)
                layoutParams = lp
                setOnClickListener {
                    startActivity(android.content.Intent(
                        this@PriceHistoryActivity,
                        ShopeeCookieSyncActivity::class.java
                    ))
                }
            }
            containerSearchResults.addView(btn)
        }
    }

    private fun showCookieSetupDialog(onConfigured: () -> Unit) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        layout.addView(TextView(this).apply {
            text = "Shopee search cần cookie đăng nhập.\n\n" +
                "Cách lấy:\n" +
                "1. Mở Chrome → vào shopee.vn → đăng nhập\n" +
                "2. Nhấn F12 → Console\n" +
                "3. Gõ: copy(document.cookie)\n" +
                "4. Paste vào đây:"
            textSize = 13f
            setPadding(0, 0, 0, 16)
        })
        val etCookie = android.widget.EditText(this).apply {
            hint = "Paste cookie ở đây..."
            minLines = 3
            maxLines = 6
            textSize = 12f
        }
        layout.addView(etCookie)

        AlertDialog.Builder(this)
            .setTitle("⚙️ Cấu hình Cookie Shopee")
            .setView(layout)
            .setPositiveButton("Lưu & Tìm") { _, _ ->
                val cookie = etCookie.text.toString().trim()
                if (cookie.isBlank()) {
                    Toast.makeText(this, "Cookie không được để trống", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    com.personal.shopeekit.core.storage.ShopeeConfig(this@PriceHistoryActivity)
                        .saveCookie(cookie)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@PriceHistoryActivity, "✅ Cookie đã lưu!", Toast.LENGTH_SHORT).show()
                        onConfigured()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun addSearchEmptyState() {
        val tv = TextView(this).apply {
            text = "Không tìm thấy sản phẩm nào cho \"${etSearchQuery.text}\""
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            gravity = android.view.Gravity.CENTER
            setPadding(24, 48, 24, 24)
        }
        containerSearchResults.addView(tv)
    }

    private fun addSearchResultRow(result: ShopeeSearchResult) {
        val card = MaterialCardView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(12, 4, 12, 4)
            layoutParams = lp
            radius = 16f * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 1
            strokeColor = getColor(R.color.card_stroke)
            setCardBackgroundColor(getColor(R.color.surface))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 14, 16, 14)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        info.addView(TextView(this).apply {
            text = result.name
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = "₫${formatPrice(result.price)}"
            textSize = 13f
            setTextColor(getColor(R.color.shopee_orange))
            setPadding(0, 3, 0, 0)
        })

        val btn = MaterialButton(this).apply {
            text = "+ Theo dõi"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = 12 }
            setOnClickListener {
                feature.trackProduct(result.url)
                Toast.makeText(
                    this@PriceHistoryActivity,
                    "Đang theo dõi: ${result.name.take(30)}...",
                    Toast.LENGTH_SHORT
                ).show()
                showTab(isTracking = true)
            }
        }

        row.addView(info)
        row.addView(btn)
        card.addView(row)
        containerSearchResults.addView(card)
    }

    private fun observeProducts() {
        lifecycleScope.launch {
            feature.db.priceDao().getActiveProducts().collectLatest { products ->
                renderProductList(products)
            }
        }
    }

    private fun renderProductList(products: List<TrackedProduct>) {
        runOnUiThread {
            // Cancel all per-card price-history coroutines before removing views
            // to prevent orphaned coroutines writing to detached views.
            productJobs.values.forEach { it.cancel() }
            productJobs.clear()
            containerProducts.removeAllViews()
            if (products.isEmpty()) {
                tvEmptyTracking.visibility = View.VISIBLE
            } else {
                tvEmptyTracking.visibility = View.GONE
                products.forEach { product -> addProductCard(product) }
            }
        }
    }

    private fun addProductCard(product: TrackedProduct) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_product_price, containerProducts, false)

        card.findViewById<TextView>(R.id.tvProductName).text = product.productName
        val chartView = card.findViewById<LineChartView>(R.id.lineChart)
        val tvCurrentPrice = card.findViewById<TextView>(R.id.tvCurrentPrice)
        val tvBestPrice = card.findViewById<TextView>(R.id.tvBestPrice)
        val tvRecommendation = card.findViewById<TextView>(R.id.tvRecommendation)
        val cardRecommendation = card.findViewById<MaterialCardView>(R.id.cardRecommendation)
        val btnOptions = card.findViewById<ImageButton>(R.id.btnProductOptions)

        btnOptions.setOnClickListener { showProductOptions(product) }
        card.setOnLongClickListener { showProductOptions(product); true }

        lifecycleScope.launch {
            feature.db.priceDao().getPriceHistory(product.productId).collectLatest { records ->
                chartView.records = records
                if (records.isNotEmpty()) {
                    tvCurrentPrice.text = "₫${formatPrice(records.first().price)}"
                }

                val bestPrice = withContext(Dispatchers.IO) {
                    feature.calculator.calculate(product.productId)
                }
                if (bestPrice != null) {
                    tvBestPrice.text = "₫${formatPrice(bestPrice.effectivePrice)}"
                    tvRecommendation.text = bestPrice.recommendation
                    cardRecommendation.visibility = View.VISIBLE
                } else {
                    cardRecommendation.visibility = View.GONE
                }
            }
        }.also { productJobs[product.productId] = it }

        containerProducts.addView(card)
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://shopee.vn/..."
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Dán URL sản phẩm Shopee")
            .setView(input)
            .setPositiveButton("Thêm") { _, _ ->
                val url = input.text.toString().trim()
                if (feature.parseShopeeUrl(url) != null) {
                    feature.trackProduct(url)
                    Toast.makeText(this, "Đã thêm sản phẩm", Toast.LENGTH_SHORT).show()
                    showTab(isTracking = true)
                } else {
                    Toast.makeText(this, "URL Shopee không hợp lệ", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showProductOptions(product: TrackedProduct) {
        AlertDialog.Builder(this)
            .setTitle(product.productName.take(50))
            .setItems(arrayOf("🔔 Đặt ngưỡng cảnh báo", "🗑️ Xóa theo dõi")) { _, which ->
                when (which) {
                    0 -> showSetThresholdDialog(product)
                    1 -> {
                        feature.stopTracking(product.productId)
                        Toast.makeText(this, "Đã xóa", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showSetThresholdDialog(product: TrackedProduct) {
        val input = EditText(this).apply {
            hint = "Ngưỡng giá VND (vd: 500000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Cảnh báo khi giá dưới...")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val threshold = input.text.toString().toLongOrNull() ?: return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    feature.db.priceDao().updateAlertThreshold(product.productId, threshold)
                }
                Toast.makeText(this, "Đã đặt ngưỡng ₫${formatPrice(threshold)}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ─── Shopee search API ────────────────────────────────────────────────────

    data class ShopeeSearchResult(
        val name: String,
        val price: Long,
        val shopId: Long,
        val productId: String,
        val url: String
    )

    // Holds the last search error message to show in UI
    private var lastSearchError: String? = null

    private fun searchShopeeProducts(keyword: String): List<ShopeeSearchResult> {
        lastSearchError = null
        return try {
            val config = com.personal.shopeekit.core.storage.ShopeeConfig(this)
            val cookie = config.getCookieSync()

            // Extract csrftoken from saved cookie for x-csrftoken header
            val csrfToken = Regex("""(?:^|;\s*)csrftoken=([^;]+)""").find(cookie)
                ?.groupValues?.get(1)?.trim() ?: ""

            val encodedKw = java.net.URLEncoder.encode(keyword, "UTF-8")
            val url = "${ShopeeHttpClient.baseUrl}/api/v4/search/search_items" +
                "?by=relevancy&keyword=$encodedKw&limit=20&newest=0" +
                "&order=desc&page_type=search&scenario=PAGE_GLOBAL_SEARCH&version=2"

            Log.d("ShopeeSearch", "▶ base=${ShopeeHttpClient.baseUrl}")
            Log.d("ShopeeSearch", "▶ cookie: ${if (cookie.isNotBlank()) "✓ (${cookie.length}ch)" else "✗ EMPTY"}")
            Log.d("ShopeeSearch", "▶ csrftoken: ${if (csrfToken.isNotBlank()) "✓" else "not found"}")
            Log.d("ShopeeSearch", "▶ URL: $url")

            val extraHeaders = mutableMapOf<String, String>()
            if (csrfToken.isNotBlank()) extraHeaders["X-CSRFToken"] = csrfToken

            val request = ShopeeHttpClient.buildRequest(
                url = url,
                cookie = cookie,
                extraHeaders = extraHeaders
            )
            val response = ShopeeHttpClient.client.newCall(request).execute()
            val code = response.code
            val contentType = response.header("Content-Type") ?: ""
            Log.d("ShopeeSearch", "◀ HTTP $code | Content-Type: $contentType")

            val body = response.body?.string()
            response.close()

            if (body == null) {
                lastSearchError = "Lỗi mạng (HTTP $code, body trống)"
                Log.w("ShopeeSearch", "✗ $lastSearchError")
                return emptyList()
            }
            Log.d("ShopeeSearch", "Body[0..500]: ${body.take(500)}")

            // Try parse Shopee error code first
            try {
                val root = org.json.JSONObject(body)
                val shopeeError = root.optInt("error", 0)
                if (shopeeError != 0) {
                    val isLogin = root.optBoolean("is_login", true)
                    lastSearchError = when (shopeeError) {
                        90309999 ->
                            "Shopee error 90309999 (HTTP $code)\nCookie có thể đã hết hạn.\nVào Đồng bộ tài khoản → đăng nhập lại."
                        else -> "Shopee error $shopeeError (HTTP $code)"
                    }
                    Log.w("ShopeeSearch", "✗ $lastSearchError | is_login=$isLogin | cookie=${cookie.isNotBlank()}")
                    return emptyList()
                }
            } catch (_: Exception) { /* not JSON */ }

            if (!contentType.contains("json", ignoreCase = true)) {
                // Guard against HTML captcha/block pages returned with HTTP 200.
                // Without this check, parseSearchResults() silently swallows the
                // JSONException and doSearch() shows "not found" instead of an error.
                lastSearchError = "Shopee trả về nội dung không hợp lệ (HTTP $code, $contentType).\nThử lại hoặc kiểm tra kết nối."
                Log.w("ShopeeSearch", "✗ $lastSearchError | body[0..200]: ${body.take(200)}")
                return emptyList()
            }

            val results = parseSearchResults(body)
            Log.d("ShopeeSearch", "✓ Parsed ${results.size} results")
            results
        } catch (e: Exception) {
            lastSearchError = "Lỗi kết nối: ${e.message}"
            Log.e("ShopeeSearch", "✗ EXCEPTION ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseSearchResults(json: String): List<ShopeeSearchResult> {
        return try {
            val root = JSONObject(json)
            // Shopee search API returns items at top level: {"items":[...]}
            // Fallback: some versions wrap in {"data":{"items":[...]}}
            val items = root.optJSONArray("items")
                ?: root.optJSONObject("data")?.optJSONArray("items")
                ?: run {
                    Log.w("ShopeeSearch", "✗ No 'items' array found. Top-level keys: ${root.keys().asSequence().toList()}")
                    return emptyList()
                }
            Log.d("ShopeeSearch", "Found items array, length=${items.length()}")
            val results = mutableListOf<ShopeeSearchResult>()
            for (i in 0 until minOf(items.length(), 20)) {
                val obj = items.optJSONObject(i) ?: continue
                // items[i] is either the item_basic directly, or {item_basic: {...}}
                val item = obj.optJSONObject("item_basic") ?: obj
                val name = item.optString("name", "")
                if (name.isEmpty()) continue
                val price = item.optLong("price", 0L) / 100_000L
                val shopId = item.optLong("shopid", 0L)
                val productId = item.optLong("itemid", 0L).toString()
                if (shopId == 0L || productId == "0") continue
                val url = "https://shopee.vn/i.$shopId.$productId"
                results.add(ShopeeSearchResult(name, price, shopId, productId, url))
            }
            results
        } catch (e: Exception) {
            Log.e("ShopeeSearch", "✗ Parse error ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    private fun formatPrice(vnd: Long) = String.format("%,d", vnd).replace(',', '.')
}
