package com.personal.shopeekit.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.personal.shopeekit.features.price.db.PriceRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom LineChart for price history.
 * Draws price over time with min/max annotations.
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE4D2D")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33EE4D2D")
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE4D2D")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 28f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEEEEE")
        strokeWidth = 1f
    }

    private val minPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val dateFormat = SimpleDateFormat("dd/MM", Locale.getDefault())

    var records: List<PriceRecord> = emptyList()
        set(value) {
            field = value.sortedBy { it.timestamp }
            invalidate()
        }

    private val padding = 60f
    private val topPad = 40f
    private val bottomPad = 60f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (records.size < 2) {
            drawEmptyState(canvas)
            return
        }

        val chartW = width - padding * 2
        val chartH = height - topPad - bottomPad

        val prices = records.map { it.price }
        val minPrice = prices.min()
        val maxPrice = prices.max()
        val priceRange = maxOf(maxPrice - minPrice, 1000L)

        // Draw grid lines
        for (i in 0..4) {
            val y = topPad + chartH * i / 4
            canvas.drawLine(padding, y, padding + chartW, y, gridPaint)
        }

        // Draw min price horizontal line
        val minY = topPad + chartH * (1f - (minPrice - minPrice).toFloat() / priceRange)
        canvas.drawLine(padding, minY, padding + chartW, minY, minPricePaint)

        // Build path
        val path = Path()
        val fillPath = Path()

        records.forEachIndexed { i, record ->
            val x = padding + chartW * i / (records.size - 1)
            val y = topPad + chartH * (1f - (record.price - minPrice).toFloat() / priceRange)

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, topPad + chartH)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Close fill path
        val lastX = padding + chartW
        fillPath.lineTo(lastX, topPad + chartH)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        // Draw dots and date labels at key points
        val step = maxOf(1, records.size / 5)
        records.filterIndexed { i, _ -> i % step == 0 || i == records.size - 1 }
            .forEach { record ->
                val i = records.indexOf(record)
                val x = padding + chartW * i / (records.size - 1)
                val y = topPad + chartH * (1f - (record.price - minPrice).toFloat() / priceRange)
                canvas.drawCircle(x, y, 5f, dotPaint)
                canvas.drawText(dateFormat.format(Date(record.timestamp)), x - 20f, height - 10f, textPaint)
            }

        // Price labels on Y axis
        val yLabels = listOf(minPrice, (minPrice + maxPrice) / 2, maxPrice)
        yLabels.forEach { price ->
            val y = topPad + chartH * (1f - (price - minPrice).toFloat() / priceRange)
            canvas.drawText(formatPrice(price), 0f, y + 8f, textPaint)
        }
    }

    private fun drawEmptyState(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#AAAAAA")
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Chưa có dữ liệu giá", width / 2f, height / 2f, paint)
    }

    private fun formatPrice(vnd: Long): String = "${vnd / 1000}k"
}
