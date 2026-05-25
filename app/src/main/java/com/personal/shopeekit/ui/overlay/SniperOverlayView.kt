package com.personal.shopeekit.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import com.personal.shopeekit.R
import com.personal.shopeekit.ShopeeKitApp
import com.personal.shopeekit.features.sniper.SniperState
import com.personal.shopeekit.features.sniper.VoucherSniperFeature
import java.util.concurrent.TimeUnit

/**
 * Floating overlay window showing countdown timer over any app (including Shopee).
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY — available without root.
 * Pattern from android-setup-guide.md (BVC project).
 */
object SniperOverlayView {

    private var overlayView: android.view.View? = null
    private var windowManager: WindowManager? = null
    private var tvCountdown: TextView? = null
    private var tvStatus: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 50)
        }
    }

    fun show(context: Context) {
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.overlay_sniper, null)
        tvCountdown = overlayView?.findViewById(R.id.tvOverlayCountdown)
        tvStatus = overlayView?.findViewById(R.id.tvOverlayStatus)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 80
        }

        try {
            windowManager?.addView(overlayView, params)
            handler.post(updateRunnable)
        } catch (e: Exception) {
            overlayView = null
        }
    }

    fun hide() {
        handler.removeCallbacks(updateRunnable)
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { /* ignore */ }
        overlayView = null
        windowManager = null
    }

    private fun updateDisplay() {
        val engine = (ShopeeKitApp.instance.features
            .filterIsInstance<VoucherSniperFeature>()
            .firstOrNull())?.engine ?: return

        val ms = engine.msUntilRelease()
        val state = engine.state.value

        tvStatus?.text = when (state) {
            is SniperState.Armed -> "🎯"
            is SniperState.Firing -> "🚀"
            is SniperState.Success -> "✅"
            is SniperState.Failed, is SniperState.OutOfStock -> "❌"
            else -> "⏳"
        }

        tvCountdown?.text = if (ms >= 0) {
            val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            val millis = (ms % 1000) / 10
            when {
                ms > 60_000 -> String.format("%02d:%02d", m, s)
                else -> String.format("%02d.%02d", s, millis)
            }
        } else "●"
    }
}
