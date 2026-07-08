package com.personal.shopeekit.service

import android.view.accessibility.AccessibilityNodeInfo
import com.personal.shopeekit.core.logging.KitLogger
import com.personal.shopeekit.features.checkout.VoucherApplyParser
import com.personal.shopeekit.features.checkout.VoucherApplyResult
import com.personal.shopeekit.features.checkout.VoucherPreference
import com.personal.shopeekit.service.CheckoutActuator.Companion.POLL_STEP_MS
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeElement
import com.personal.shopeekit.service.ShopeeUIDiscovery.ShopeeScreen
import kotlinx.coroutines.delay

/**
 * The "apply best voucher" flow, extracted verbatim from ShopeeAccessibilityService.
 * Open the "Shopee Voucher" row → select per preference → confirm "Đồng ý" → verify.
 * Drives the UI via a [CheckoutActuator] (taps + polling). No behaviour change.
 */
class VoucherApplyFlow(private val act: CheckoutActuator) {

    companion object {
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

        // Prewarm: open the drawer once BEFORE T to warm the RN component + TLS
        // connection + JS bundle, so the real open at T renders/fetches faster.
        // Short ceilings — this runs pre-fire and must finish well before T.
        private const val PREWARM_OPEN_TIMEOUT_MS = 1200L
        private const val PREWARM_CLOSE_TIMEOUT_MS = 600L
    }

    /**
     * Open the voucher drawer once and dismiss it, WITHOUT confirming — a warm-up so
     * the real open at the release instant reuses a warm RN component, TLS
     * connection, and loaded bundle. Runs only pre-T, from the engine's warm-up
     * window. Best-effort: any failure is a no-op and the fire path is unaffected.
     *
     * HARD SAFETY: never taps "Đồng ý"/place-order — dismiss is the system Back
     * action, so nothing is confirmed or applied. Pre-T the drawer list is empty /
     * not-live, so there is nothing to select; the checkout row stays on its
     * "Chọn hoặc nhập mã" placeholder (drawer-skip unaffected).
     */
    suspend fun prewarm() {
        val root = act.root ?: return
        val row = ShopeeUIDiscovery.find(root, ShopeeScreen.CHECKOUT, ShopeeElement.VOUCHER_PICKER_ROW, log = false)
            ?: return
        if (ShopeeUIDiscovery.isPlaceOrderNode(row)) return   // never risk the order button

        val t0 = System.currentTimeMillis()
        act.tapPreferClick(row)

        // Wait briefly for the drawer's confirm button to render — this is what warms
        // the RN sheet + triggers the (pre-T, empty) network fetch. We do NOT tap it.
        val drawerRoot = act.waitForElement(
            ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON,
            timeoutMs = PREWARM_OPEN_TIMEOUT_MS
        )
        val opened = drawerRoot != null && ShopeeUIDiscovery.find(
            drawerRoot, ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON,
            requireClickable = false, log = false
        ) != null

        // Dismiss via Back (never confirm), then verify the drawer actually closed so
        // the fire loop starts from the same checkout state as an un-prewarmed run.
        act.back()
        val closed = act.waitForElementGone(
            ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON,
            timeoutMs = PREWARM_CLOSE_TIMEOUT_MS
        )
        val ms = System.currentTimeMillis() - t0
        if (opened && closed) {
            KitLogger.i("SNIPE", "prewarm OK — drawer opened+dismissed in ${ms}ms (warm for T)")
        } else {
            KitLogger.w("SNIPE", "prewarm partial — opened=$opened closed=$closed in ${ms}ms (fire loop still recovers)")
        }
    }

    /**
     * Apply best voucher on the current checkout screen.
     * Steps: open "Shopee Voucher" row → select per preference → confirm → verify
     * the drawer closed and read the applied discount. Each failure is reported
     * distinctly so the 2-step rehearsal can tell the user exactly where it broke
     * and the 3-step run can refuse to order when the voucher never applied.
     */
    suspend fun apply(
        preference: VoucherPreference,
        requireApplied: Boolean
    ): VoucherApplyResult {
        val root = act.root ?: return VoucherApplyResult.AccessibilityUnavailable

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
        act.tapPreferClick(pickerRow)

        // Step 2: wait for the drawer to render (its confirm button appears). RN
        // loads the sheet async, so poll rather than sleep a fixed time.
        val drawerRoot = act.waitForElement(
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
                selectVoucherByMaxDiscount(act.root ?: drawerRoot)
                    .also { if (it == null) selectionSucceeded = false }
            }
            is VoucherPreference.MaxCashback -> {
                scrollVoucherList(drawerRoot!!)
                selectVoucherByMaxCashback(act.root ?: drawerRoot)
                    .also { if (it == null) selectionSucceeded = false }
            }
            is VoucherPreference.ManualCode ->
                applyManualCode(act.root ?: drawerRoot!!, preference.code)
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
            val r = act.root
            val confirmBtn = r?.let {
                ShopeeUIDiscovery.find(it, ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON, log = tries == 0)
            }
            if (confirmBtn == null) { closed = true; break }   // already closed
            // HARD SAFETY: the confirm must never be the place-order button.
            if (ShopeeUIDiscovery.isPlaceOrderNode(confirmBtn)) {
                KitLogger.e("SNIPE", "ABORT — voucher confirm resolved to place-order node; refusing to tap")
                return VoucherApplyResult.ConfirmNotFound
            }
            if (tries == 0) act.humanTap(confirmBtn)
            else act.safePerformAction(confirmBtn, AccessibilityNodeInfo.ACTION_CLICK)
            closed = act.waitForElementGone(ShopeeScreen.VOUCHER_PICKER, ShopeeElement.APPLY_VOUCHER_BUTTON, timeoutMs = DRAWER_CLOSE_TIMEOUT_MS)
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
            val value = platformVoucherRowValue(act.root)
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
            val postRoot = act.root
            if (postRoot != null) {
                lastState = platformVoucherRowState(postRoot)
                lastSelectedId = A11yTreeUtils.hasNodeWithIdSuffix(postRoot, "viewPlatformVoucherSelected")
                parsed = VoucherApplyParser.parse(A11yTreeUtils.allText(postRoot))
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
            delay(POLL_STEP_MS)
        }
        if (applied) {
            val discountText = platformVoucherRowValue(act.root) ?: parsed?.discountText
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
            when (platformVoucherRowState(act.root)) {
                VoucherRowState.APPLIED -> {
                    val v = platformVoucherRowValue(act.root)
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
                else -> delay(POLL_STEP_MS)
            }
        }
        KitLogger.w(
            "SNIPE",
            "3-step verify: could not confirm applied, no empty placeholder either " +
                "(lastState=$lastState selectedId=$lastSelectedId parsedApplied=${parsed?.applied}) — " +
                "trusting confirmed voucher pass, PROCEEDING to order"
        )
        val v = platformVoucherRowValue(act.root)
        return VoucherApplyResult.Applied(voucherLabel = selectedLabel ?: v, discountText = v)
    }

    private suspend fun selectVoucherByMaxDiscount(root: AccessibilityNodeInfo): String? {
        val items = ShopeeUIDiscovery.findAll(root, ShopeeScreen.VOUCHER_LIST, ShopeeElement.VOUCHER_LIST_ITEM)
        if (items.isEmpty()) return null

        // Parse discount amounts from each item's subtree
        val best = items.maxByOrNull { extractDiscountAmount(it) } ?: return null
        act.humanTap(best)
        return best.text?.toString() ?: extractDiscountText(best)
    }

    private suspend fun selectVoucherByMaxCashback(root: AccessibilityNodeInfo): String? {
        val items = ShopeeUIDiscovery.findAll(root, ShopeeScreen.VOUCHER_LIST, ShopeeElement.VOUCHER_LIST_ITEM)
        if (items.isEmpty()) return null

        val best = items.maxByOrNull { extractCashbackPercent(it) } ?: return null
        act.humanTap(best)
        return best.text?.toString() ?: extractDiscountText(best)
    }

    /**
     * Type [code] into the voucher-code input and SUBMIT it via the input's own
     * "Áp Dụng" button. Returns [code] only when both the text was set and the apply
     * button was tapped — otherwise null so the caller reports NothingApplied and
     * retries, rather than falsely claiming the code applied. (Setting the text alone
     * never adds the voucher to the list: the code was previously never submitted.)
     */
    private suspend fun applyManualCode(root: AccessibilityNodeInfo, code: String): String? {
        val inputNode = traverseForInput(root) ?: return null
        act.safePerformAction(inputNode, AccessibilityNodeInfo.ACTION_FOCUS)
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
        val submitRoot = act.root ?: root
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
        act.tapPreferClick(applyBtn)
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

    /**
     * True once the voucher drawer's LIST has rendered (not just its "Đồng ý" button):
     * a voucher card / radio is present, or Shopee's auto-selected summary. This is the
     * "safe to confirm" signal — confirming before it would apply an empty selection.
     */
    private fun isVoucherListLoaded(root: AccessibilityNodeInfo): Boolean =
        A11yTreeUtils.hasNodeWithIdPrefix(root, "sectionVoucherCard_") ||
            A11yTreeUtils.hasNodeWithIdPrefix(root, "radioBtnVoucher_") ||
            A11yTreeUtils.hasNodeWithIdSuffix(root, "viewPlatformVoucherSelected") ||
            // id-free fallback so this early-exits instead of dead-waiting the full
            // ceiling when the card/radio ids shifted across a Shopee version. The
            // auto-select SUMMARY ("1 voucher đã được tự động chọn cho bạn") renders
            // only once the drawer's voucher list has loaded and Shopee auto-picked —
            // a specific drawer-only phrase, so it won't false-positive on the checkout
            // mounted behind the drawer (bare "tự động chọn" chrome would, so match the
            // full "đã được tự động chọn").
            UiMatch.normalize(A11yTreeUtils.allText(root)).contains("da duoc tu dong chon")

    /** Poll until [isVoucherListLoaded] or [timeoutMs] elapses. Returns true if it loaded. */
    private suspend fun waitForVoucherListLoaded(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = act.root
            if (root != null && isVoucherListLoaded(root)) return true
            delay(POLL_STEP_MS)
        }
        return false
    }

    /**
     * Locate the checkout platform "Shopee Voucher" row. Prefer the stable id, but
     * FALL BACK to the text-discovered row when the id is absent — without this the
     * 3-step applied-verify depended solely on `buttonCartPageUseVoucher`, so on a
     * Shopee build where that id shifted the row opened fine by text (2-step passed)
     * but the verify never saw the value → NothingApplied → the order was never placed.
     */
    private fun findPlatformVoucherRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        A11yTreeUtils.findNodeByIdSuffix(root, "buttonCartPageUseVoucher")
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
        val raw = A11yTreeUtils.allText(row).trim()
        if (raw.isBlank()) return VoucherRowState.ABSENT   // present but not yet rendered
        if (UiMatch.normalize(raw).contains("chon hoac nhap ma")) return VoucherRowState.PLACEHOLDER
        // Strip the "Shopee Voucher" row label; anything left is the applied value.
        val value = raw.replace(Regex("(?i)shopee\\s*voucher"), "").trim()
        return if (value.isNotBlank()) VoucherRowState.APPLIED else VoucherRowState.UNREADABLE
    }

    private fun platformVoucherRowValue(root: AccessibilityNodeInfo?): String? {
        root ?: return null
        val row = findPlatformVoucherRow(root) ?: return null
        val raw = A11yTreeUtils.allText(row).trim()
        if (raw.isBlank()) return null
        if (UiMatch.normalize(raw).contains("chon hoac nhap ma")) return null   // empty placeholder
        val value = raw.replace(Regex("(?i)shopee\\s*voucher"), "").trim()
        return value.ifBlank { null }
    }

    /**
     * E4: Scroll voucher picker list down then back to trigger RN lazy rendering.
     * Without this, items at the bottom of the list may not yet be in the a11y tree.
     */
    private suspend fun scrollVoucherList(root: AccessibilityNodeInfo) {
        val scrollable = A11yTreeUtils.findScrollable(root) ?: return
        act.safePerformAction(scrollable, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id)
        delay(150)
        act.safePerformAction(scrollable, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id)
        delay(100)
    }
}
