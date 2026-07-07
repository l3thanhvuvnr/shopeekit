package com.personal.shopeekit.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.personal.shopeekit.core.logging.KitLogger
import com.personal.shopeekit.core.storage.AppDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Multi-strategy UI element discovery for the Shopee app.
 *
 * Discovery layers, highest priority first:
 *   Layer 1 — Scored text/desc + resource-id (diacritic-aware, thresholded — no substring traps)
 *   Layer 2 — Heuristic           (shape + position; last resort for label-less RN)
 *
 * Why not "first text match wins" (the old approach): Android's
 * `findAccessibilityNodeInfosByText` matches *case-insensitive substring*, so
 * "OK"/"Voucher"/"Áp dụng" hit many unrelated nodes and the code tapped the
 * first one in tree order — the root cause of wrong-button taps. Here every
 * candidate is scored and must clear a threshold, keyed off Shopee's stable
 * resource-ids (which win outright), so time-critical taps never rely on guessing.
 */
object ShopeeUIDiscovery {

    // ─── Screen / Element Enums ─────────────────────────────────────────────

    enum class ShopeeScreen(val key: String) {
        CHECKOUT("checkout"),
        VOUCHER_PICKER("voucher_picker"),
        VOUCHER_LIST("voucher_list"),
        ORDER_LIST("order_list")
    }

    enum class ShopeeElement(val key: String) {
        PLACE_ORDER_BUTTON("place_order_button"),
        VOUCHER_PICKER_ROW("voucher_picker_row"),
        AUTO_SELECT_VOUCHER("auto_select_voucher"),
        APPLY_VOUCHER_BUTTON("apply_voucher_button"),
        VOUCHER_INPUT_APPLY("voucher_input_apply"),
        VOUCHER_LIST_ITEM("voucher_list_item"),
        VOUCHER_DISCOUNT_TEXT("voucher_discount_text"),
        ORDER_SUCCESS_TEXT("order_success_text"),
        ORDER_LIST_ITEM("order_list_item"),
        PAYMENT_PIN_PROMPT("payment_pin_prompt");

        companion object {
            fun fromKey(key: String?): ShopeeElement? = entries.firstOrNull { it.key == key }
        }
    }

    /** Which discovery layer produced a match (and thus how much to trust it). */
    enum class MatchSource { TEXT, HEURISTIC }

    /**
     * A resolved node plus *why* we believe it's the target. Callers use
     * [confidence]/[source] to decide whether an action is safe: e.g. the
     * destructive place-order tap refuses a [MatchSource.HEURISTIC]-only guess
     * and asks the user to calibrate instead of risking the wrong button.
     */
    data class Match(
        val node: AccessibilityNodeInfo,
        val confidence: Double,
        val source: MatchSource,
        val label: String
    )

    // ─── Positive text labels by element ────────────────────────────────────

    // AUTHORITATIVE labels — extracted from Shopee VN 3.77.25's *bundled* React
    // Native i18n catalog (res/raw/bundle_v9.7z → strings/@shopee-rn/checkout|
    // voucher-pages/i18n/*vi*.json). The checkout screen (OPC) itself downloads
    // at runtime and exposes no stable resource IDs, but these are the exact
    // strings it renders, so scored text matching keys off ground truth.
    // normalize() is case/diacritic-insensitive → one form per string suffices.
    private val textLabels: Map<ShopeeElement, List<String>> = mapOf(
        // i18n: label_opc_place_order="ĐẶT HÀNG", label_place_order="Đặt hàng".
        ShopeeElement.PLACE_ORDER_BUTTON to listOf(
            "Đặt hàng", "Place Order"
        ),
        // i18n: label_opc_platform_voucher / label_platform_voucher = "Shopee Voucher"
        // (the platform row). The shop row is "Voucher của Shop" — vetoed below.
        ShopeeElement.VOUCHER_PICKER_ROW to listOf(
            "Shopee Voucher"
        ),
        ShopeeElement.AUTO_SELECT_VOUCHER to listOf(
            "Tự động chọn", "Chọn tốt nhất", "Áp dụng tốt nhất", "Auto select", "Best voucher"
        ),
        // Voucher drawer/sheet confirm. Ground truth (voucher-pages i18n):
        // voucher_drawer_button="Đồng ý" (the main drawer CTA — put it first),
        // voucher_list_label_ok="OK", voucher_wallet_title_button_apply="Áp dụng",
        // voucher_tnc_label_use_now="Dùng ngay".
        ShopeeElement.APPLY_VOUCHER_BUTTON to listOf(
            "Đồng ý", "OK", "Áp dụng", "Dùng ngay", "Apply"
        ),
        // The apply-typed-code button next to the voucher-code input (i18n:
        // label_voucher_input_apply="Áp Dụng"). Distinct from the drawer's main
        // "Đồng ý" confirm — kept as its own element so the manual-code path submits
        // the typed code instead of only setting the text.
        ShopeeElement.VOUCHER_INPUT_APPLY to listOf(
            "Áp Dụng", "Apply"
        ),
        ShopeeElement.ORDER_SUCCESS_TEXT to listOf(
            "Đặt hàng thành công", "Đã đặt hàng", "Order placed",
            "Thành công", "Successfully"
        ),
        ShopeeElement.PAYMENT_PIN_PROMPT to listOf(
            "Nhập mã PIN", "Mã PIN", "Nhập OTP", "Mã OTP",
            "ShopeePay PIN", "Xác thực thanh toán", "Enter PIN", "Payment PIN"
        )
    )

    // Text that must NOT be treated as the target, even if it contains a label
    // word — the classic false positives ("Đặt hàng thành công" ≠ the button).
    private val negativeLabels: Map<ShopeeElement, List<String>> = mapOf(
        ShopeeElement.PLACE_ORDER_BUTTON to listOf(
            // "mua sau" = label_opc_cancel_place_order (the Cancel/"buy later"
            // button that sits right next to place-order); "chi tiết" =
            // opc_payment_detail link. Never tap these.
            "thành công", "mua sau", "chi tiết", "chính sách", "lịch sử",
            "hướng dẫn", "điều khoản", "điều kiện"
        ),
        ShopeeElement.APPLY_VOUCHER_BUTTON to listOf(
            "điều khoản", "điều kiện", "không áp dụng", "không thể áp dụng", "hết hạn"
        ),
        ShopeeElement.VOUCHER_PICKER_ROW to listOf(
            // Checkout shows TWO voucher rows: "Voucher của Shop" (i18n
            // label_opc_shop_voucher) and "Shopee Voucher" (platform). We target
            // the platform row, so veto only the shop row. NOTE: don't veto
            // "nhập mã" — the platform row's value cell is "Chọn hoặc nhập mã".
            "của shop", "shop voucher",
            "đã hết hạn", "đã sử dụng", "không đủ điều kiện"
        )
    )

    private val contentDescKeywords: Map<ShopeeElement, List<String>> = mapOf(
        ShopeeElement.PLACE_ORDER_BUTTON to listOf("order", "đặt hàng", "checkout"),
        ShopeeElement.VOUCHER_PICKER_ROW to listOf("voucher", "coupon", "discount"),
        ShopeeElement.APPLY_VOUCHER_BUTTON to listOf("apply", "áp dụng", "confirm")
    )

    // STABLE resource-ids, verified from the live Shopee VN 3.77.25 tree on a real
    // device (uiautomator dump). These are the STRONGEST signal: Shopee's checkout
    // CTAs render as text-less ViewGroups (so text matching can't see them), but
    // they carry semantic, stable ids. Matched with top priority; text/position
    // stay as fallback in case an id is dropped by a future update. The id may be
    // on a non-clickable node (e.g. the "Đồng ý" TextView) → we resolve to its
    // clickable parent. Disabled buttons get an "_disabled" suffix (e.g.
    // buttonAction_disabled) which deliberately does NOT match, so we never tap a
    // disabled CTA.
    private val resourceIdHints: Map<ShopeeElement, List<String>> = mapOf(
        ShopeeElement.PLACE_ORDER_BUTTON to listOf("buttonPlaceOrder"),
        ShopeeElement.VOUCHER_PICKER_ROW to listOf("buttonCartPageUseVoucher"),
        ShopeeElement.APPLY_VOUCHER_BUTTON to listOf("btnOkVoucherSelectionSubmitSection")
    )

    /** True if [viewId] (full "pkg:id/name" or bare name) matches one of [hints] by its short name. */
    private fun matchesResourceId(viewId: String?, hints: List<String>): Boolean {
        if (viewId.isNullOrBlank() || hints.isEmpty()) return false
        val short = viewId.substringAfterLast('/')
        return hints.any { it == viewId || it == short }
    }

    /**
     * Hard safety veto: is this node Shopee's place-order button? The voucher-apply
     * flow calls this before every tap so it can NEVER press "Đặt hàng" while trying
     * to confirm a voucher (which would place a real order in 2-step test mode).
     */
    fun isPlaceOrderNode(node: AccessibilityNodeInfo): Boolean =
        node.viewIdResourceName?.substringAfterLast('/') == "buttonPlaceOrder"

    // Element-specific vertical prior (0 = no preference, 1 = strongly favour lower on screen)
    private fun positionPrior(element: ShopeeElement, cyRatio: Float): Double = when (element) {
        // place-order bar and the sheet's apply button both live near the bottom
        ShopeeElement.PLACE_ORDER_BUTTON, ShopeeElement.APPLY_VOUCHER_BUTTON,
        ShopeeElement.AUTO_SELECT_VOUCHER -> cyRatio.toDouble()
        else -> 0.0
    }

    // Real physical display size (px), published by the AccessibilityService. Used as
    // a STABLE reference for the off-screen phantom guard, because a live window root's
    // getBoundsInScreen can report a virtual width wider than the screen mid-animation.
    @Volatile private var displayWidthPx = 0
    @Volatile private var displayHeightPx = 0

    fun setDisplaySize(w: Int, h: Int) {
        if (w > 0 && h > 0) { displayWidthPx = w; displayHeightPx = h }
    }

    // ─── Main discovery entry point ──────────────────────────────────────────

    /**
     * Find a Shopee UI node. Returns the first suitable node, or null.
     * requireClickable=true (default) resolves to a tappable node/ancestor.
     *
     * Thin wrapper over [findMatch] for callers that don't need the confidence.
     */
    fun find(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement,
        requireClickable: Boolean = true,
        log: Boolean = true
    ): AccessibilityNodeInfo? = findMatch(root, screen, element, requireClickable, log)?.node

    /**
     * Like [find] but returns the confidence and which layer resolved it, so the
     * caller can fail safe (never blindly tap a low-confidence heuristic guess).
     * Every resolution is logged with source + confidence + label — this is the
     * primary signal for diagnosing/tuning wrong-button taps from the Debug Log.
     *
     * Pass [log]=false on hot polling paths (e.g. waitForElement) to avoid
     * burying the useful one-shot diagnostics under NOT-FOUND spam.
     */
    fun findMatch(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement,
        requireClickable: Boolean = true,
        log: Boolean = true
    ): Match? {
        // Layer 1: scored text/content-description match (resource-id first)
        findByScore(root, screen, element, requireClickable)?.let { (node, conf) ->
            return Match(node, conf, MatchSource.TEXT, nodeLabel(node)).also {
                if (log) KitLogger.d("UI", "find ${element.key} → L1 text conf=${pct(conf)} ${it.label}")
            }
        }

        // Layer 2: heuristic (shape + position) — inherently a guess, low confidence
        findByHeuristic(root, element)?.let { node ->
            return Match(node, HEURISTIC_CONFIDENCE, MatchSource.HEURISTIC, nodeLabel(node)).also {
                if (log) KitLogger.d("UI", "find ${element.key} → L2 heuristic ${it.label}")
            }
        }

        if (log) KitLogger.w("UI", "find ${element.key} → NOT FOUND (all layers failed)")
        return null
    }

    /** Heuristic (shape/position) matches carry no text/id evidence — treat as low trust. */
    private const val HEURISTIC_CONFIDENCE = 0.40

    private fun pct(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    /** Short human-facing label for a live node, for logs and diagnostics. */
    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        return when {
            !t.isNullOrBlank() -> "\"${t.take(30)}\""
            !d.isNullOrBlank() -> "[${d.take(30)}]"
            else -> node.className?.toString()?.substringAfterLast('.') ?: "?"
        }
    }

    // ─── Layer 1: Scored text / content-description ──────────────────────────

    private class ScoredCandidate(
        val target: AccessibilityNodeInfo,
        val score: Double,
        val cyRatio: Float,
        val shape: Double,
        val idMatch: Boolean
    )

    private fun findByScore(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement,
        requireClickable: Boolean
    ): Pair<AccessibilityNodeInfo, Double>? {
        val labels = textLabels[element] ?: emptyList()
        val negatives = negativeLabels[element] ?: emptyList()
        val descKeywords = contentDescKeywords[element] ?: emptyList()
        // Built-in hints PLUS any id learned/pinned on a previous run (persisted in
        // AppDataStore, preloaded into memCache on connect). This closes the
        // write→read loop: a CTA whose stable id shifted between Shopee versions is
        // still matched by its last-known id, and it's the substrate for a user pin.
        val cachedId = memCache[idKey(screen, element)]?.substringAfterLast('/')
        val idHints = (resourceIdHints[element] ?: emptyList())
            .let { if (cachedId != null && cachedId !in it) it + cachedId else it }
        if (labels.isEmpty() && idHints.isEmpty()) return null

        val rb = Rect().also { root.getBoundsInScreen(it) }
        val sw = rb.width().coerceAtLeast(1).toFloat()
        val sh = rb.height().coerceAtLeast(1).toFloat()

        val candidates = mutableListOf<ScoredCandidate>()
        forEachNode(root) { node ->
            val own = node.text?.toString()
            val desc = node.contentDescription?.toString()
            // Resource-id is the strongest signal and, crucially, the ONLY one for
            // Shopee's text-less CTA ViewGroups — so a node with no text/desc is
            // still a candidate when its id matches.
            val idMatch = matchesResourceId(node.viewIdResourceName, idHints)
            // An exact resource-id match is trusted even if Android reports the node
            // invisible: Shopee's RN wraps a CTA in a semantic-id node that is often
            // laid out at [0,0][0,0] (so isVisibleToUser=false), while the visible
            // content sits in children. That id node is still the correct, tappable
            // target (humanTap falls back to ACTION_CLICK). Every OTHER signal still
            // requires visibility — a genuinely off-screen text node is not a tap target.
            if (!idMatch && !node.isVisibleToUser) return@forEachNode
            if (own.isNullOrBlank() && desc.isNullOrBlank() && !idMatch) return@forEachNode

            // veto known false positives
            if (UiMatch.hasNegative(own, negatives) || UiMatch.hasNegative(desc, negatives)) {
                return@forEachNode
            }

            val textScore = maxOf(
                UiMatch.bestTextScore(own, labels),
                UiMatch.bestTextScore(desc, labels)
            )
            // content-description keyword match is a weaker signal; cap it below the text floor
            val descScore = if (descKeywords.any { kw ->
                    UiMatch.normalize(desc).contains(UiMatch.normalize(kw)) &&
                        UiMatch.normalize(kw).isNotEmpty()
                }) 0.65 else 0.0
            // Row-label boost: the voucher picker row is one accessible node that
            // concatenates label + value ("Shopee Voucher Miễn Phí Vận Chuyển"),
            // diluting the coverage-weighted textScore to ~0.68 (< threshold) so it
            // was never tapped. The label is always the prefix, so a whole-phrase
            // startsWith is a strong, unambiguous signal (the shop row is vetoed;
            // the "…ở mục Shopee Voucher" hint does NOT start with the label).
            val rowScore = if (element == ShopeeElement.VOUCHER_PICKER_ROW &&
                (UiMatch.startsWithAny(own, labels) || UiMatch.startsWithAny(desc, labels))
            ) 0.92 else 0.0
            val idScore = if (idMatch) 0.97 else 0.0
            val bestScore = maxOf(textScore, descScore, rowScore, idScore)
            if (bestScore < UiMatch.TEXT_ACCEPT_THRESHOLD) return@forEachNode

            val target = if (node.isClickable) node else findClickableParent(node)
            if (target == null || !target.isEnabled) return@forEachNode
            if (requireClickable && !target.isClickable) return@forEachNode

            val tb = Rect().also { target.getBoundsInScreen(it) }
            // Degenerate bounds ([0,0][0,0]) are the norm for Shopee's id-bearing CTA
            // wrappers. Keep such a node ONLY when the resource-id matched (it's the
            // real target, tapped via ACTION_CLICK); a degenerate text/heuristic node
            // would be tapping thin air, so drop it. For a kept degenerate node use
            // neutral shape/position — idMatch wins the ranking outright anyway.
            val degenerate = tb.width() <= 0 || tb.height() <= 0
            if (degenerate && !idMatch) return@forEachNode
            // Off-screen phantom guard: Shopee's RN lays out off-screen drawer/carousel
            // pages with REAL bounds shifted a full screen-width away (observed: a
            // "Đồng ý" node at x=[1102,2138] on a 1080-wide screen). Such a node is not
            // tappable and, worse, makes waitForElement think the voucher drawer opened
            // while it is still off-screen — so the whole apply happens against a
            // phantom (findVoucherItems=0, nothing applied). Require the tap centre to
            // fall inside the PHYSICAL display (the window root's own bounds are an
            // unreliable reference — mid-animation the RN root reports a virtual width
            // wider than the screen). (Degenerate id nodes are exempt — they sit at the
            // origin and are clicked via ACTION_CLICK, not by coordinate.)
            if (!degenerate) {
                val cx = tb.exactCenterX()
                val cyAbs = tb.exactCenterY()
                val screenW = if (displayWidthPx > 0) displayWidthPx else rb.right
                val screenH = if (displayHeightPx > 0) displayHeightPx else rb.bottom
                if (cx < 0 || cx > screenW || cyAbs < 0 || cyAbs > screenH) {
                    KitLogger.d("UI", "skip off-screen ${element.key} candidate @[${tb.left},${tb.top}][${tb.right},${tb.bottom}] (screen ${screenW}x${screenH})")
                    return@forEachNode
                }
            }
            val w = if (degenerate) 0f else tb.width() / sw
            val h = if (degenerate) 0f else tb.height() / sh
            val cy = if (degenerate) 0f else (tb.exactCenterY() - rb.top) / sh
            candidates.add(ScoredCandidate(target, bestScore, cy, UiMatch.buttonShapeScore(w, h), idMatch))
        }

        if (candidates.isEmpty()) return null

        // Rank: a resource-id match wins outright (Shopee's ids are semantic and
        // unambiguous), then by score, then by (position prior + button-shape).
        val winner = candidates.maxWithOrNull(
            compareBy(
                { if (it.idMatch) 1 else 0 },
                { it.score },
                { positionPrior(element, it.cyRatio) + it.shape }
            )
        ) ?: return null

        // Cache the winner's resource id as a fast hint (not authoritative).
        winner.target.viewIdResourceName?.let { cacheId(screen, element, it) }
        return winner.target to winner.score
    }

    // ─── Layer 2: Heuristic (shape + position) ───────────────────────────────

    private fun findByHeuristic(
        root: AccessibilityNodeInfo,
        element: ShopeeElement
    ): AccessibilityNodeInfo? = when (element) {
        // Place-order: performPlaceOrder() REFUSES a heuristic-only match and asks
        // for calibration, so this stays only as a diagnostic signal.
        ShopeeElement.PLACE_ORDER_BUTTON -> findBottomBarButton(root)
        // Voucher picker row: a wrong guess only opens a sheet — not destructive.
        ShopeeElement.VOUCHER_PICKER_ROW -> findVoucherPickerHeuristic(root)
        // ⚠️ APPLY_VOUCHER / AUTO_SELECT: NEVER a bottom-bar heuristic. The widest
        // bottom-bar button on the checkout screen IS "Đặt hàng" — guessing it here
        // once tapped place-order while "confirming a voucher" (a real order in
        // 2-step test mode). The confirm must be found by resource-id / exact text
        // or NOT AT ALL.
        else -> null
    }

    /**
     * The place-order / apply CTA is the widest, best-button-shaped clickable
     * element pinned near the bottom. RN renders it as View/TextView, not
     * Button — so we score by shape (not class) and prefer the lowest one.
     */
    private fun findBottomBarButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rb = Rect().also { root.getBoundsInScreen(it) }
        val sw = rb.width().coerceAtLeast(1).toFloat()
        val sh = rb.height().coerceAtLeast(1).toFloat()

        var best: AccessibilityNodeInfo? = null
        var bestKey = -1.0
        forEachNode(root) { node ->
            if (!node.isVisibleToUser || !node.isEnabled || !isClickableLike(node)) return@forEachNode
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (b.width() <= 0 || b.height() <= 0) return@forEachNode
            val w = b.width() / sw
            val h = b.height() / sh
            val cy = (b.exactCenterY() - rb.top) / sh
            // Shopee's "Đặt hàng" is a partial-width button pinned to the very
            // bottom (the bottom bar also holds the order total on the left), so
            // accept a fairly narrow width but require it be low and button-shaped.
            if (w < 0.25f || cy < 0.80f) return@forEachNode
            val shape = UiMatch.buttonShapeScore(w, h)
            if (shape <= 0.0) return@forEachNode
            val key = cy + shape                      // lower + button-shaped wins
            if (key > bestKey) {
                bestKey = key
                best = node
            }
        }
        return best
    }

    private fun findVoucherPickerHeuristic(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Require a voucher-specific word (not the over-broad "giảm"/"mã"), and a clickable target.
        val keys = listOf("voucher", "coupon")
        var found: AccessibilityNodeInfo? = null
        forEachNode(root) { node ->
            if (found != null || !node.isVisibleToUser) return@forEachNode
            val t = UiMatch.normalize(node.text?.toString())
            val d = UiMatch.normalize(node.contentDescription?.toString())
            if (keys.any { t.contains(it) || d.contains(it) }) {
                val target = if (node.isClickable) node else findClickableParent(node)
                if (target != null && target.isEnabled && target.isClickable) found = target
            }
        }
        return found
    }

    /**
     * A node is "clickable-like" if it accepts a tap. RN buttons surface as
     * clickable View/TextView/Layout nodes, so we require isClickable plus a
     * sane class rather than `className.contains("Button")`.
     */
    private fun isClickableLike(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        val cls = node.className?.toString() ?: return true
        return cls.contains("Button", ignoreCase = true) ||
            cls.contains("View") ||
            cls.contains("TextView") ||
            cls.contains("Layout") ||
            cls.contains("Compose")
    }

    // ─── findAll (voucher list items) ────────────────────────────────────────

    fun findAll(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement
    ): List<AccessibilityNodeInfo> {
        if (element == ShopeeElement.VOUCHER_LIST_ITEM) {
            return findVoucherItems(root)
        }
        val labels = textLabels[element] ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        forEachNode(root) { node ->
            if (!node.isVisibleToUser) return@forEachNode
            val ts = maxOf(
                UiMatch.bestTextScore(node.text?.toString(), labels),
                UiMatch.bestTextScore(node.contentDescription?.toString(), labels)
            )
            if (ts >= UiMatch.TEXT_ACCEPT_THRESHOLD) {
                val target = if (node.isClickable) node else findClickableParent(node)
                if (target != null && target.isEnabled) results.add(target)
            }
        }
        return results
    }

    private fun findVoucherItems(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val items = mutableListOf<AccessibilityNodeInfo>()
        collectVoucherItemNodes(root, items, depth = 0)
        KitLogger.d("UI", "findVoucherItems → ${items.size} items found")
        return items
    }

    private val moneyPattern =
        Regex("""(₫|\d[\d.]+đ|\d+%|Giảm|Hoàn|giảm|hoàn|discount|cashback)""")

    private fun collectVoucherItemNodes(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 8) return
        if (node.isClickable && node.isEnabled && depth >= 2) {
            val subtreeText = A11yTreeUtils.allText(node)
            val cls = node.className?.toString() ?: ""
            val isButton = cls.contains("Button", ignoreCase = true) && !cls.contains("View")
            if (!isButton && moneyPattern.containsMatchIn(subtreeText)) {
                results.add(node)
                return
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVoucherItemNodes(child, results, depth + 1)
        }
    }

    // ─── Tree traversal helpers ──────────────────────────────────────────────

    /** Visit every node (bounded depth) and run [action] on each. */
    private fun forEachNode(
        root: AccessibilityNodeInfo,
        maxDepth: Int = 40,
        action: (AccessibilityNodeInfo) -> Unit
    ) {
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            action(node)
            if (depth >= maxDepth) return
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
    }

    /** Walk up to find the nearest clickable, enabled ancestor (max 5 levels). */
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        repeat(5) {
            val c = current ?: return null
            if (c.isClickable && c.isEnabled) return c
            current = c.parent
        }
        return null
    }

    // ─── Cache (DataStore backed) ────────────────────────────────────────────

    private val memCache = mutableMapOf<String, String>()          // resource-id hints
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private fun idKey(screen: ShopeeScreen, element: ShopeeElement) =
        "shopee_ui_${screen.key}_${element.key}"

    private fun cacheId(screen: ShopeeScreen, element: ShopeeElement, id: String) {
        if (id.isBlank()) return
        // Never cache a disabled-CTA id (e.g. buttonAction_disabled): the matcher
        // deliberately refuses `_disabled` ids, so caching one leaves a dead hint
        // that resolves to nothing on later runs.
        if (id.substringAfterLast('/').endsWith("_disabled")) return
        val key = idKey(screen, element)
        if (memCache[key] == id) return
        memCache[key] = id
        ioScope.launch { AppDataStore.setString(key, id) }
    }

    /**
     * Load persisted id hints from [AppDataStore] into [memCache] so [findByScore]
     * consults them from the first find. Call once on service connect. Fire-and-forget
     * on the IO scope — a find that races ahead of it simply uses the built-in hints.
     */
    fun preloadCache() {
        ioScope.launch {
            for (screen in ShopeeScreen.entries) {
                for (element in ShopeeElement.entries) {
                    val key = idKey(screen, element)
                    val id = AppDataStore.getString(key) ?: continue
                    memCache[key] = id
                }
            }
            KitLogger.d("UI", "preloadCache — ${memCache.size} id hint(s) restored")
        }
    }

}
