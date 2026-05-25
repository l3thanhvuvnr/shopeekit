package com.personal.shopeekit.core.time

import com.personal.shopeekit.core.network.ShopeeHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Measures round-trip time (RTT) to Shopee server.
 * RTT is used to compute speculative fire time: fireAt = T - rtt - buffer
 */
object RttMeasurer {

    private const val SAMPLE_COUNT = 5
    @Volatile private var _rttMs: Long = 80L // conservative default

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
            } catch (e: Exception) {
                // Ignore
            }
            Thread.sleep(300)
        }

        _rttMs = if (samples.isEmpty()) 80L
                 else samples.sorted()[samples.size / 2]
        _rttMs
    }

    /**
     * Compute how many ms before T we should fire the accessibility click.
     * We want the request to arrive at the server exactly at T.
     * buffer = 30ms safety margin.
     */
    fun speculativeLeadMs(bufferMs: Long = 30L): Long = _rttMs + bufferMs
}
