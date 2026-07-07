package com.personal.shopeekit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.personal.shopeekit.R
import com.personal.shopeekit.core.logging.KitLogger
import com.personal.shopeekit.core.storage.ShopeeConfig
import com.personal.shopeekit.features.checkout.HumanBehavior
import com.personal.shopeekit.features.checkout.OrderResultParser
import com.personal.shopeekit.features.checkout.PlaceOrderResult
import com.personal.shopeekit.features.checkout.VoucherApplyParser
import com.personal.shopeekit.features.checkout.VoucherApplyResult
import com.personal.shopeekit.features.checkout.VoucherPreference
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeElement
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeScreen
import com.personal.shopeekit.ui.ShopeeCookieSyncActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Accessibility Service for ShopeeKit.
 *
 * Primary role:
 *  1. Monitor Shopee app UI state (foreground detection, cookie-sync prompt)
 *  2. Drive the CheckoutSniper flow: apply best voucher + place order
 *  3. Detect order success / out-of-stock / payment errors from UI feedback
 *
 * NOTE: AccessibilityService.performAction() drives Shopee's own UI code path,
 * so each tap goes through Shopee's normal request signing (X-Sap-Ri etc.) —
 * this is why we automate via the UI instead of calling the API directly.
 */
class ShopeeAccessibilityService : AccessibilityService() {

    companion object {
        const val SHOPEE_PACKAGE = "com.shopee.vn"
        private const val TAG = "ShopeeAccess"
        private const val NOTIF_CHANNEL_SYNC = "cookie_sync"
        private const val NOTIF_ID_SYNC = 9001

        // ─── CheckoutSniper timing (latency budget) ──────────────────────────
        // Poll cadence while waiting for a Shopee RN transition (drawer open/close,
        // order-result screen). 16ms ≈ one 60fps frame: the UI cannot change faster
        // than a frame, so polling tighter only burns CPU without detecting sooner.
        // Each poll snapshots the a11y tree (Binder IPC) + a DFS find, which
        // self-limits the effective rate on large trees. This is the main knob for
        // "detect the transition ASAP" — the drawer render/close animations
        // themselves are Shopee's and can't be shortened.
        private const val POLL_STEP_MS = 16L
        // Ceiling for the voucher drawer's confirm button to render after tapping
        // the "Shopee Voucher" row.
        private const val DRAWER_OPEN_TIMEOUT_MS = 1500L
        // Ceiling for the voucher LIST to finish loading (network fetch) after the
        // drawer's "Đồng ý" button appears — the button renders before the cards, so
        // confirming immediately would apply an empty/half-loaded selection. Early-exits
        // via isVoucherListLoaded (id OR the "đã được tự động chọn" summary text), so
        // this is only the ceiling when detection can't see the list; on timeout it
        // confirms best-effort and the applied-verify still gates ordering.
        private const val VOUCHER_LOAD_TIMEOUT_MS = 1500L
        // Ceiling for the drawer to close after tapping "Đồng ý". A registered tap
        // closes the RN drawer in ~300ms; the ceiling only bites when a tap didn't
        // register (then the loop re-taps), so 800ms is ample.
        private const val DRAWER_CLOSE_TIMEOUT_MS = 800L
        // Ceiling for the order-result screen after tapping place-order. Polled with
        // early-exit, so the happy path returns as soon as the result renders
        // (~150-400ms) rather than always paying a flat wait.
        private const val ORDER_RESULT_TIMEOUT_MS = 800L
        // Ceiling for the checkout's platform-voucher row to re-render its applied
        // state after the drawer dismisses (3-step only). The order is gated on this
        // verify, so keep it generous — a too-short poll would refuse a genuinely
        // applied voucher and never place the order.
        private const val VOUCHER_VERIFY_TIMEOUT_MS = 2500L
        // Once the row has read PRESENT-yet-UNREADABLE this long (and never the empty
        // placeholder), stop dead-waiting the full ceiling — the value is unreadable on
        // this device and won't become readable. Bails only on UNREADABLE, never ABSENT.
        private const val VERIFY_UNKNOWN_PROBE_MS = 600L
        // After bailing/timing out without a positive, watch briefly for a late
        // APPLIED or a still-rendering PLACEHOLDER before committing to proceed — the
        // guard against shortcutting an UNREADABLE→PLACEHOLDER (empty) transition.
        private const val VERIFY_SETTLE_MS = 500L

        // Foreground state exposed to the UI layer
        private val _isShopeeActive = MutableStateFlow(false)
        val isShopeeActive: StateFlow<Boolean> = _isShopeeActive

        // Service instance reference (set on connect)
        @Volatile private var instance: ShopeeAccessibilityService? = null

        fun getInstance(): ShopeeAccessibilityService? = instance

        // ─── CheckoutSniper API ───────────────────────────────────────────────

        /**
         * Apply best voucher on checkout screen: open the "Shopee Voucher" row,
         * select per [preference], confirm ("Đồng ý"/OK/Áp dụng).
         *
         * [requireApplied] — verify the voucher actually shows applied on the checkout
         * before returning [VoucherApplyResult.Applied]. TRUE for the 3-step live run
         * (never place a voucher-less order). FALSE for the 2-step rehearsal: platform
         * vouchers are time-gated and only apply AT the release instant, so before then
         * "open drawer → Đồng ý" (the refresh action) is itself the pass condition —
         * requiring an applied state would wrongly fail the test.
         */
        fun applyBestVoucher(
            preference: VoucherPreference,
            requireApplied: Boolean
        ): VoucherApplyResult {
            val svc = instance ?: return VoucherApplyResult.AccessibilityUnavailable
            return svc.performApplyBestVoucher(preference, requireApplied)
        }

        /**
         * Click the "Đặt hàng" button and parse result.
         */
        fun clickPlaceOrder(): PlaceOrderResult {
            val svc = instance ?: return PlaceOrderResult.AccessibilityUnavailable
            return svc.performPlaceOrder()
        }

        /**
         * Check if a recent order was just placed (idempotency check).
         * Looks for order confirmation text or recent order in order list.
         */
        fun hasRecentOrder(): Boolean {
            val svc = instance ?: return false
            return svc.detectRecentOrderCreated()
        }

        /**
         * E5: Check if the current screen is the checkout screen.
         * Guards against firing when user navigated away.
         */
        fun isOnCheckoutScreen(): Boolean {
            val svc = instance ?: return false
            return svc.detectCheckoutScreen()
        }

        /**
         * Harmless warm-up activity before fire (anti-fraud). No-op if the
         * service isn't connected. See [performWarmUpNudge].
         */
        fun warmUpNudge() {
            instance?.performWarmUpNudge()
        }

    }

    private var overlayView: android.view.View? = null
    private var windowManager: WindowManager? = null
    private var lastSyncNotifMs: Long = 0L   // throttle: show max 1 notif per 30min

    // Cookie presence, kept fresh by a background collector so the (main-thread)
    // accessibility callback never has to block on DataStore. Starts false
    // (assume present) so we don't nag before the first read lands.
    @Volatile private var cookieBlank: Boolean = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        publishDisplaySize()
        ShopeeUIDiscovery.preloadCache()   // restore learned/pinned id hints
        setupOverlayWindow()
        createNotificationChannel()
        // Track cookie presence off the main thread — read once here and keep it
        // current, so onAccessibilityEvent never calls a blocking getCookieSync().
        serviceScope.launch {
            ShopeeConfig(this@ShopeeAccessibilityService).cookieFlow.collect { cookie ->
                cookieBlank = cookie.isBlank()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        publishDisplaySize()   // keep the off-screen reference correct after rotation
    }

    /**
     * Publish the real physical display size to [ShopeeUIDiscovery] so its off-screen
     * phantom guard has a STABLE reference. `rootInActiveWindow.getBoundsInScreen`
     * is unreliable mid-animation (Shopee's RN root can report a virtual width wider
     * than the screen — the off-screen drawer pages), which let a phantom "Đồng ý" at
     * x≈1620 on a 1080-wide screen pass as on-screen. The Display size is constant.
     */
    private fun publishDisplaySize() {
        val dm = resources.displayMetrics
        ShopeeUIDiscovery.setDisplaySize(dm.widthPixels, dm.heightPixels)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val isShopee = packageName == SHOPEE_PACKAGE
        _isShopeeActive.value = isShopee

        if (!isShopee) return

        // Auto-sync cookie notification: show when Shopee is foreground and cookie missing/stale
        maybeShowSyncNotification()
    }

    override fun onInterrupt() {
        _isShopeeActive.value = false
    }

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        removeOverlayWindow()
        super.onDestroy()
    }

    // ─── CheckoutSniper: Voucher Apply ────────────────────────────────────────

    /**
     * Apply best voucher on the current checkout screen.
     * Steps: open "Shopee Voucher" row → select per preference → confirm → verify
     * the drawer closed and read the applied discount. Each failure is reported
     * distinctly so the 2-step rehearsal can tell the user exactly where it broke
     * and the 3-step run can refuse to order when the voucher never applied.
     */
    private fun performApplyBestVoucher(
        preference: VoucherPreference,
        requireApplied: Boolean
    ): VoucherApplyResult {
        val root = rootInActiveWindow ?: return VoucherApplyResult.AccessibilityUnavailable

        // ── DRAWER-SKIP (latency win) ──
        // If a platform voucher is ALREADY applied on the checkout row — Shopee
        // auto-applies the best one at/after the release instant — the entire
        // open → load → select → confirm → close animation chain is redundant, so
        // report Applied immediately (saves the ~2-5s drawer round-trip, which is
        // animation-bound and the biggest remaining latency lever). Only for AutoBest:
        // the explicit modes want a SPECIFIC voucher that may differ from whatever is
        // currently applied, so they must still open the drawer. Pre-release the row
        // shows the "Chọn hoặc nhập mã" placeholder → value is null → no skip.
        if (preference is VoucherPreference.AutoBest) {
            val alreadyApplied = platformVoucherRowValue(root)
            if (alreadyApplied != null) {
                KitLogger.i("SNIPE", "drawer-skip — platform voucher already applied on checkout: $alreadyApplied")
                return VoucherApplyResult.Applied(voucherLabel = alreadyApplied, discountText = alreadyApplied)
            }
        }

        // Step 1: open the platform "Shopee Voucher" row.
        val pickerRow = ShopeeUIDiscovery.find(root, ShopeeScreen.CHECKOUT, ShopeeElement.VOUCHER_PICKER_ROW)
            ?: return VoucherApplyResult.PickerNotFound
        // HARD SAFETY: never let the voucher flow tap the place-order button.
        if (ShopeeUIDiscovery.isPlaceOrderNode(pickerRow)) {
            KitLogger.e("SNIPE", "ABORT — voucher row resolved to place-order node")
            return VoucherApplyResult.PickerNotFound
        }
        // The voucher-row wrapper (buttonCartPageUseVoucher) is a semantic clickable
        // node whose a11y bounds are unreliable ([0,0][0,0] or a wrong offset), so a
        // coordinate gesture misses. Prefer the click ACTION (position-independent).
        tapPreferClick(pickerRow)

        // Step 2: wait for the drawer to render (its confirm button appears). RN
        // loads the sheet async, so poll rather than sleep a fixed time.
        val drawerRoot = waitForElement(
            ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON,
            timeoutMs = DRAWER_OPEN_TIMEOUT_MS
        )
        val drawerOpen = drawerRoot != null && ShopeeUIDiscovery.find(
            drawerRoot, ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON,
            requireClickable = false, log = false
        ) != null
        if (!drawerOpen) return VoucherApplyResult.DrawerNotOpened

        // Step 2b: WAIT for the voucher list to finish loading before confirming. The
        // "Đồng ý" button renders before the voucher cards return from the network, so
        // a fast "open → confirm" would tap Đồng ý on an empty drawer (nothing applied).
        // Ready = a voucher card / radio rendered, or Shopee's auto-selected summary.
        // Best-effort: after the timeout we confirm anyway (drawer may be genuinely
        // empty pre-release), and the 3-step run's applied-check still gates ordering.
        if (!waitForVoucherListLoaded(VOUCHER_LOAD_TIMEOUT_MS)) {
            KitLogger.w("SNIPE", "voucher list not confirmed loaded within ${VOUCHER_LOAD_TIMEOUT_MS}ms — confirming best-effort")
        }

        // Step 3: selection.
        //  - AutoBest: Shopee auto-selects the best voucher the moment the drawer
        //    opens (i18n voucher_checkout_auto_selected_limit_one="1 voucher đã
        //    được tự động chọn cho bạn"). Tapping a voucher item would TOGGLE that
        //    selection off, so we deliberately select nothing and confirm the default.
        //  - Other modes: pick a specific item (scroll first to force RN lazy-render).
        // For the explicit-selection preferences, a null return means NOTHING was
        // selected (empty list pre-release, or the code/apply button wasn't found).
        // That is NOT a pass — track it so the 2-step rehearsal reports NothingApplied
        // (→ retry) instead of confirming an empty drawer and falsely claiming Applied.
        // AutoBest is exempt: it deliberately selects nothing (Shopee auto-selects).
        var selectionSucceeded = true
        val selectedLabel: String? = when (preference) {
            is VoucherPreference.AutoBest -> "Tự động (tốt nhất)"
            is VoucherPreference.MaxDiscount -> {
                scrollVoucherList(drawerRoot!!)
                selectVoucherByMaxDiscount(rootInActiveWindow ?: drawerRoot)
                    .also { if (it == null) selectionSucceeded = false }
            }
            is VoucherPreference.MaxCashback -> {
                scrollVoucherList(drawerRoot!!)
                selectVoucherByMaxCashback(rootInActiveWindow ?: drawerRoot)
                    .also { if (it == null) selectionSucceeded = false }
            }
            is VoucherPreference.ManualCode ->
                applyManualCode(rootInActiveWindow ?: drawerRoot!!, preference.code)
                    .also { if (it == null) selectionSucceeded = false }
        }

        // Step 4: tap "Đồng ý" and VERIFY the page actually closed. RN may ignore a
        // tap that lands right after the page renders (before its onClick handler is
        // wired), so retry — first with a human gesture, then a direct ACTION_CLICK.
        // "Closed" = the confirm button is gone; do NOT use "place-order visible" —
        // the checkout stays mounted behind the voucher page so it's always findable.
        var closed = false
        var tries = 0
        while (!closed && tries < 3) {
            val r = rootInActiveWindow
            val confirmBtn = r?.let {
                ShopeeUIDiscovery.find(it, ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON, log = tries == 0)
            }
            if (confirmBtn == null) { closed = true; break }   // already closed
            // HARD SAFETY: the confirm must never be the place-order button.
            if (ShopeeUIDiscovery.isPlaceOrderNode(confirmBtn)) {
                KitLogger.e("SNIPE", "ABORT — voucher confirm resolved to place-order node; refusing to tap")
                return VoucherApplyResult.ConfirmNotFound
            }
            if (tries == 0) humanTap(confirmBtn)
            else safePerformAction(confirmBtn, AccessibilityNodeInfo.ACTION_CLICK)
            closed = waitForElementGone(ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON, timeoutMs = DRAWER_CLOSE_TIMEOUT_MS)
            tries++
        }
        if (!closed) {
            KitLogger.w("SNIPE", "voucher confirm did not close the drawer after $tries tries")
            return VoucherApplyResult.ConfirmNotFound
        }

        // ── 2-step rehearsal: opening the drawer + confirming "Đồng ý" IS the pass. ──
        // Platform/flash vouchers are time-gated and only apply AT the release instant,
        // so before then the checkout row can't show "applied" — requiring it would
        // wrongly fail the test. The rehearsal only proves the app can drive the UI
        // (open → Đồng ý), which is the same "refresh" action that applies the voucher
        // once it's live. Best-effort read the row value for display (usually null pre-T).
        if (!requireApplied) {
            // An explicit-selection mode that picked nothing is a miss, not a pass —
            // retry rather than report a voucher we never selected.
            if (!selectionSucceeded) {
                KitLogger.w("SNIPE", "explicit selection found no voucher (${preference::class.simpleName}) — NothingApplied")
                return VoucherApplyResult.NothingApplied
            }
            val value = platformVoucherRowValue(rootInActiveWindow)
            KitLogger.i("SNIPE", "voucher confirm OK (2-step rehearsal) tries=$tries value=$value")
            return VoucherApplyResult.Applied(voucherLabel = selectedLabel ?: value, discountText = value)
        }

        // Back on checkout — VERIFY a platform voucher is actually applied. "Drawer
        // closed" alone is a FALSE-POSITIVE: if Shopee had nothing eligible, tapping
        // "Đồng ý" just closes the drawer and the "Shopee Voucher" row keeps its empty
        // "Chọn hoặc nhập mã" placeholder. The RELIABLE checkout-side signal is the
        // platform row VALUE changing away from that placeholder (verified live: it
        // becomes the applied voucher, e.g. "Miễn Phí Vận Chuyển" / "giảm {amount}").
        // The old `viewPlatformVoucherSelected` node and "đã áp dụng"/"tự động chọn"
        // text live ONLY inside the drawer, not on the checkout — so checking for them
        // here false-negatived every time. Poll briefly: the row value re-renders a
        // beat after the drawer dismisses.
        var parsed: VoucherApplyParser.Result? = null
        var applied = false
        var lastState = VoucherRowState.ABSENT
        var lastSelectedId = false
        // Positive-confirm poll. Ceiling is generous (order is gated on it), but to
        // avoid dead-waiting the full ceiling on a device where the row value is simply
        // unreadable, bail once the row has read PRESENT-yet-UNREADABLE for a short
        // probe — UNLESS we've ever seen the empty placeholder (then keep the full
        // window; it may flip to APPLIED and we must not shortcut an empty row). ABSENT
        // (row not found / occluded mid-close) does NOT count toward the probe.
        var sawPlaceholder = false
        var unreadableSinceMs = 0L
        val verifyDeadline = System.currentTimeMillis() + VOUCHER_VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < verifyDeadline) {
            val postRoot = rootInActiveWindow
            if (postRoot != null) {
                lastState = platformVoucherRowState(postRoot)
                lastSelectedId = hasNodeWithIdSuffix(postRoot, "viewPlatformVoucherSelected")
                parsed = VoucherApplyParser.parse(extractAllText(postRoot))
                if (lastState == VoucherRowState.APPLIED || lastSelectedId || parsed.applied) {
                    applied = true
                    break
                }
                when (lastState) {
                    VoucherRowState.PLACEHOLDER -> { sawPlaceholder = true; unreadableSinceMs = 0L }
                    VoucherRowState.UNREADABLE -> {
                        if (!sawPlaceholder) {
                            val now = System.currentTimeMillis()
                            if (unreadableSinceMs == 0L) unreadableSinceMs = now
                            else if (now - unreadableSinceMs >= VERIFY_UNKNOWN_PROBE_MS) break
                        }
                    }
                    else -> unreadableSinceMs = 0L   // ABSENT → keep waiting, don't bail
                }
            }
            android.os.SystemClock.sleep(POLL_STEP_MS)
        }
        if (applied) {
            val discountText = platformVoucherRowValue(rootInActiveWindow) ?: parsed?.discountText
            KitLogger.i(
                "SNIPE",
                "voucher APPLIED (verified) pref=${preference::class.simpleName} label=$selectedLabel discount=$discountText tries=$tries"
            )
            return VoucherApplyResult.Applied(voucherLabel = selectedLabel ?: discountText, discountText = discountText)
        }
        // No positive confirm. Decide on the STRONGEST negative: block ONLY when we can
        // read the row as the empty "Chọn hoặc nhập mã" placeholder. Settle briefly
        // first so a still-rendering UNREADABLE→PLACEHOLDER (genuinely empty) isn't
        // shortcut into a voucher-less order after an early bail, and a late APPLIED is
        // still caught. If the row stays unreadable/absent — the common cross-version
        // case that used to loop forever — trust the confirmed voucher pass (drawer
        // opened + Đồng ý tapped + closed, the SAME evidence the 2-step rehearsal passes
        // on) and proceed. The user validates the applied voucher in 2-step first.
        val settleDeadline = System.currentTimeMillis() + VERIFY_SETTLE_MS
        while (System.currentTimeMillis() < settleDeadline) {
            when (platformVoucherRowState(rootInActiveWindow)) {
                VoucherRowState.APPLIED -> {
                    val v = platformVoucherRowValue(rootInActiveWindow)
                    KitLogger.i("SNIPE", "voucher APPLIED (late render) pref=${preference::class.simpleName} discount=$v tries=$tries")
                    return VoucherApplyResult.Applied(voucherLabel = selectedLabel ?: v, discountText = v)
                }
                VoucherRowState.PLACEHOLDER -> {
                    KitLogger.w(
                        "SNIPE",
                        "3-step verify: row shows 'Chọn hoặc nhập mã' (proven empty) " +
                            "pref=${preference::class.simpleName} tries=$tries → NOT ordering, will retry"
                    )
                    return VoucherApplyResult.NothingApplied
                }
                else -> android.os.SystemClock.sleep(POLL_STEP_MS)
            }
        }
        KitLogger.w(
            "SNIPE",
            "3-step verify: could not confirm applied, no empty placeholder either " +
                "(lastState=$lastState selectedId=$lastSelectedId parsedApplied=${parsed?.applied}) — " +
                "trusting confirmed voucher pass, PROCEEDING to order"
        )
        val v = platformVoucherRowValue(rootInActiveWindow)
        return VoucherApplyResult.Applied(voucherLabel = selectedLabel ?: v, discountText = v)
    }

    /**
     * Poll the active window until [element] appears on [screen], or [timeoutMs]
     * elapses. Returns the root that contains it, or null on timeout.
     * Uses SystemClock.sleep (OK on non-main thread called from AccessibilityService
     * callback) rather than Thread.sleep to avoid spurious ANR traces.
     */
    private fun waitForElement(
        screen: ShopeeScreen,
        element: ShopeeElement,
        timeoutMs: Long = 800L,
        stepMs: Long = POLL_STEP_MS
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null &&
                ShopeeUIDiscovery.find(root, screen, element, requireClickable = false, log = false) != null
            ) {
                return root
            }
            android.os.SystemClock.sleep(stepMs)
        }
        return rootInActiveWindow
    }

    /**
     * Poll until [element] is NO LONGER present on [screen], i.e. a sheet/page has
     * closed. Returns true if it disappeared within [timeoutMs].
     *
     * Needed because the checkout stays mounted BEHIND the voucher page, so its
     * place-order button is always findable — "place-order visible again" is NOT a
     * reliable "drawer closed" signal, but "confirm button gone" is.
     */
    private fun waitForElementGone(
        screen: ShopeeScreen,
        element: ShopeeElement,
        timeoutMs: Long = 2000L,
        stepMs: Long = POLL_STEP_MS
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root == null ||
                ShopeeUIDiscovery.find(root, screen, element, requireClickable = false, log = false) == null
            ) {
                return true
            }
            android.os.SystemClock.sleep(stepMs)
        }
        return false
    }

    private fun selectVoucherByMaxDiscount(root: AccessibilityNodeInfo): String? {
        val items = ShopeeUIDiscovery.findAll(root, ShopeeScreen.VOUCHER_LIST, ShopeeElement.VOUCHER_LIST_ITEM)
        if (items.isEmpty()) return null

        // Parse discount amounts from each item's subtree
        val best = items.maxByOrNull { extractDiscountAmount(it) } ?: return null
        humanTap(best)
        return best.text?.toString() ?: extractDiscountText(best)
    }

    private fun selectVoucherByMaxCashback(root: AccessibilityNodeInfo): String? {
        val items = ShopeeUIDiscovery.findAll(root, ShopeeScreen.VOUCHER_LIST, ShopeeElement.VOUCHER_LIST_ITEM)
        if (items.isEmpty()) return null

        val best = items.maxByOrNull { extractCashbackPercent(it) } ?: return null
        humanTap(best)
        return best.text?.toString() ?: extractDiscountText(best)
    }

    /**
     * Type [code] into the voucher-code input and SUBMIT it via the input's own
     * "Áp Dụng" button. Returns [code] only when both the text was set and the apply
     * button was tapped — otherwise null so the caller reports NothingApplied and
     * retries, rather than falsely claiming the code applied. (Setting the text alone
     * never adds the voucher to the list: the code was previously never submitted.)
     */
    private fun applyManualCode(root: AccessibilityNodeInfo, code: String): String? {
        val inputNode = traverseForInput(root) ?: return null
        safePerformAction(inputNode, AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
        }
        val textSet = try {
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            KitLogger.w("SNIPE", "manual code: ACTION_SET_TEXT failed: ${e.message}")
            false
        }
        if (!textSet) {
            KitLogger.w("SNIPE", "manual code: ACTION_SET_TEXT refused")
            return null
        }
        // The apply button typically enables only after the field has text, so
        // re-fetch the tree before resolving it.
        val submitRoot = rootInActiveWindow ?: root
        val applyBtn = ShopeeUIDiscovery.find(
            submitRoot, ShopeeScreen.VOUCHER_PICKER, ShopeeElement.VOUCHER_INPUT_APPLY
        )
        if (applyBtn == null) {
            KitLogger.w("SNIPE", "manual code: apply button ('Áp Dụng') not found — code not submitted")
            return null
        }
        // HARD SAFETY: never let the code-apply resolve to the place-order button.
        if (ShopeeUIDiscovery.isPlaceOrderNode(applyBtn)) {
            KitLogger.e("SNIPE", "ABORT — manual-code apply resolved to place-order node")
            return null
        }
        tapPreferClick(applyBtn)
        KitLogger.i("SNIPE", "manual code '$code' typed + submitted")
        return code
    }

    /** Extract absolute discount amount (e.g. "Giảm 200.000đ" → 200000) */
    private fun extractDiscountAmount(node: AccessibilityNodeInfo): Long {
        val text = extractDiscountText(node) ?: return 0L
        val cleaned = text.replace("[^0-9]".toRegex(), "")
        return cleaned.toLongOrNull() ?: 0L
    }

    /** Extract cashback percent (e.g. "Hoàn 15%" → 15) */
    private fun extractCashbackPercent(node: AccessibilityNodeInfo): Int {
        val text = extractDiscountText(node) ?: return 0
        val match = Regex("""(\d+)%""").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractDiscountText(node: AccessibilityNodeInfo): String? {
        if (node.text != null) return node.text.toString()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = extractDiscountText(child)
            if (text != null) return text
        }
        return null
    }

    private fun traverseForInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = traverseForInput(child)
            if (result != null) return result
        }
        return null
    }

    // ─── CheckoutSniper: Place Order ─────────────────────────────────────────

    private fun performPlaceOrder(): PlaceOrderResult {
        val root = rootInActiveWindow ?: return PlaceOrderResult.AccessibilityUnavailable

        val match = ShopeeUIDiscovery.findMatch(
            root, ShopeeScreen.CHECKOUT, ShopeeElement.PLACE_ORDER_BUTTON
        ) ?: return PlaceOrderResult.AccessibilityUnavailable

        KitLogger.i(
            "SNIPE",
            "place-order resolved by ${match.source} conf=${"%.2f".format(match.confidence)} → ${match.label}"
        )

        // FAIL SAFE: never tap a pure heuristic guess for the destructive
        // place-order action — that's exactly how a wrong button gets pressed.
        // If we can't identify it by a user pin or a real text match, refuse and
        // ask the user to calibrate the button once (guaranteed-correct path).
        if (match.source == ShopeeUIDiscovery.MatchSource.HEURISTIC) {
            KitLogger.w("SNIPE", "place-order only matched by heuristic — refusing to tap; calibration needed")
            return PlaceOrderResult.NeedsCalibration
        }

        val clicked = humanTap(match.node)
        if (!clicked) return PlaceOrderResult.AccessibilityUnavailable

        // Poll for the order-result screen and return the moment a terminal result
        // (success / PIN / error / OOS) renders, instead of always paying a flat
        // 500ms. The plain checkout screen parses as Unknown, so we keep polling
        // until Shopee navigates — the happy path returns in ~150-400ms, and a PIN
        // prompt is caught the instant it appears.
        return waitForOrderResult()
    }

    /**
     * Poll the active window until [OrderResultParser] yields a terminal result
     * (anything other than [PlaceOrderResult.Unknown]) or [timeoutMs] elapses.
     * Early-exit keeps post-tap latency at the result's real render time rather
     * than a fixed wait. Parsing is quiet (log=false) to avoid flooding the Debug
     * Log with one line per poll; the resolved result is logged once.
     */
    private fun waitForOrderResult(
        timeoutMs: Long = ORDER_RESULT_TIMEOUT_MS,
        stepMs: Long = POLL_STEP_MS
    ): PlaceOrderResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: PlaceOrderResult = PlaceOrderResult.Unknown("no result yet")
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val r = OrderResultParser.parse(extractAllText(root), log = false)
                if (r !is PlaceOrderResult.Unknown) {
                    KitLogger.i("SNIPE", "order result: ${r::class.simpleName} after ${System.currentTimeMillis() - (deadline - timeoutMs)}ms")
                    return r
                }
                last = r
            }
            android.os.SystemClock.sleep(stepMs)
        }
        KitLogger.d("SNIPE", "order-result poll timed out → Unknown")
        return last
    }

    /**
     * Tap a node like a human: a gesture at an off-centre point inside the
     * node's bounds with a randomised stroke duration. Falls back to
     * ACTION_CLICK if the node has no usable bounds or gesture dispatch fails.
     *
     * Off-centre, variable-duration taps avoid the pixel-perfect, fixed-length
     * signature that a server-side risk model can flag (see [HumanBehavior]).
     */
    private fun humanTap(node: AccessibilityNodeInfo): Boolean {
        // Re-sync the node before reading its bounds: it may have been resolved a few
        // poll cycles ago and Shopee's RN tree can have re-rendered since, leaving the
        // cached bounds stale. refresh() returns false if the node is gone.
        try { node.refresh() } catch (_: Exception) { /* best-effort */ }
        val bounds = android.graphics.Rect().also {
            try { node.getBoundsInScreen(it) } catch (_: Exception) { it.setEmpty() }
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return safePerformAction(node, AccessibilityNodeInfo.ACTION_CLICK)
        }
        val (x, y) = HumanBehavior.tapPointIn(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, HumanBehavior.tapDurationMs()))
            .build()
        // dispatchGesture is ASYNC: it returns before the stroke lands. Block this
        // (non-main) worker thread until the gesture completes, so callers never
        // race ahead of the tap — that race made the engine declare the voucher
        // "applied" ~40ms before the "Đồng ý" tap even finished, so the confirm
        // never took and the drawer stayed open.
        val latch = java.util.concurrent.CountDownLatch(1)
        var completed = false
        var cancelled = false
        val cb = object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { completed = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { cancelled = true; latch.countDown() }
        }
        val dispatched = dispatchGesture(gesture, cb, null)
        if (!dispatched) {
            KitLogger.w("TAP", "dispatch refused → ACTION_CLICK fallback")
            return safePerformAction(node, AccessibilityNodeInfo.ACTION_CLICK)
        }
        val signalled = try {
            latch.await(700, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) { false }
        KitLogger.d("TAP", "@(${x.toInt()},${y.toInt()}) box=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] done=$completed cancelled=$cancelled")
        // Fall back to ACTION_CLICK ONLY when the gesture was genuinely CANCELLED
        // (never landed). On a latch timeout with no callback the stroke was already
        // dispatched and almost certainly landed — a fallback tap here would DOUBLE-tap,
        // which on the place-order button means a duplicate order. Trust the dispatch.
        return when {
            completed -> true
            cancelled -> safePerformAction(node, AccessibilityNodeInfo.ACTION_CLICK)
            else -> {
                if (!signalled) KitLogger.w("TAP", "gesture callback timed out — assuming landed, no fallback tap")
                true
            }
        }
    }

    /**
     * [AccessibilityNodeInfo.performAction] on a node that Shopee's RN tree has
     * recycled throws IllegalStateException; wrap it so a mid-flight window change
     * degrades to a no-op (false) instead of crashing the engine coroutine.
     */
    private fun safePerformAction(node: AccessibilityNodeInfo, action: Int): Boolean =
        try {
            node.performAction(action)
        } catch (e: Exception) {
            KitLogger.w("TAP", "performAction($action) failed on stale node: ${e.message}")
            false
        }

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        if (node.text != null) sb.append(node.text).append(" ")
        if (node.contentDescription != null) sb.append(node.contentDescription).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(extractAllText(child))
        }
        return sb.toString()
    }

    /** True if any node in the subtree carries a resource-id ending in [suffix]. */
    private fun hasNodeWithIdSuffix(node: AccessibilityNodeInfo, suffix: String): Boolean {
        if (node.viewIdResourceName?.substringAfterLast('/') == suffix) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasNodeWithIdSuffix(child, suffix)) return true
        }
        return false
    }

    /** First node in the subtree whose resource-id ends in [suffix], or null. */
    private fun findNodeByIdSuffix(node: AccessibilityNodeInfo, suffix: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.substringAfterLast('/') == suffix) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByIdSuffix(child, suffix)?.let { return it }
        }
        return null
    }

    /** True if any node in the subtree carries a resource-id whose short name starts with [prefix]. */
    private fun hasNodeWithIdPrefix(node: AccessibilityNodeInfo, prefix: String): Boolean {
        if (node.viewIdResourceName?.substringAfterLast('/')?.startsWith(prefix) == true) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasNodeWithIdPrefix(child, prefix)) return true
        }
        return false
    }

    /**
     * True once the voucher drawer's LIST has rendered (not just its "Đồng ý" button):
     * a voucher card / radio is present, or Shopee's auto-selected summary. This is the
     * "safe to confirm" signal — confirming before it would apply an empty selection.
     */
    private fun isVoucherListLoaded(root: AccessibilityNodeInfo): Boolean =
        hasNodeWithIdPrefix(root, "sectionVoucherCard_") ||
            hasNodeWithIdPrefix(root, "radioBtnVoucher_") ||
            hasNodeWithIdSuffix(root, "viewPlatformVoucherSelected") ||
            // id-free fallback so this early-exits instead of dead-waiting the full
            // ceiling when the card/radio ids shifted across a Shopee version. The
            // auto-select SUMMARY ("1 voucher đã được tự động chọn cho bạn") renders
            // only once the drawer's voucher list has loaded and Shopee auto-picked —
            // a specific drawer-only phrase, so it won't false-positive on the checkout
            // mounted behind the drawer (bare "tự động chọn" chrome would, so match the
            // full "đã được tự động chọn").
            UiMatch.normalize(extractAllText(root)).contains("da duoc tu dong chon")

    /** Poll until [isVoucherListLoaded] or [timeoutMs] elapses. Returns true if it loaded. */
    private fun waitForVoucherListLoaded(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && isVoucherListLoaded(root)) return true
            android.os.SystemClock.sleep(POLL_STEP_MS)
        }
        return false
    }

    /**
     * Text value shown in the platform "Shopee Voucher" row (`buttonCartPageUseVoucher`),
     * with the row label stripped — "Miễn Phí Vận Chuyển", "giảm 40.000đ", etc., or null
     * when the row is on its empty "Chọn hoặc nhập mã" placeholder. Ground-truth signal
     * for whether a platform voucher is applied ON THE CHECKOUT (the drawer's
     * `viewPlatformVoucherSelected` / "đã áp dụng" text do NOT appear here).
     */
    /**
     * Locate the checkout platform "Shopee Voucher" row. Prefer the stable id, but
     * FALL BACK to the text-discovered row when the id is absent — without this the
     * 3-step applied-verify depended solely on `buttonCartPageUseVoucher`, so on a
     * Shopee build where that id shifted the row opened fine by text (2-step passed)
     * but the verify never saw the value → NothingApplied → the order was never placed.
     */
    private fun findPlatformVoucherRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNodeByIdSuffix(root, "buttonCartPageUseVoucher")
            ?: ShopeeUIDiscovery.find(
                root, ShopeeScreen.CHECKOUT, ShopeeElement.VOUCHER_PICKER_ROW,
                requireClickable = false, log = false
            )

    /**
     * Applied-state of the platform voucher row for the 3-step verify. Four states so
     * the verify can (a) block only on the positively-empty PLACEHOLDER, (b) early-bail
     * the dead-wait only when the row is PRESENT-yet-UNREADABLE (visibly there, not the
     * placeholder, but no parseable value — the stable "can't read on this device"
     * case), and (c) KEEP waiting on ABSENT (row not found / still rendering / occluded
     * mid-close-animation), which must never trigger the early-bail — an ABSENT that
     * later renders the placeholder would otherwise be shortcut into a voucher-less order.
     */
    private enum class VoucherRowState { APPLIED, PLACEHOLDER, UNREADABLE, ABSENT }

    private fun platformVoucherRowState(root: AccessibilityNodeInfo?): VoucherRowState {
        root ?: return VoucherRowState.ABSENT
        val row = findPlatformVoucherRow(root) ?: return VoucherRowState.ABSENT
        val raw = extractAllText(row).trim()
        if (raw.isBlank()) return VoucherRowState.ABSENT   // present but not yet rendered
        if (UiMatch.normalize(raw).contains("chon hoac nhap ma")) return VoucherRowState.PLACEHOLDER
        // Strip the "Shopee Voucher" row label; anything left is the applied value.
        val value = raw.replace(Regex("(?i)shopee\\s*voucher"), "").trim()
        return if (value.isNotBlank()) VoucherRowState.APPLIED else VoucherRowState.UNREADABLE
    }

    private fun platformVoucherRowValue(root: AccessibilityNodeInfo?): String? {
        root ?: return null
        val row = findPlatformVoucherRow(root) ?: return null
        val raw = extractAllText(row).trim()
        if (raw.isBlank()) return null
        if (UiMatch.normalize(raw).contains("chon hoac nhap ma")) return null   // empty placeholder
        val value = raw.replace(Regex("(?i)shopee\\s*voucher"), "").trim()
        return value.ifBlank { null }
    }

    /**
     * Tap a node preferring the semantic click ACTION over a coordinate gesture.
     * Used for Shopee's clickable wrapper nodes (e.g. the voucher row) whose a11y
     * bounds are unreliable so a gesture would miss; ACTION_CLICK is position-free.
     * Falls back to a human gesture if the node isn't clickable or the action fails.
     */
    private fun tapPreferClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && safePerformAction(node, AccessibilityNodeInfo.ACTION_CLICK)) {
            KitLogger.d("TAP", "ACTION_CLICK ${node.viewIdResourceName?.substringAfterLast('/') ?: node.className}")
            return true
        }
        return humanTap(node)
    }

    // ─── CheckoutSniper: Idempotency Check ───────────────────────────────────

    /**
     * Detect if an order was recently created (handles network lag edge case).
     * Looks for order success screen or recent order in "Đơn hàng của tôi".
     */
    private fun detectRecentOrderCreated(): Boolean {
        val root = rootInActiveWindow ?: return false
        val text = extractAllText(root).lowercase()

        return text.contains("đặt hàng thành công") ||
            text.contains("order placed successfully") ||
            text.contains("đã đặt hàng") ||
            text.contains("mã đơn hàng") ||
            text.contains("order id") ||
            text.contains("order #")
    }

    // E5: check current screen has checkout signals (before firing)
    private fun detectCheckoutScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        val text = extractAllText(root).lowercase()
        // Must have place-order button text OR order total visible
        return text.contains("đặt hàng") || text.contains("place order") ||
            text.contains("tổng thanh toán") || text.contains("total payment")
    }

    // ─── CheckoutSniper: Warm-up (anti-fraud) ────────────────────────────────

    /**
     * E4: Scroll voucher picker list down then back to trigger RN lazy rendering.
     * Without this, items at the bottom of the list may not yet be in the a11y tree.
     */
    private fun scrollVoucherList(root: AccessibilityNodeInfo) {
        val scrollable = findScrollable(root) ?: return
        safePerformAction(scrollable, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id)
        android.os.SystemClock.sleep(150)
        safePerformAction(scrollable, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id)
        android.os.SystemClock.sleep(100)
    }

    /**
     * One unit of harmless "I'm looking at the screen" activity, run repeatedly
     * by CheckoutSniperEngine in the ~2s before fire. Scrolls a scrollable node
     * a little forward then back so the checkout state is unchanged, but the
     * session isn't dead-still right up to the millisecond it taps.
     */
    private fun performWarmUpNudge() {
        val root = rootInActiveWindow ?: return
        val scrollable = findScrollable(root) ?: return
        safePerformAction(scrollable, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id)
        android.os.SystemClock.sleep(HumanBehavior.tapDurationMs())
        safePerformAction(scrollable, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    /**
     * Setup 1px overlay window for receiving ACTION_OUTSIDE touch events.
     * Pattern from android-setup-guide.md (BVC project).
     */
    private fun setupOverlayWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        overlayView = android.view.View(this)
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            // Overlay permission not granted yet
        }
    }

    private fun removeOverlayWindow() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { /* ignore */ }
        overlayView = null
    }

    // ─── Cookie Auto-Sync ─────────────────────────────────────────────────────

    /**
     * Show a notification prompting user to sync cookie when:
     *  - Shopee app just came to foreground
     *  - Cookie is blank OR not refreshed in last 12 hours
     *  - Last notification was > 30 minutes ago (throttle)
     */
    private fun maybeShowSyncNotification() {
        val now = System.currentTimeMillis()
        if (now - lastSyncNotifMs < 30 * 60 * 1000L) return // throttle 30min

        // Read the cached flag (kept fresh by the collector in onServiceConnected)
        // instead of a blocking DataStore read on the main thread.
        if (!cookieBlank) return // cookie already set, no need to bother user

        lastSyncNotifMs = now
        Log.i(TAG, "Shopee foreground, cookie missing — showing sync notification")

        val intent = Intent(this, ShopeeCookieSyncActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("ShopeeKit — Đồng bộ tài khoản")
            .setContentText("Nhấn để đồng bộ session Shopee (cần 1 lần)")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_SYNC, notif)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_SYNC,
            "Cookie Sync",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Thông báo đồng bộ session Shopee"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
