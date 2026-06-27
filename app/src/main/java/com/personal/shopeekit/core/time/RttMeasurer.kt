package com.personal.shopeekit.core.time

import com.personal.shopeekit.core.network.ShopeeHttpClient
import com.personal.shopeekit.core.logging.KitLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Measures round-trip time (RTT) to Shopee server.
 * RTT is used to compute speculative fire time.
 *
 * F2: Uses half-RTT for lead calculation.
 * The fire-to-server trip is one-way (request only), so we need RTT/2 not full RTT.
 * A configurable buffer is added on top for safety margin.
 */
object RttMeasurer {

    private const val SAMPLE_COUNT = 5
    @Volatile private var _rttMs: Long = 80L   // conservative default

    val rttMs: Long get() = _rttMs

    /**
     * Measure RTT by sending N pings and taking the median.
     * Call this before scheduling a snipe (T-10min).
     */
    suspend fun measure(): Long = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Long>()

        repeat(SAMPLE_COUNT) {
            try {
                val start = System.currentTimeMillis()
                val request = ShopeeHttpClient.buildRequest(ShopeeHttpClient.pingUrl())
                val response = ShopeeHttpClient.client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - start
                response.close()
                samples.add(elapsed)
            } catch (_: Exception) { }
            delay(300L)
        }

        _rttMs = if (samples.isEmpty()) 80L else samples.sorted()[samples.size / 2]
        KitLogger.i("RTT", "measure — median=${_rttMs}ms samples=${samples.size} lead=${speculativeLeadMs()}ms")
        _rttMs
    }

    /**
     * F2: How many ms before T to fire the accessibility click.
     * Uses half-RTT (one-way trip time) + buffer for safety.
     * Full RTT was over-compensating — the response back doesn't need to arrive before T.
     */
    fun speculativeLeadMs(bufferMs: Long = 30L): Long = (_rttMs / 2) + bufferMs
}
