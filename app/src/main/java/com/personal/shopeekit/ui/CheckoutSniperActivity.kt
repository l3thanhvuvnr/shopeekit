package com.personal.shopeekit.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.personal.shopeekit.R
import com.personal.shopeekit.ShopeeKitApp
import com.personal.shopeekit.features.checkout.*
import com.personal.shopeekit.service.ShopeeAccessibilityService
import com.personal.shopeekit.service.ShopeeKitForegroundService
import com.personal.shopeekit.ui.overlay.CheckoutOverlayView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class CheckoutSniperActivity : AppCompatActivity() {

    // Views
    private lateinit var etHour: EditText
    private lateinit var etMinute: EditText
    private lateinit var etSecond: EditText
    private lateinit var btnPickDate: MaterialButton
    private lateinit var tvSelectedDate: TextView
    private lateinit var rgVoucherPreference: RadioGroup
    private lateinit var rbAutoBest: RadioButton
    private lateinit var rbMaxDiscount: RadioButton
    private lateinit var rbMaxCashback: RadioButton
    private lateinit var rbManualCode: RadioButton
    private lateinit var etManualCode: EditText
    private lateinit var seekRetryTimeout: SeekBar
    private lateinit var tvRetryTimeout: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnEnableAccessibility: MaterialButton
    private lateinit var tvCheckoutStatus: TextView
    private lateinit var tvCheckoutCountdown: TextView
    private lateinit var tvCheckoutDetail: TextView
    private lateinit var btnArmCheckout: MaterialButton
    private lateinit var btnDisarmCheckout: MaterialButton

    private val engine by lazy {
        (application as ShopeeKitApp).features
            .filterIsInstance<CheckoutSniperFeature>()
            .first()
            .engine
    }

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    // Retry timeout steps: 30s, 60s, 90s, 120s(default), 150s, 180s, 240s, 300s, 360s, 420s, 480s, 540s, 600s
    private val timeoutSteps = listOf(30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 360, 420, 480, 540, 600)

    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateCountdown()
            updateAccessibilityStatus()
            countdownHandler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_checkout_sniper)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            ?.setNavigationOnClickListener { finish() }
        // title hardcoded trong XML toolbar — không cần supportActionBar

        bindViews()
        setupListeners()
        observeState()
        countdownHandler.post(countdownRunnable)
    }

    override fun onDestroy() {
        countdownHandler.removeCallbacks(countdownRunnable)
        super.onDestroy()
    }

    private fun bindViews() {
        etHour = findViewById(R.id.etHour)
        etMinute = findViewById(R.id.etMinute)
        etSecond = findViewById(R.id.etSecond)
        btnPickDate = findViewById(R.id.btnPickDate)
        tvSelectedDate = findViewById(R.id.tvSelectedDate)
        rgVoucherPreference = findViewById(R.id.rgVoucherPreference)
        rbAutoBest = findViewById(R.id.rbAutoBest)
        rbMaxDiscount = findViewById(R.id.rbMaxDiscount)
        rbMaxCashback = findViewById(R.id.rbMaxCashback)
        rbManualCode = findViewById(R.id.rbManualCode)
        etManualCode = findViewById(R.id.etManualCode)
        seekRetryTimeout = findViewById(R.id.seekRetryTimeout)
        tvRetryTimeout = findViewById(R.id.tvRetryTimeout)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        tvCheckoutStatus = findViewById(R.id.tvCheckoutStatus)
        tvCheckoutCountdown = findViewById(R.id.tvCheckoutCountdown)
        tvCheckoutDetail = findViewById(R.id.tvCheckoutDetail)
        btnArmCheckout = findViewById(R.id.btnArmCheckout)
        btnDisarmCheckout = findViewById(R.id.btnDisarmCheckout)

        tvSelectedDate.text = "Ngày: ${dateFormat.format(calendar.time)}"
    }

    private fun setupListeners() {
        btnPickDate.setOnClickListener { showDatePicker() }

        rgVoucherPreference.setOnCheckedChangeListener { _, checkedId ->
            etManualCode.visibility = if (checkedId == R.id.rbManualCode) View.VISIBLE else View.GONE
        }

        seekRetryTimeout.max = timeoutSteps.size - 1
        seekRetryTimeout.progress = 3 // default 120s
        updateTimeoutLabel(3)
        seekRetryTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateTimeoutLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnArmCheckout.setOnClickListener { onArmClicked() }
        btnDisarmCheckout.setOnClickListener { onDisarmClicked() }

        btnEnableAccessibility.setOnClickListener { showSetupGuide() }

        // Tap trạng thái accessibility để xem hướng dẫn chi tiết
        tvAccessibilityStatus.setOnClickListener { showSetupGuide() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            engine.state.collectLatest { updateUI(it) }
        }
    }

    private fun showDatePicker() {
        DatePickerDialog(this, { _, y, m, d ->
            calendar.set(y, m, d)
            tvSelectedDate.text = "Ngày: ${dateFormat.format(calendar.time)}"
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateTimeoutLabel(progress: Int) {
        val seconds = timeoutSteps.getOrElse(progress) { 120 }
        tvRetryTimeout.text = when {
            seconds < 60 -> "$seconds giây"
            seconds % 60 == 0 -> "${seconds / 60} phút${if (seconds == 120) " (mặc định)" else ""}"
            else -> "${seconds / 60} phút ${seconds % 60} giây"
        }
    }

    private fun buildConfig(): CheckoutConfig? {
        val hour = etHour.text.toString().toIntOrNull() ?: run {
            Toast.makeText(this, "Nhập giờ hợp lệ (0-23)", Toast.LENGTH_SHORT).show()
            return null
        }
        val minute = etMinute.text.toString().toIntOrNull() ?: 0
        val second = etSecond.text.toString().toIntOrNull() ?: 0

        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            Toast.makeText(this, "Giờ không hợp lệ", Toast.LENGTH_SHORT).show()
            return null
        }

        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, second)
        calendar.set(Calendar.MILLISECOND, 0)

        val releaseMs = calendar.timeInMillis
        if (releaseMs < System.currentTimeMillis()) {
            Toast.makeText(this, "Thời gian phải ở tương lai", Toast.LENGTH_SHORT).show()
            return null
        }

        val preference = when (rgVoucherPreference.checkedRadioButtonId) {
            R.id.rbMaxDiscount -> VoucherPreference.MaxDiscount
            R.id.rbMaxCashback -> VoucherPreference.MaxCashback
            R.id.rbManualCode -> {
                val code = etManualCode.text.toString().trim()
                if (code.isBlank()) {
                    Toast.makeText(this, "Nhập mã voucher", Toast.LENGTH_SHORT).show()
                    return null
                }
                VoucherPreference.ManualCode(code)
            }
            else -> VoucherPreference.AutoBest
        }

        val timeoutSeconds = timeoutSteps.getOrElse(seekRetryTimeout.progress) { 120 }

        return CheckoutConfig(
            releaseTimeMs = releaseMs,
            voucherPreference = preference,
            retryTimeoutMs = timeoutSeconds * 1000L
        )
    }

    private fun onArmClicked() {
        // Kiểm tra đủ điều kiện trước khi arm
        val issues = mutableListOf<String>()

        if (ShopeeAccessibilityService.getInstance() == null)
            issues += "❌ AccessibilityService chưa bật"
        if (!Settings.canDrawOverlays(this))
            issues += "❌ Quyền Overlay (Hiển thị trên ứng dụng khác) chưa cấp"
        if (!isBatteryOptimizationIgnored())
            issues += "⚠️ Battery Optimization chưa tắt (service có thể bị kill)"

        if (issues.isNotEmpty()) {
            showRequirementsDialog(issues)
            return
        }

        val config = buildConfig() ?: return
        startForegroundService(ShopeeKitForegroundService.startIntent(this))
        CheckoutOverlayView.show(this)
        engine.arm(config)
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun showRequirementsDialog(issues: List<String>) {
        val msg = buildString {
            appendLine("Cần hoàn thành các bước sau:\n")
            issues.forEach { appendLine(it) }
            appendLine()
            appendLine("Nhấn \"Hướng dẫn\" để xem cách cài đặt.")
        }
        AlertDialog.Builder(this)
            .setTitle("Chưa sẵn sàng")
            .setMessage(msg)
            .setPositiveButton("Hướng dẫn") { _, _ -> showSetupGuide() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showSetupGuide() {
        val isXiaomi = android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        val steps = buildString {
            appendLine("═══ HƯỚNG DẪN CÀI ĐẶT ═══\n")

            appendLine("① BẬT ACCESSIBILITY SERVICE")
            appendLine("→ Cài đặt → Trợ năng → Ứng dụng đã cài đặt → ShopeeKit → Bật")
            appendLine()

            appendLine("② CẤP QUYỀN OVERLAY")
            appendLine("→ Cài đặt → Ứng dụng → ShopeeKit → Quyền → Hiển thị trên ứng dụng khác → Bật")
            appendLine()

            appendLine("③ TẮT BATTERY OPTIMIZATION")
            appendLine("→ Cài đặt → Ứng dụng → ShopeeKit → Pin → Không giới hạn")
            appendLine()

            if (isXiaomi) {
                appendLine("④ XIAOMI / HYPEROS — QUAN TRỌNG:")
                appendLine("→ Cài đặt → Ứng dụng → ShopeeKit → Tự động khởi động → Bật")
                appendLine("→ Cài đặt → Ứng dụng → ShopeeKit → Kiểm soát hoạt động nền → Không giới hạn")
                appendLine("→ Bảo mật (Security app) → Quyền riêng tư → Trình quản lý quyền → Trợ năng → ShopeeKit → Bật")
                appendLine()
                appendLine("⚠️  HyperOS có thể kill app sau vài giây.")
                appendLine("     Sau khi ARM, giữ màn hình sáng và để Shopee ở foreground.")
                appendLine()
            }

            appendLine("⑤ CÁCH DÙNG CHECKOUT SNIPER")
            appendLine("1. Mở Shopee → vào trang checkout (sẵn sàng đặt hàng)")
            appendLine("2. KHÔNG nhấn gì — để màn hình ở trang checkout")
            appendLine("3. Mở ShopeeKit → Checkout Sniper")
            appendLine("4. Đặt giờ voucher mở (giờ:phút:giây)")
            appendLine("5. Chọn ưu tiên voucher → nhấn ARM")
            appendLine("6. Quay lại app Shopee, giữ ở trang checkout")
            appendLine("7. Khi đến giờ: ShopeeKit tự chọn voucher tốt nhất + nhấn Đặt hàng")
        }

        AlertDialog.Builder(this)
            .setTitle("📋 Hướng dẫn")
            .setMessage(steps)
            .setPositiveButton("Bật Accessibility") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("Tắt Battery Opt.") { _, _ ->
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun onDisarmClicked() {
        engine.disarm()
        CheckoutOverlayView.hide()
        stopService(ShopeeKitForegroundService.stopIntent(this))
    }

    private fun updateUI(state: CheckoutSniperState) {
        runOnUiThread {
            val canArm = state is CheckoutSniperState.Idle ||
                state is CheckoutSniperState.Success ||
                state is CheckoutSniperState.Failed ||
                state is CheckoutSniperState.OutOfStock

            btnArmCheckout.isEnabled = canArm
            btnDisarmCheckout.visibility = if (!canArm) View.VISIBLE else View.GONE

            when (state) {
                is CheckoutSniperState.Idle -> {
                    tvCheckoutStatus.text = "● IDLE"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_idle))
                    tvCheckoutCountdown.text = "--:--:--"
                    tvCheckoutDetail.text = ""
                }
                is CheckoutSniperState.Armed -> {
                    tvCheckoutStatus.text = "🎯 SẴN SÀNG"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_armed))
                    tvCheckoutDetail.text = "RTT: ${state.rttMs}ms | Offset: ${state.serverOffsetMs}ms"
                }
                is CheckoutSniperState.Firing -> {
                    tvCheckoutStatus.text = "🚀 ĐANG BẮN #${state.attemptCount}"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_firing))
                }
                is CheckoutSniperState.ApplyingVoucher -> {
                    tvCheckoutStatus.text = "🎟️ ĐANG CHỌN VOUCHER"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_firing))
                }
                is CheckoutSniperState.PlacingOrder -> {
                    tvCheckoutStatus.text = "📦 ĐANG ĐẶT HÀNG"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_firing))
                    tvCheckoutCountdown.text = "NOW!"
                }
                is CheckoutSniperState.RetryLoop -> {
                    tvCheckoutStatus.text = "🔄 THỬ LẠI #${state.attemptCount}"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_scheduled))
                    tvCheckoutDetail.text = state.lastError
                }
                is CheckoutSniperState.Success -> {
                    tvCheckoutStatus.text = "✅ THÀNH CÔNG!"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_success))
                    tvCheckoutCountdown.text = "🎉"
                    val detail = buildString {
                        append("Latency: +${state.latencyMs}ms")
                        state.appliedVoucher?.let { append(" | Voucher: $it") }
                        if (state.savedAmount > 0) append(" | Tiết kiệm: ${state.savedAmount}đ")
                        if (state.detectedExisting) append("\n(Order detected via idempotency check)")
                    }
                    tvCheckoutDetail.text = detail
                    CheckoutOverlayView.hide()
                    Toast.makeText(this, "✅ Đặt hàng thành công!", Toast.LENGTH_LONG).show()
                }
                is CheckoutSniperState.OutOfStock -> {
                    tvCheckoutStatus.text = "😞 HẾT VOUCHER"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_failed))
                    tvCheckoutDetail.text = "Voucher đã hết. Đặt hàng không voucher?"
                    CheckoutOverlayView.hide()
                    showOrderWithoutVoucherDialog()
                }
                is CheckoutSniperState.Failed -> {
                    tvCheckoutStatus.text = "❌ THẤT BẠI"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_failed))
                    tvCheckoutDetail.text = "Lý do: ${state.reason} (${state.attemptCount} lần thử)"
                    CheckoutOverlayView.hide()
                }
                is CheckoutSniperState.RequiresPin -> {
                    tvCheckoutStatus.text = "🔐 CẦN NHẬP PIN"
                    tvCheckoutStatus.setTextColor(getColor(R.color.status_scheduled))
                    tvCheckoutDetail.text = "ShopeePay yêu cầu PIN/OTP — mở Shopee và nhập tay"
                    CheckoutOverlayView.hide()
                    Toast.makeText(this, "🔐 Cần nhập PIN/OTP để hoàn tất", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showOrderWithoutVoucherDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hết voucher")
            .setMessage("Voucher đã hết hoặc chưa available. Đặt hàng thường không có voucher?")
            .setPositiveButton("Đặt hàng") { _, _ ->
                // Trigger place order without voucher
                com.personal.shopeekit.service.ShopeeAccessibilityService.clickPlaceOrder()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun updateCountdown() {
        val ms = engine.msUntilRelease()
        if (ms < 0) return
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        val millis = (ms % 1000) / 10
        tvCheckoutCountdown.text = when {
            h > 0 -> String.format("%02d:%02d:%02d", h, m, s)
            ms > 60_000 -> String.format("%02d:%02d", m, s)
            else -> String.format("%02d.%02d", s, millis)
        }
    }

    private fun updateAccessibilityStatus() {
        val connected = ShopeeAccessibilityService.getInstance() != null
        tvAccessibilityStatus.text = if (connected) "✅ AccessibilityService đã kết nối"
        else "⚠️ AccessibilityService chưa bật"
        tvAccessibilityStatus.setTextColor(
            if (connected) getColor(R.color.status_success) else getColor(R.color.status_warning)
        )
        btnEnableAccessibility.visibility = if (connected) View.GONE else View.VISIBLE
        btnArmCheckout.isEnabled = connected && (engine.state.value is CheckoutSniperState.Idle ||
            engine.state.value is CheckoutSniperState.Success ||
            engine.state.value is CheckoutSniperState.Failed ||
            engine.state.value is CheckoutSniperState.OutOfStock)
    }
}
