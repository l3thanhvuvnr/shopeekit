package com.personal.shopeekit.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.github.mikephil.charting.charts.LineChart
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.personal.shopeekit.R
import com.personal.shopeekit.ShopeeKitApp
import com.personal.shopeekit.databinding.ActivityPriceHistoryBinding
import com.personal.shopeekit.databinding.ItemProductPriceBinding
import com.personal.shopeekit.features.price.PriceHistoryFeature
import com.personal.shopeekit.features.price.ShopeeSearchResult
import com.personal.shopeekit.features.price.db.PriceRecord
import com.personal.shopeekit.features.price.db.TrackedProduct
import com.personal.shopeekit.ui.views.PriceChartRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PriceHistoryActivity : AppCompatActivity() {

    private val vm: PriceHistoryViewModel by viewModels()
    private val feature by lazy {
        (application as ShopeeKitApp).features.filterIsInstance<PriceHistoryFeature>().first()
    }

    private lateinit var binding: ActivityPriceHistoryBinding

    private lateinit var btnTabTracking: MaterialButton
    private lateinit var btnTabSearch: MaterialButton
    private lateinit var panelTracking: LinearLayout
    private lateinit var panelSearch: LinearLayout
    private lateinit var containerProducts: LinearLayout
    private lateinit var tvEmptyTracking: TextView
    private lateinit var etSearchQuery: EditText
    private lateinit var btnSearch: MaterialButton
    private lateinit var btnAddByUrl: MaterialButton
    private lateinit var progressSearch: ProgressBar
    private lateinit var scrollResults: ScrollView
    private lateinit var containerSearchResults: LinearLayout

    // Per-card price-history jobs, cancelled before the list is rebuilt.
    private val productJobs = mutableMapOf<String, Job>()
    // Selected chart range per product (defaults to 30 days).
    private val productRange = mutableMapOf<String, PriceChartRenderer.Range>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPriceHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        binding.toolbar.setNavigationOnClickListener { finish() }

        bindViews()
        setupTabs()
        setupSearch()
        observeProducts()
        observeSearch()
    }

    private fun bindViews() {
        btnTabTracking = binding.btnTabTracking
        btnTabSearch = binding.btnTabSearch
        panelTracking = binding.panelTracking
        panelSearch = binding.panelSearch
        containerProducts = binding.containerProducts
        tvEmptyTracking = binding.tvEmptyTracking
        etSearchQuery = binding.etSearchQuery
        btnSearch = binding.btnSearch
        btnAddByUrl = binding.btnAddByUrl
        progressSearch = binding.progressSearch
        scrollResults = binding.scrollResults
        containerSearchResults = binding.containerSearchResults
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
            btnTabSearch.backgroundTintList = null
            btnTabSearch.setTextColor(getColor(R.color.shopee_orange))
        } else {
            panelTracking.visibility = View.GONE
            panelSearch.visibility = View.VISIBLE
            btnTabSearch.setBackgroundTintList(getColorStateList(R.color.shopee_orange))
            btnTabSearch.setTextColor(getColor(android.R.color.white))
            btnTabTracking.backgroundTintList = null
            btnTabTracking.setTextColor(getColor(R.color.shopee_orange))
            etSearchQuery.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    private fun setupSearch() {
        btnSearch.setOnClickListener { triggerSearch() }
        btnAddByUrl.setOnClickListener { showUrlDialog() }
        etSearchQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                triggerSearch(); true
            } else false
        }
    }

    private fun triggerSearch() {
        val query = etSearchQuery.text.toString().trim()
        if (query.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etSearchQuery.windowToken, 0)
        lifecycleScope.launch {
            if (!vm.hasCookie()) {
                showCookieSetupDialog(onConfigured = { vm.search(query, debounceMs = 0) })
            } else {
                vm.search(query, debounceMs = 0)
            }
        }
    }

    private fun observeSearch() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.searchState.collectLatest { state ->
                    progressSearch.visibility =
                        if (state is SearchUiState.Loading) View.VISIBLE else View.GONE
                    containerSearchResults.removeAllViews()
                    when (state) {
                        is SearchUiState.Results -> state.items.forEach { addSearchResultRow(it) }
                        SearchUiState.Empty -> addSearchInfo(
                            "Không tìm thấy sản phẩm cho \"${etSearchQuery.text}\""
                        )
                        is SearchUiState.AuthError -> {
                            addSearchInfo("❌ ${state.message}", isError = true)
                            addResyncButton()
                        }
                        is SearchUiState.Error -> addSearchInfo("❌ ${state.message}", isError = true)
                        else -> {}
                    }
                }
            }
        }
    }

    private fun addSearchInfo(message: String, isError: Boolean = false) {
        containerSearchResults.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(getColor(if (isError) R.color.status_failed else R.color.text_secondary))
            gravity = android.view.Gravity.CENTER
            setPadding(24, 48, 24, 24)
        })
    }

    private fun addResyncButton() {
        containerSearchResults.addView(MaterialButton(this).apply {
            text = "🔄 Đồng bộ tài khoản Shopee"
            textSize = 14f
            setOnClickListener {
                startActivity(Intent(this@PriceHistoryActivity, ShopeeCookieSyncActivity::class.java))
            }
        })
    }

    private fun addSearchResultRow(result: ShopeeSearchResult) {
        val card = MaterialCardView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(12, 4, 12, 4) }
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
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = 12 }
            setOnClickListener {
                feature.trackProduct(result.url)
                Toast.makeText(
                    this@PriceHistoryActivity,
                    "Đang theo dõi: ${result.name.take(30)}...", Toast.LENGTH_SHORT
                ).show()
                showTab(isTracking = true)
            }
        }
        row.addView(info); row.addView(btn); card.addView(row)
        containerSearchResults.addView(card)
    }

    private fun showCookieSetupDialog(onConfigured: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        layout.addView(TextView(this).apply {
            text = "Shopee search cần cookie đăng nhập.\n\n" +
                "Cách lấy:\n1. Mở Chrome → vào shopee.vn → đăng nhập\n" +
                "2. Nhấn F12 → Console\n3. Gõ: copy(document.cookie)\n4. Paste vào đây:"
            textSize = 13f
            setPadding(0, 0, 0, 16)
        })
        val etCookie = EditText(this).apply { hint = "Paste cookie..."; minLines = 3; maxLines = 6; textSize = 12f }
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
                lifecycleScope.launch {
                    vm.saveCookie(cookie)
                    Toast.makeText(this@PriceHistoryActivity, "✅ Cookie đã lưu!", Toast.LENGTH_SHORT).show()
                    onConfigured()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ─── Tracking list ──────────────────────────────────────────────────────────

    private fun observeProducts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.products.collectLatest { renderProductList(it) }
            }
        }
    }

    private fun renderProductList(products: List<TrackedProduct>) {
        productJobs.values.forEach { it.cancel() }
        productJobs.clear()
        containerProducts.removeAllViews()
        if (products.isEmpty()) {
            tvEmptyTracking.visibility = View.VISIBLE
        } else {
            tvEmptyTracking.visibility = View.GONE
            products.forEach { addProductCard(it) }
        }
    }

    private fun addProductCard(product: TrackedProduct) {
        val cardBinding = ItemProductPriceBinding.inflate(layoutInflater, containerProducts, false)
        val card = cardBinding.root
        cardBinding.tvProductName.text = product.productName
        val chart = cardBinding.lineChart
        val tvCurrentPrice = cardBinding.tvCurrentPrice
        val tvBestPrice = cardBinding.tvBestPrice
        val tvRecommendation = cardBinding.tvRecommendation
        val cardRecommendation = cardBinding.cardRecommendation
        val btnOptions = cardBinding.btnProductOptions
        val chipGroup = cardBinding.chipGroupRange

        PriceChartRenderer.setUp(chart)
        btnOptions.setOnClickListener { showProductOptions(product) }
        card.setOnLongClickListener { showProductOptions(product); true }

        val range = productRange[product.productId] ?: PriceChartRenderer.Range.MONTH
        productRange[product.productId] = range
        when (range) {
            PriceChartRenderer.Range.WEEK -> chipGroup.check(R.id.chip7d)
            PriceChartRenderer.Range.MONTH -> chipGroup.check(R.id.chip30d)
            PriceChartRenderer.Range.ALL -> chipGroup.check(R.id.chipAll)
        }

        var latestRecords: List<PriceRecord> = emptyList()
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val r = when (checkedIds.firstOrNull()) {
                R.id.chip7d -> PriceChartRenderer.Range.WEEK
                R.id.chipAll -> PriceChartRenderer.Range.ALL
                else -> PriceChartRenderer.Range.MONTH
            }
            productRange[product.productId] = r
            PriceChartRenderer.render(chart, latestRecords, r)
        }

        val job = lifecycleScope.launch {
            vm.priceRepo.observePriceHistory(product.productId).collectLatest { records ->
                latestRecords = records
                PriceChartRenderer.render(chart, records, productRange[product.productId]!!)
                if (records.isNotEmpty()) {
                    tvCurrentPrice.text = "₫${formatPrice(records.first().price)}"
                }
                val best = withContext(Dispatchers.IO) { feature.calculator.calculate(product.productId) }
                if (best != null) {
                    tvBestPrice.text = "₫${formatPrice(best.effectivePrice)}"
                    tvRecommendation.text = best.recommendation
                    cardRecommendation.visibility = View.VISIBLE
                } else {
                    cardRecommendation.visibility = View.GONE
                }
            }
        }
        productJobs[product.productId] = job
        containerProducts.addView(card)
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply { hint = "https://shopee.vn/..."; setPadding(32, 24, 32, 24) }
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
            .setItems(
                arrayOf("🔔 Đặt ngưỡng cảnh báo", "⏱️ Tần suất cập nhật", "📤 Xuất CSV", "🗑️ Xóa theo dõi")
            ) { _, which ->
                when (which) {
                    0 -> showSetThresholdDialog(product)
                    1 -> showPollIntervalDialog(product)
                    2 -> exportCsv(product)
                    3 -> {
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
                vm.setThreshold(product.productId, threshold)
                Toast.makeText(this, "Đã đặt ngưỡng ₫${formatPrice(threshold)}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showPollIntervalDialog(product: TrackedProduct) {
        val options = arrayOf("1 giờ", "4 giờ", "12 giờ", "24 giờ")
        val hours = intArrayOf(1, 4, 12, 24)
        val current = hours.indexOf(product.pollIntervalHours).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Tần suất cập nhật giá")
            .setSingleChoiceItems(options, current) { dialog, which ->
                lifecycleScope.launch {
                    vm.priceRepo.updatePollInterval(product.productId, hours[which])
                    feature.schedulePoller(product.productId, product.shopId, hours[which])
                    Toast.makeText(
                        this@PriceHistoryActivity,
                        "Cập nhật mỗi ${options[which]}", Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /** Export this product's full price history to a CSV file and share it. */
    private fun exportCsv(product: TrackedProduct) {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                vm.priceRepo.getHistoryOnce(product.productId)
            }
            if (records.isEmpty()) {
                Toast.makeText(this@PriceHistoryActivity, "Chưa có dữ liệu để xuất", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sb = StringBuilder("timestamp,datetime,price,originalPrice,discountPercent\n")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            records.sortedBy { it.timestamp }.forEach {
                sb.append("${it.timestamp},${fmt.format(java.util.Date(it.timestamp))}," +
                    "${it.price},${it.originalPrice},${it.discountPercent}\n")
            }
            val file = File(cacheDir, "price_${product.productId}.csv").apply { writeText(sb.toString()) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this@PriceHistoryActivity, "$packageName.fileprovider", file
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Xuất lịch sử giá"))
        }
    }

    private fun formatPrice(vnd: Long) = String.format("%,d", vnd).replace(',', '.')
}
