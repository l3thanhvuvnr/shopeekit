package com.personal.shopeekit.core.time

import com.personal.shopeekit.core.network.ShopeeHttpClient
import com.personal.shopeekit.core.logging.KitLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Measures the offset between device clock and Shopee server clock.
 *
 * Primary method: edge-detection (F1).
 *   Poll rapidly (~40 requests, 80ms apart) and detect the exact moment the
 *   "Date" header ticks from second N → N+1. At that boundary, the server
 *   clock is at approximately N+1.000s, so:
 *     offset = (N+1)*1000 - localMidpoint_at_boundary
 *   Error ≈ RTT/2, much better than the ±500ms from plain median.
 *
 * Fallback: if no boundary is detected in the polling window, use the
 * traditional median approach so we always have a usable offset.
 */
object TimeSync {

    private const val SAMPLE_COUNT = 5           // fallback median samples
    private const val EDGE_SAMPLE_COUNT = 40     // F1: edge-detection poll count
    private const val EDGE_INTERVAL_MS = 80L     // F1: interval between edge polls
    private const val RECALIBRATE_INTERVAL_MS = 5 * 60 * 1000L

    @Volatile private var _offsetMs: Long = 0L
    @Volatile private var _lastCalibrated: Long = 0L
    @Volatile private var _isRefined: Boolean = false
    @Volatile private var _rttApprox: Long = 80L  // updated during edge-detection for logging

    val offsetMs: Long get() = _offsetMs
    val isCalibrated: Boolean get() = _lastCalibrated > 0
    /** True if offset was computed via edge-detection (sub-second accuracy). */
    val isRefined: Boolean get() = _isRefined

    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }

    /**
     * Calibrate with edge-detection first; fallback to median if edge not found.
     * @return offset in milliseconds (server_time - local_time)
     */
    suspend fun calibrate(): Long = withContext(Dispatchers.IO) {
        val edgeOffset = tryEdgeDetection()
        if (edgeOffset != null) {
            _offsetMs = edgeOffset
            _isRefined = true
            KitLogger.i("TSY", "calibrate — edge-detection OK offset=${edgeOffset}ms (±~${_rttApprox/2}ms)")
        } else {
            _offsetMs = medianCalibrate()
            _isRefined = false
            KitLogger.w("TSY", "calibrate — fallback median offset=${_offsetMs}ms (±500ms, no boundary found)")
        }
        _lastCalibrated = System.currentTimeMillis()
        _offsetMs
    }

    /**
     * F1: Edge-detection calibration.
     * Polls until Date header ticks to next second. At that boundary:
     *   server_time ≈ (ticked_second * 1000) + 0 ms
     *   error ≈ RTT / 2
     */
    private suspend fun tryEdgeDetection(): Long? {
        var prevSecond: Long = -1L
        var prevMidpoint: Long = 0L

        repeat(EDGE_SAMPLE_COUNT) {
            try {
                val before = System.currentTimeMillis()
                val request = ShopeeHttpClient.buildRequest(ShopeeHttpClient.pingUrl())
                val response = ShopeeHttpClient.client.newCall(request).execute()
                val after = System.currentTimeMillis()
                val dateHeader = response.header("Date")
                response.close()

                if (dateHeader != null) {
                    val serverSec = (dateFormat.parse(dateHeader)?.time ?: return@repeat) / 1000L
                    val localMidpoint = (before + after) / 2
                    _rttApprox = after - before  // track for logging

                    if (prevSecond >= 0 && serverSec > prevSecond) {
                        // Boundary detected: server just ticked from prevSecond → serverSec
                        // At the boundary, server time = serverSec * 1000 (start of that second)
                        // Our best local estimate of *when* the tick happened is midway between
                        // the last sample that saw prevSecond and this sample that sees serverSec.
                        val boundaryLocal = (prevMidpoint + localMidpoint) / 2
                        return serverSec * 1000L - boundaryLocal
                    }
                    prevSecond = serverSec
                    prevMidpoint = localMidpoint
                }
            } catch (_: Exception) { }
            delay(EDGE_INTERVAL_MS)
        }
        return null // no boundary found in window
    }

    /** Traditional 5-sample median calibration (fallback). */
    private suspend fun medianCalibrate(): Long {
        val samples = mutableListOf<Long>()
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
                    samples.add(serverTime - (before + after) / 2)
                }
            } catch (_: Exception) { }
            delay(200L)
        }
        return if (samples.isEmpty()) 0L else samples.sorted()[samples.size / 2]
    }

    fun serverTimeMs(): Long = System.currentTimeMillis() + _offsetMs

    fun toServerTime(localMs: Long): Long = localMs + _offsetMs

    suspend fun ensureFresh(): Long {
        val now = System.currentTimeMillis()
        return if (now - _lastCalibrated > RECALIBRATE_INTERVAL_MS) calibrate()
        else _offsetMs
    }
}
