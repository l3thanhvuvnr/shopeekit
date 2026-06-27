package com.personal.shopeekit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.personal.shopeekit.R
import com.personal.shopeekit.service.ShopeeAccessibilityService
import com.personal.shopeekit.ui.setup.TokenSetupActivity

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupNavigation()
        checkRequiredPermissions()
        requestNotificationPermissionIfNeeded()
    }

    /** Price-drop alerts need POST_NOTIFICATIONS on Android 13+. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupNavigation() {
        findViewById<MaterialCardView>(R.id.btnCheckoutSniper).setOnClickListener {
            startActivity(Intent(this, CheckoutSniperActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.btnPriceHistory).setOnClickListener {
            startActivity(Intent(this, PriceHistoryActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnTokenSetup).setOnClickListener {
            startActivity(Intent(this, TokenSetupActivity::class.java))
        }

        findViewById<View?>(R.id.btnCookieSync)?.setOnClickListener {
            startActivity(Intent(this, ShopeeCookieSyncActivity::class.java))
        }
    }

    private fun checkRequiredPermissions() {
        if (!isBatteryOptimizationIgnored()) {
            requestIgnoreBatteryOptimization()
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Cần cấp quyền 'Hiển thị trên ứng dụng khác'",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"))
        )
    }

    private fun updatePermissionStatus() {
        val tvAccessibility = findViewById<TextView>(R.id.tvAccessibilityStatus)
        val tvOverlay = findViewById<TextView>(R.id.tvOverlayStatus)
        val tvBattery = findViewById<TextView>(R.id.tvBatteryStatus)

        val accessibilityOk = ShopeeAccessibilityService.getInstance() != null
        tvAccessibility.text = if (accessibilityOk) "✅ Accessibility" else "❌ Accessibility — cần bật"
        tvAccessibility.setTextColor(getColor(if (accessibilityOk) R.color.status_success else R.color.status_failed))
        tvAccessibility.setOnClickListener {
            if (!accessibilityOk) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val overlayOk = Settings.canDrawOverlays(this)
        tvOverlay.text = if (overlayOk) "✅ Overlay" else "❌ Overlay — cần cấp phép"
        tvOverlay.setTextColor(getColor(if (overlayOk) R.color.status_success else R.color.status_failed))

        val batteryOk = isBatteryOptimizationIgnored()
        tvBattery.text = if (batteryOk) "✅ Battery Optimization tắt" else "⚠️ Battery Optimization — cần tắt"
        tvBattery.setTextColor(getColor(if (batteryOk) R.color.status_success else R.color.status_warning))
    }
}
