package com.personal.shopeekit.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.personal.shopeekit.R
import com.personal.shopeekit.ShopeeKitApp
import com.personal.shopeekit.features.price.PriceHistoryFeature
import com.personal.shopeekit.features.price.db.TrackedProduct
import com.personal.shopeekit.ui.views.LineChartView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PriceHistoryActivity : AppCompatActivity() {

    private val feature by lazy {
        (application as ShopeeKitApp).features.filterIsInstance<PriceHistoryFeature>().first()
    }

    private lateinit var containerProducts: LinearLayout
    private lateinit var btnAddProduct: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_price_history)

        containerProducts = findViewById(R.id.containerProducts)
        btnAddProduct = findViewById(R.id.btnAddProduct)

        btnAddProduct.setOnClickListener { showAddProductDialog() }

        observeProducts()
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
            containerProducts.removeAllViews()
            if (products.isEmpty()) {
                val empty = TextView(this).apply {
                    text = "Chưa có sản phẩm nào. Nhấn + để thêm."
                    setPadding(32, 32, 32, 32)
                    setTextColor(getColor(R.color.text_secondary))
                }
                containerProducts.addView(empty)
                return@runOnUiThread
            }
            products.forEach { product -> addProductCard(product) }
        }
    }

    private fun addProductCard(product: TrackedProduct) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_product_price, containerProducts, false)

        card.findViewById<TextView>(R.id.tvProductName).text = product.productName
        val chartView = card.findViewById<LineChartView>(R.id.lineChart)
        val tvBestPrice = card.findViewById<TextView>(R.id.tvBestPrice)
        val tvRecommendation = card.findViewById<TextView>(R.id.tvRecommendation)

        lifecycleScope.launch {
            feature.db.priceDao().getPriceHistory(product.productId).collectLatest { records ->
                chartView.records = records
                if (records.isNotEmpty()) {
                    val latest = records.first()
                    card.findViewById<TextView>(R.id.tvCurrentPrice).text =
                        "Hiện tại: ₫${formatPrice(latest.price)}"
                }

                val bestPrice = withContext(Dispatchers.IO) {
                    feature.calculator.calculate(product.productId)
                }
                if (bestPrice != null) {
                    tvBestPrice.text = "Best price: ₫${formatPrice(bestPrice.effectivePrice)}"
                    tvRecommendation.text = bestPrice.recommendation
                }
            }
        }

        card.setOnLongClickListener {
            showProductOptions(product)
            true
        }

        containerProducts.addView(card)
    }

    private fun showAddProductDialog() {
        val input = EditText(this).apply {
            hint = "https://shopee.vn/..."
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Thêm sản phẩm theo dõi giá")
            .setView(input)
            .setPositiveButton("Thêm") { _, _ ->
                val url = input.text.toString().trim()
                if (feature.parseShopeeUrl(url) != null) {
                    feature.trackProduct(url)
                    Toast.makeText(this, "Đã thêm sản phẩm", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "URL Shopee không hợp lệ", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showProductOptions(product: TrackedProduct) {
        AlertDialog.Builder(this)
            .setTitle(product.productName)
            .setItems(arrayOf("Đặt ngưỡng cảnh báo giá", "Xóa theo dõi")) { _, which ->
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

    private fun formatPrice(vnd: Long) = String.format("%,d", vnd).replace(',', '.')
}
