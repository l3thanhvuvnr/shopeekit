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
 * Most scenarios are single-pass with the fake clock's server time fixed just past
 * the commit gate and inside the retry window. The retry loop, the pre-release
 * hold+re-apply, and the MAX_ATTEMPTS cap are exercised by drivers that miss or
 * nudge the clock. Window-end by elapsed SERVER time is not asserted (it depends
 * on real wall-clock advancement, which the fixed fake clock doesn't model).
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
        override suspend fun prewarmDrawer() {}
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
            override suspend fun prewarmDrawer() {}
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

    // ─── Retry / hold / idempotency branches ──────────────────────────────────
    // (Added after an adversarial review flagged these paths as untested.)

    @Test
    fun `voucher-apply miss retries then succeeds`() = runTest {
        // First apply doesn't complete (drawer never opened) → RetryLoop; second
        // apply succeeds → order. Exercises the retry loop with the server-time
        // window held open (serverNow fixed in-window).
        val driver = object : CheckoutUiDriver {
            var applyCalls = 0
            var placeCalls = 0
            override fun isOnCheckoutScreen() = true
            override fun hasRecentOrder() = false
            override suspend fun applyBestVoucher(preference: VoucherPreference, requireApplied: Boolean): VoucherApplyResult {
                applyCalls++
                return if (applyCalls == 1) VoucherApplyResult.DrawerNotOpened
                else VoucherApplyResult.Applied("v", null)
            }
            override suspend fun clickPlaceOrder(): PlaceOrderResult { placeCalls++; return PlaceOrderResult.Success }
            override fun warmUpNudge() {}
            override suspend fun prewarmDrawer() {}
        }
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        assertTrue("expected Success, got ${engine.state.value}", engine.state.value is CheckoutSniperState.Success)
        assertEquals("should have retried the apply once", 2, driver.applyCalls)
        assertEquals(1, driver.placeCalls)
    }

    @Test
    fun `persistent voucher-apply miss stops at MAX_ATTEMPTS`() = runTest {
        // apply never completes; the window stays open (serverNow fixed) so the loop
        // stops on the attempt cap, not the window — Failed after 25 tries.
        val driver = FakeDriver(applyResult = VoucherApplyResult.DrawerNotOpened)
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        val state = engine.state.value
        assertTrue("expected Failed, got $state", state is CheckoutSniperState.Failed)
        assertEquals("MAX_ATTEMPTS", 25, (state as CheckoutSniperState.Failed).attemptCount)
        assertEquals(25, driver.applyCalls)
    }

    @Test
    fun `pre-release apply holds then re-applies past the commit gate`() = runTest {
        // FULL only: apply happens before T+commit → hold & re-apply (never order
        // pre-release). Driver nudges the server clock forward per apply so the 2nd
        // pass clears the commit gate and orders.
        val clock = FakeClock(localNow = releaseT + 10_000L, serverNow = releaseT - 100L)
        val driver = object : CheckoutUiDriver {
            var applyCalls = 0
            var placeCalls = 0
            override fun isOnCheckoutScreen() = true
            override fun hasRecentOrder() = false
            override suspend fun applyBestVoucher(preference: VoucherPreference, requireApplied: Boolean): VoucherApplyResult {
                applyCalls++
                clock.serverNow += 200L   // advance Shopee's clock each pass
                return VoucherApplyResult.Applied("v", null)
            }
            override suspend fun clickPlaceOrder(): PlaceOrderResult { placeCalls++; return PlaceOrderResult.Success }
            override fun warmUpNudge() {}
            override suspend fun prewarmDrawer() {}
        }
        val engine = CheckoutSniperEngine(driver, clock, StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        assertTrue("expected Success, got ${engine.state.value}", engine.state.value is CheckoutSniperState.Success)
        assertTrue("must re-apply after the pre-release hold", driver.applyCalls >= 2)
        // never ordered before clearing the gate
        assertEquals(1, driver.placeCalls)
    }

    @Test
    fun `FULL with a leftover order at fire start still places a real order`() = runTest {
        // hasRecentOrder is TRUE from the start (a leftover confirmation) → the
        // idempotency shortcut must be DISABLED so it isn't read as our success;
        // the engine should still apply + place a real order.
        val driver = FakeDriver(recentOrder = true, placeResult = PlaceOrderResult.Success)
        val engine = CheckoutSniperEngine(driver, clockPastCommit(), StandardTestDispatcher(testScheduler))

        engine.arm(config(SnipeMode.FULL_CHECKOUT))
        advanceUntilIdle()

        val state = engine.state.value
        assertTrue("expected Success, got $state", state is CheckoutSniperState.Success)
        assertFalse("must not be a false idempotency success", (state as CheckoutSniperState.Success).detectedExisting)
        assertEquals("placed a real order", 1, driver.placeCalls)
    }
}
