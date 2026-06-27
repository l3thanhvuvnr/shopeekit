package com.personal.shopeekit.ui.views

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.personal.shopeekit.features.price.db.PriceRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configures and populates an MPAndroidChart [LineChart] for price history.
 * Theme-aware (dark mode) via resolved text color; tooltip + pinch-zoom enabled.
 */
object PriceChartRenderer {

    enum class Range(val days: Int?) { WEEK(7), MONTH(30), ALL(null) }

    private val dateFmt = SimpleDateFormat("dd/MM", Locale.getDefault())

    fun setUp(chart: LineChart) {
        val textColor = resolveTextColor(chart.context)
        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setNoDataText("Chưa có dữ liệu giá")
            setNoDataTextColor(textColor)
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)

            axisRight.isEnabled = false
            axisLeft.textColor = textColor
            axisLeft.setDrawGridLines(true)
            axisLeft.gridColor = Color.argb(40, 128, 128, 128)
            axisLeft.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = formatVnd(value.toLong())
            }

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = textColor
            xAxis.setDrawGridLines(false)
            xAxis.granularity = 1f
        }
    }

    /**
     * Render [records] (any order) filtered to [range]. Records are sorted
     * ascending by timestamp for plotting.
     */
    fun render(chart: LineChart, records: List<PriceRecord>, range: Range) {
        val now = System.currentTimeMillis()
        val cutoff = range.days?.let { now - it * 24L * 60 * 60 * 1000 } ?: Long.MIN_VALUE
        val data = records.filter { it.timestamp >= cutoff }.sortedBy { it.timestamp }

        if (data.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }

        val entries = data.mapIndexed { i, r -> Entry(i.toFloat(), r.price.toFloat()) }
        val accent = Color.parseColor("#EE4D2D") // Shopee orange — brand constant
        val set = LineDataSet(entries, "Giá").apply {
            color = accent
            lineWidth = 2f
            setDrawCircles(data.size <= 30)
            setCircleColor(accent)
            circleRadius = 2.5f
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = accent
            fillAlpha = 40
            highLightColor = accent
            mode = LineDataSet.Mode.LINEAR
        }

        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt().coerceIn(0, data.size - 1)
                return dateFmt.format(Date(data[idx].timestamp))
            }
        }
        chart.data = LineData(set)
        chart.invalidate()
        chart.animateX(300)
    }

    private fun formatVnd(vnd: Long): String = when {
        vnd >= 1_000_000 -> "%.1fM".format(vnd / 1_000_000.0)
        vnd >= 1_000 -> "${vnd / 1000}k"
        else -> vnd.toString()
    }

    private fun resolveTextColor(context: Context): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, tv, true
            )
        ) tv.data else Color.GRAY
    }
}
