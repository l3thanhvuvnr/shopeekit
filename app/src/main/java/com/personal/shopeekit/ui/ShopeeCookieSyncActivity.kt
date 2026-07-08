package com.personal.shopeekit.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.personal.shopeekit.core.storage.ShopeeConfig
import com.personal.shopeekit.databinding.ActivityCookieSyncBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebView-based Shopee session sync screen.
 *
 * Cách dùng:
 *  1. Mở màn hình này
 *  2. Trang shopee.vn hiện ra trong WebView
 *  3. Đăng nhập Shopee trong WebView này (khác với app Shopee — đây là session riêng)
 *  4. Sau khi đăng nhập xong, nhấn "Đồng bộ ngay" hoặc tự động sync
 *  5. Cookie được lưu vào ShopeeConfig → dùng cho tất cả các tính năng
 *
 * LƯU Ý: Cookie trong WebView này tồn tại vĩnh viễn, lần sau mở lại sẽ
 * tự động đăng nhập mà không cần nhập lại.
 */
class ShopeeCookieSyncActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CookieSync"
        private const val SHOPEE_URL = "https://shopee.vn"
        private const val SHOPEE_HOME_URL = "https://shopee.vn/"
    }

    private lateinit var binding: ActivityCookieSyncBinding
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnSync: Button
    private lateinit var btnClose: Button

    private var cookieSynced = false
    private var pageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCookieSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        // Back: navigate WebView history first, then fall through to default.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        progressBar = binding.progressSync
        tvStatus    = binding.tvSyncStatus
        btnSync     = binding.btnManualSync
        btnClose    = binding.btnClose
        webView     = binding.webViewShopee

        setupWebView()

        btnSync.setOnClickListener { extractAndSaveCookies() }
        btnClose.setOnClickListener { finish() }

        tvStatus.text = "Đang tải shopee.vn..."
        webView.loadUrl(SHOPEE_HOME_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            loadWithOverviewMode = true
            useWideViewPort      = true
            setSupportZoom(true)
            builtInZoomControls  = false
            userAgentString      = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // Accept all cookies (3rd party needed for Shopee login OAuth)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished: $url")
                pageLoaded = true
                checkLoginAndAutoSync()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Cho phép TẤT CẢ subdomain của shopee.vn và các redirect OAuth phổ biến
                val host = request.url.host ?: return false
                val allowed = host.endsWith("shopee.vn") ||
                    host.endsWith("shopee.com") ||
                    host.endsWith("facebook.com") ||
                    host.endsWith("accounts.google.com") ||
                    host.endsWith("appleid.apple.com")
                return if (allowed) {
                    false // WebView tự xử lý
                } else {
                    Log.d(TAG, "Blocked: ${request.url}")
                    true
                }
            }
        }
    }

    /** Kiểm tra cookie sau mỗi trang load — auto sync nếu đã đăng nhập */
    private fun checkLoginAndAutoSync() {
        val cookies = CookieManager.getInstance().getCookie(SHOPEE_URL) ?: run {
            updateStatusNotLoggedIn()
            return
        }

        Log.d(TAG, "Cookie present: ${!cookies.isNullOrBlank()} (${cookies?.length ?: 0} chars)")
        val isLoggedIn = isShopeeLoggedIn(cookies)
        Log.d(TAG, "isLoggedIn=$isLoggedIn")

        if (isLoggedIn) {
            if (!cookieSynced) {
                tvStatus.text = "✅ Phát hiện đăng nhập — đang đồng bộ cookie..."
                extractAndSaveCookies()
            }
        } else {
            updateStatusNotLoggedIn()
        }
    }

    private fun updateStatusNotLoggedIn() {
        if (!cookieSynced) {
            tvStatus.text = "👆 Đăng nhập Shopee bên dưới, sau đó nhấn \"Đồng bộ ngay\""
        }
    }

    /**
     * SPC_U = user ID, chỉ có khi đăng nhập.
     * Giá trị hợp lệ là số > 0, không phải "-1" hay rỗng.
     */
    private fun isShopeeLoggedIn(cookies: String): Boolean {
        val spcU = Regex("""SPC_U=([^;]+)""").find(cookies)?.groupValues?.get(1)?.trim()
        if (spcU.isNullOrBlank()) return false
        if (spcU == "-1" || spcU == "0" || spcU == "-") return false
        return true
    }

    /** Lấy cookie từ WebView và lưu vào ShopeeConfig */
    private fun extractAndSaveCookies() {
        CookieManager.getInstance().flush()

        val cookies = CookieManager.getInstance().getCookie(SHOPEE_URL)
        Log.d(TAG, "Extracting cookie, present=${!cookies.isNullOrBlank()}, len=${cookies?.length ?: 0}")

        if (cookies.isNullOrBlank()) {
            tvStatus.text = "⚠️ Chưa có cookie — hãy đăng nhập Shopee trong khung bên dưới rồi nhấn lại"
            Toast.makeText(this, "Chưa có cookie, đăng nhập trước", Toast.LENGTH_LONG).show()
            return
        }

        if (!isShopeeLoggedIn(cookies)) {
            tvStatus.text = "⚠️ Chưa phát hiện đăng nhập — hãy đăng nhập Shopee trong khung bên dưới rồi nhấn lại"
            Toast.makeText(this, "Đăng nhập Shopee trong WebView trước", Toast.LENGTH_LONG).show()
            return
        }

        cookieSynced = true
        Log.i(TAG, "Saving cookie len=${cookies.length}")

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ShopeeConfig(this@ShopeeCookieSyncActivity).saveCookie(cookies)
            }
            val spcU = Regex("""SPC_U=([^;]+)""").find(cookies)?.groupValues?.get(1) ?: "?"
            tvStatus.text = "✅ Đồng bộ thành công! User ID: $spcU"
            btnSync.text  = "🔄 Đồng bộ lại"
            Toast.makeText(this@ShopeeCookieSyncActivity, "✅ Cookie đã lưu!", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
