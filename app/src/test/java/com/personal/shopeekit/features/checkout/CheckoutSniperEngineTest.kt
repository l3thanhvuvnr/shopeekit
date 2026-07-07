package com.personal.shopeekit.features.checkout

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-machine tests for [CheckoutSniperEngine], driven through fake
 * [CheckoutUiDriver] + [SniperClock] implementations so the whole fire-loop runs
 * on the JVM with virtual time — no device, no real network calibration.
 *
 * These replace the old locally-mirrored `shouldRetry` helper: they exercise the
 * REAL engine deciding terminal-vs-retry, so they can't drift from it.
 *
 * Scenarios are single-pass and deterministic. The fake clock's server time is
 * fixed just past the commit gate and inside the retry window, so a terminal
 * result ends the loop on the first attempt; retry-until-window-end timing is
 * intentionally not asserted here (it depends on wall-clock advancement).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutSniperEngineTest {

    private val releaseT = 1_000_000_000_000L   // arbitrary server-epoch T

    /** Fires the scheduled callback immediately; server time fixed just past T+commit. */
    private class FakeClock(
        var localNow: Long,
        var serverNow: Long,
        override val isRefined: Boolean = true,
        private val offset: Long = 0L
    ) : SniperClock {
        override fun nowMs(): Long = localNow
        override fun serverNowMs(): Long = serverNow
        override suspend fun calibrate(): Long = offset
        override suspend fun measureRtt(): Long = 20L
        override fun scheduleAt(atMs: Long, onFire: () -> Unit) = onFire()
        override fun cancelSchedule() {}
    }

    private class FakeDriver(
        var onCheckout: Boolean = true,
        var recentOrder: Boolean = false,
        var applyResult: VoucherApplyResult = VoucherApplyResult.Applied("Tự động", "giảm 40.000đ"),
        var placeResult: PlaceOrderResult = PlaceOrderResult.Success
    ) : CheckoutUiDriver {
        var applyCalls = 0
        var placeCalls = 0
        override fun isOnCheckoutScreen(): Boolean = onCheckout
        override fun hasRecentOrder(): Boolean = recentOrder
        override suspend fun applyBestVoucher(
            preference: VoucherPreference,
            requireApplied: Boolean
        ): VoucherApplyResult {
            applyCalls++
            return applyResult
        }
        override suspend fun clickPlaceOrder(): PlaceOrderResult {
            placeCalls++
            return placeResult
        }
        override fun warmUpNudge() {}
    }

    private fun config(mode: SnipeMode) = CheckoutConfig(
        releaseTimeMs = releaseT,
        voucherPreference = VoucherPreference.AutoBest,
        retryTimeoutMs = CheckoutConfig.DEFAULT_RETRY_TIMEOUT_MS,
        mode = mode
    )

    /** local clock well past fireAt (warm-up exits immediately); server past commit gate. */
    private fun clockPastCommit() = FakeClock(
        localNow = releaseT + 10_000L,
        serverNow = releaseT + 300L   // > COMMIT_MARGIN_MS (200), inside 30s window
    )

    @Test
    fun `VOUCHER_ONLY applied stops before placing an order`() = runTest {
        val driver = FakeDriver(applyResult = VoucherApplyResult.Applied("Tự động", "giảm 40.000đ"))
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.VOUCHER_ONLY))
        advanceUntilIdle()

        val state = engine.state.value
        assertTrue("expected VoucherApplied, got $state", state is CheckoutSniperState.VoucherApplied)
        assertEquals("must not place an order in 2-step", 0, driver.placeCalls)
    }

    @Test
    fun `FULL applied then success reaches Success and places order`() = runTest {
        val driver = FakeDriver(placeResult = PlaceOrderResult.Success)
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        val state = engine.state.value
        assertTrue("expected Success, got $state", state is CheckoutSniperState.Success)
        assertEquals(1, driver.placeCalls)
    }

    @Test
    fun `FULL place-order RequiresPin stops immediately`() = runTest {
        val driver = FakeDriver(placeResult = PlaceOrderResult.RequiresPin("nhập mã pin"))
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        assertTrue(engine.state.value is CheckoutSniperState.RequiresPin)
    }

    @Test
    fun `FULL out-of-stock is terminal`() = runTest {
        val driver = FakeDriver(placeResult = PlaceOrderResult.OutOfStock)
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        assertTrue(engine.state.value is CheckoutSniperState.OutOfStock)
    }

    @Test
    fun `not on checkout screen at fire fails with zero attempts`() = runTest {
        val driver = FakeDriver(onCheckout = false)
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        val state = engine.state.value
        assertTrue("expected Failed, got $state", state is CheckoutSniperState.Failed)
        assertEquals(0, (state as CheckoutSniperState.Failed).attemptCount)
        assertEquals("must not apply/order when off checkout", 0, driver.applyCalls)
    }

    @Test
    fun `FULL idempotency detects an order that appears mid-loop`() = runTest {
        // recentOrder false at fire start (so the shortcut is enabled), then true on
        // the in-loop check → treated as our own success via idempotency.
        val driver = object : CheckoutUiDriver {
            var checks = 0
            var placeCalls = 0
            override fun isOnCheckoutScreen() = true
            override fun hasRecentOrder(): Boolean {
                // 1st call = orderPresentAtStart (false); 2nd = in-loop check (true)
                checks++
                return checks >= 2
            }
            override suspend fun applyBestVoucher(preference: VoucherPreference, requireApplied: Boolean) =
                VoucherApplyResult.Applied("v", null)
            override suspend fun clickPlaceOrder(): PlaceOrderResult { placeCalls++; return PlaceOrderResult.Success }
            override fun warmUpNudge() {}
        }
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        val state = engine.state.value
        assertTrue("expected Success, got $state", state is CheckoutSniperState.Success)
        assertTrue((state as CheckoutSniperState.Success).detectedExisting)
        assertEquals("idempotency short-circuits before any place-order", 0, driver.placeCalls)
    }

    @Test
    fun `accessibility unavailable during apply is fatal`() = runTest {
        val driver = FakeDriver(applyResult = VoucherApplyResult.AccessibilityUnavailable)
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        assertTrue(engine.state.value is CheckoutSniperState.Failed)
    }

    @Test
    fun `disarm returns engine to Idle`() = runTest {
        val engine = CheckoutSniperEngine(FakeDriver(), clockPastCommit(), StandardTestDispatcher(testScheduler))
        engine.arm(config(SnipeMode.VOUCHER_ONLY))
        advanceUntilIdle()
        engine.disarm()
        assertTrue(engine.state.value is CheckoutSniperState.Idle)
    }

    @Test
    fun `msUntilRelease is positive when armed before T`() = runTest {
        // server time BEFORE T → countdown positive. Use VOUCHER_ONLY so no commit gate.
        val clock = FakeClock(localNow = releaseT - 5_000L, serverNow = releaseT - 5_000L)
        val driver = FakeDriver()
        val engine = CheckoutSniperEngine(driver, clock, StandardTestDispatcher(testScheduler))
        engine.arm(config(SnipeMode.VOUCHER_ONLY))
        // don't advance to fire; just check the countdown reads from server time
        assertTrue(engine.msUntilRelease() > 0)
        assertFalse(driver.applyCalls > 0)  // hasn't fired yet
        engine.disarm()
    }
}
