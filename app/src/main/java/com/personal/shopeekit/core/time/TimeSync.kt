package com.personal.shopeekit.core.time

import com.personal.shopeekit.core.network.ShopeeHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Measures the offset between device clock and Shopee server clock.
 *
 * Shopee server time is read from the "Date:" HTTP response header.
 * We run 5 measurements and take the median to reduce noise.
 *
 * Usage:
 *   val offset = TimeSync.calibrate()
 *   val serverNow = System.currentTimeMillis() + offset
 */
object TimeSync {

    private const val SAMPLE_COUNT = 5
    @Volatile private var _offsetMs: Long = 0L
    @Volatile private var _lastCalibrated: Long = 0L
    private const val RECALIBRATE_INTERVAL_MS = 5 * 60 * 1000L // 5 min

    val offsetMs: Long get() = _offsetMs
    val isCalibrated: Boolean get() = _lastCalibrated > 0

    /**
     * Calibrate server time offset. Should be called at T-10min before snipe.
     * @return offset in milliseconds (server_time - local_time)
     */
    suspend fun calibrate(): Long = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Long>()
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

        repeat(SAMPLE_COUNT) {
            try {
                val before = System.currentTimeMillis()
                val request = ShopeeHttpClient.buildRequest(ShopeeHttpClient.pingUrl())
                val response = ShopeeHttpClient.client.newCall(request).execute()
                val after = System.currentTimeMillis()

                val dateHeader = response.header("Date")
                response.close()

                if (dateHeader != null) {
                    val serverTime = dateFormat.parse(dateHeader)?.time ?: return@repeat
                    val localMidpoint = (before + after) / 2
                    samples.add(serverTime - localMidpoint)
                }
            } catch (e: Exception) {
                // Ignore individual sample failures
            }
            Thread.sleep(200) // small gap between samples
        }

        _offsetMs = if (samples.isEmpty()) 0L else samples.sorted()[samples.size / 2]
        _lastCalibrated = System.currentTimeMillis()
        _offsetMs
    }

    /**
     * Get current server time (millis) using calibrated offset.
     */
    fun serverTimeMs(): Long = System.currentTimeMillis() + _offsetMs

    /**
     * Convert a local epoch ms to estimated server epoch ms.
     */
    fun toServerTime(localMs: Long): Long = localMs + _offsetMs

    /**
     * Auto-recalibrate if stale.
     */
    suspend fun ensureFresh(): Long {
        val now = System.currentTimeMillis()
        return if (now - _lastCalibrated > RECALIBRATE_INTERVAL_MS) calibrate()
        else _offsetMs
    }
}
