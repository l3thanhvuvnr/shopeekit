package com.personal.shopeekit.ui.setup

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.personal.shopeekit.R
import com.personal.shopeekit.core.storage.ShopeeConfig
import kotlinx.coroutines.launch

/**
 * Screen to configure Shopee auth headers captured via mitmproxy.
 *
 * Setup process (one-time):
 * 1. PC: npm install -g apk-mitm && apk-mitm shopee.apk
 * 2. adb install shopee-patched.apk
 * 3. PC: pip install mitmproxy && mitmproxy --listen-port 8080
 * 4. Android: WiFi → Proxy → PC_IP:8080
 * 5. Open Shopee, browse any product
 * 6. Copy Cookie + User-Agent from mitmproxy to this screen
 */
class TokenSetupActivity : AppCompatActivity() {

    private lateinit var config: ShopeeConfig
    private lateinit var etCookie: EditText
    private lateinit var etUserAgent: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_token_setup)

        config = ShopeeConfig(this)
        etCookie = findViewById(R.id.etCookie)
        etUserAgent = findViewById(R.id.etUserAgent)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        btnSave = findViewById(R.id.btnSave)
        tvStatus = findViewById(R.id.tvStatus)

        loadCurrentConfig()

        btnSave.setOnClickListener { saveConfig() }
    }

    private fun loadCurrentConfig() {
        etCookie.setText(config.getCookieSync())
        etUserAgent.setText(config.getUserAgentSync())
        etBaseUrl.setText(config.getBaseUrlSync())

        tvStatus.text = if (config.isConfigured()) "✅ Config đã được cấu hình"
        else "⚠️ Chưa có config — cần setup mitmproxy"
    }

    private fun saveConfig() {
        val cookie = etCookie.text.toString().trim()
        val ua = etUserAgent.text.toString().trim()
        val baseUrl = etBaseUrl.text.toString().trim()

        if (cookie.isBlank()) {
            Toast.makeText(this, "Cookie không được để trống", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            config.saveCookie(cookie)
            if (ua.isNotBlank()) config.saveUserAgent(ua)
            if (baseUrl.isNotBlank()) config.saveBaseUrl(baseUrl)

            Toast.makeText(this@TokenSetupActivity, "✅ Đã lưu config", Toast.LENGTH_SHORT).show()
            tvStatus.text = "✅ Config đã được lưu"
        }
    }
}
