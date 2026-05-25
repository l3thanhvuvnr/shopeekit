package com.personal.shopeekit.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.personal.shopeekit.R
import com.personal.shopeekit.ShopeeKitApp
import com.personal.shopeekit.features.sniper.SniperState
import com.personal.shopeekit.features.sniper.VoucherSniperFeature
import com.personal.shopeekit.service.ShopeeKitForegroundService
import com.personal.shopeekit.ui.overlay.SniperOverlayView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class SniperSetupActivity : AppCompatActivity() {

    private lateinit var etVoucherUrl: EditText
    private lateinit var tvSelectedDateTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvDetail: TextView
    private lateinit var btnArm: Button
    private lateinit var btnDisarm: Button
    private lateinit var btnPickDate: Button
    private lateinit var btnPickTime: Button

    private val engine by lazy {
        (application as ShopeeKitApp).features
            .filterIsInstance<VoucherSniperFeature>()
            .first()
            .engine
    }

    private val calendar = Calendar.getInstance()
    private var selectedReleaseMs: Long = 0L
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateCountdown()
            countdownHandler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sniper_setup)

        etVoucherUrl = findViewById(R.id.etVoucherUrl)
        tvSelectedDateTime = findViewById(R.id.tvSelectedDateTime)
        tvStatus = findViewById(R.id.tvStatus)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvDetail = findViewById(R.id.tvDetail)
        btnArm = findViewById(R.id.btnArm)
        btnDisarm = findViewById(R.id.btnDisarm)
        btnPickDate = findViewById(R.id.btnPickDate)
        btnPickTime = findViewById(R.id.btnPickTime)

        btnPickDate.setOnClickListener { showDatePicker() }
        btnPickTime.setOnClickListener { showTimePicker() }
        btnArm.setOnClickListener { onArmClicked() }
        btnDisarm.setOnClickListener { onDisarmClicked() }

        // Observe state changes
        lifecycleScope.launch {
            engine.state.collectLatest { state -> updateUI(state) }
        }

        countdownHandler.post(countdownRunnable)
    }

    override fun onDestroy() {
        countdownHandler.removeCallbacks(countdownRunnable)
        super.onDestroy()
    }

    private fun showDatePicker() {
        DatePickerDialog(this, { _, y, m, d ->
            calendar.set(y, m, d)
            updateDateTimeDisplay()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
            .show()
    }

    private fun showTimePicker() {
        TimePickerDialog(this, { _, h, min ->
            calendar.set(Calendar.HOUR_OF_DAY, h)
            calendar.set(Calendar.MINUTE, min)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            updateDateTimeDisplay()
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true)
            .show()
    }

    private fun updateDateTimeDisplay() {
        selectedReleaseMs = calendar.timeInMillis
        tvSelectedDateTime.text = dateFormat.format(calendar.time)
    }

    private fun onArmClicked() {
        val url = etVoucherUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "Nhập Shopee Voucher URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedReleaseMs == 0L) {
            Toast.makeText(this, "Chọn thời gian mở voucher", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedReleaseMs < System.currentTimeMillis()) {
            Toast.makeText(this, "Thời gian phải ở tương lai", Toast.LENGTH_SHORT).show()
            return
        }

        // Start foreground service to keep alive during countdown
        startForegroundService(ShopeeKitForegroundService.startIntent(this))

        // Show overlay
        SniperOverlayView.show(this)

        engine.arm(url, selectedReleaseMs)
    }

    private fun onDisarmClicked() {
        engine.disarm()
        SniperOverlayView.hide()
        stopService(ShopeeKitForegroundService.stopIntent(this))
    }

    private fun updateUI(state: SniperState) {
        runOnUiThread {
            when (state) {
                is SniperState.Idle -> {
                    tvStatus.text = "● IDLE"
                    tvStatus.setTextColor(getColor(R.color.status_idle))
                    tvCountdown.text = "--:--:--"
                    tvDetail.text = ""
                    btnArm.isEnabled = true
                    btnDisarm.visibility = View.GONE
                }
                is SniperState.Scheduled -> {
                    tvStatus.text = "⏳ ĐANG CHUẨN BỊ"
                    tvStatus.setTextColor(getColor(R.color.status_scheduled))
                    tvDetail.text = "Đang calibrate clock + RTT..."
                    btnArm.isEnabled = false
                    btnDisarm.visibility = View.VISIBLE
                }
                is SniperState.Armed -> {
                    tvStatus.text = "🎯 SẴN SÀNG"
                    tvStatus.setTextColor(getColor(R.color.status_armed))
                    tvDetail.text = "RTT: ${state.rttMs}ms | Offset: ${state.serverOffsetMs}ms"
                    btnArm.isEnabled = false
                    btnDisarm.visibility = View.VISIBLE
                }
                is SniperState.Firing -> {
                    tvStatus.text = "🚀 ĐÃ BẮN"
                    tvStatus.setTextColor(getColor(R.color.status_firing))
                    tvCountdown.text = "FIRED!"
                    tvDetail.text = "Đang chờ kết quả..."
                }
                is SniperState.Success -> {
                    tvStatus.text = "✅ THÀNH CÔNG!"
                    tvStatus.setTextColor(getColor(R.color.status_success))
                    tvCountdown.text = "🎉"
                    tvDetail.text = "Latency: ${state.latencyMs}ms từ T"
                    btnArm.isEnabled = true
                    btnDisarm.visibility = View.GONE
                    SniperOverlayView.hide()
                    Toast.makeText(this, "✅ Lấy voucher thành công!", Toast.LENGTH_LONG).show()
                }
                is SniperState.Failed -> {
                    tvStatus.text = "❌ THẤT BẠI"
                    tvStatus.setTextColor(getColor(R.color.status_failed))
                    tvDetail.text = "Lý do: ${state.reason}"
                    btnArm.isEnabled = true
                    btnDisarm.visibility = View.GONE
                    SniperOverlayView.hide()
                }
                is SniperState.OutOfStock -> {
                    tvStatus.text = "😞 HẾT VOUCHER"
                    tvStatus.setTextColor(getColor(R.color.status_failed))
                    tvDetail.text = "Voucher đã hết"
                    btnArm.isEnabled = true
                    btnDisarm.visibility = View.GONE
                    SniperOverlayView.hide()
                }
                SniperState.TokenExpired -> {
                    tvStatus.text = "⚠️ TOKEN HẾT HẠN"
                    tvStatus.setTextColor(getColor(R.color.status_warning))
                    tvDetail.text = "Cần re-capture headers qua mitmproxy"
                    btnArm.isEnabled = false
                    startActivity(Intent(this, com.personal.shopeekit.ui.setup.TokenSetupActivity::class.java))
                }
            }
        }
    }

    private fun updateCountdown() {
        val ms = engine.msUntilRelease()
        if (ms < 0) return
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        val millis = (ms % 1000) / 10
        tvCountdown.text = when {
            h > 0 -> String.format("%02d:%02d:%02d", h, m, s)
            else -> String.format("%02d:%02d.%02d", m, s, millis)
        }
    }
}
