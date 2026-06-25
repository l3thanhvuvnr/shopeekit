package com.personal.shopeekit.service

import android.view.accessibility.AccessibilityNodeInfo
import com.personal.shopeekit.core.storage.AppDataStore
import kotlinx.coroutines.runBlocking

/**
 * Multi-strategy UI element discovery for Shopee app.
 *
 * Solves the hardcoded resource ID problem with 4-layer fallback:
 *   Layer 1 — Cached resource ID (learned from previous sessions)
 *   Layer 2 — Text content match (Vietnamese + English)
 *   Layer 3 — Content description match
 *   Layer 4 — Heuristic (position + role)
 *
 * Self-healing: if cached ID no longer valid (Shopee update), automatically
 * falls back to text search and re-caches the new ID.
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
        VOUCHER_LIST_ITEM("voucher_list_item"),
        VOUCHER_DISCOUNT_TEXT("voucher_discount_text"),
        ORDER_SUCCESS_TEXT("order_success_text"),
        ORDER_LIST_ITEM("order_list_item")
    }

    // ─── Text labels by screen element ──────────────────────────────────────

    // Text labels verified against Shopee VN 3.76.25 i18n (dre-extract/.../i18n/live/vi).
    // The checkout screen (module OPC_HOME) is React Native — no native resource IDs —
    // so text + content-description matching is the primary discovery path.
    private val textLabels: Map<ShopeeElement, List<String>> = mapOf(
        ShopeeElement.PLACE_ORDER_BUTTON to listOf(
            "Đặt hàng", "Đặt Hàng", "ĐẶT HÀNG", "Place Order", "Place order"
        ),
        ShopeeElement.VOUCHER_PICKER_ROW to listOf(
            // "Lấy Voucher để nhận giảm giá" = cart voucher-recommend section title (3.76.25)
            "Chọn Voucher khác", "Lấy Voucher để nhận giảm giá",
            "Thêm voucher Shopee", "Shopee Voucher", "Chọn voucher",
            "Voucher", "Thêm mã giảm giá", "Select Voucher", "Add voucher"
        ),
        ShopeeElement.AUTO_SELECT_VOUCHER to listOf(
            "Tự động chọn", "Áp dụng tốt nhất", "Chọn tốt nhất", "Tự chọn",
            "Auto select", "Best voucher"
        ),
        ShopeeElement.APPLY_VOUCHER_BUTTON to listOf(
            // "Áp dụng" (label_apply) + "Dùng ngay" (label_use) verified in 3.76.25
            "Áp dụng", "Dùng ngay", "OK", "Xác nhận", "Đồng ý", "Apply"
        ),
        ShopeeElement.ORDER_SUCCESS_TEXT to listOf(
            "Đặt hàng thành công", "Đã đặt hàng", "Order placed",
            "Thành công", "Successfully"
        )
    )

    private val contentDescKeywords: Map<ShopeeElement, List<String>> = mapOf(
        ShopeeElement.PLACE_ORDER_BUTTON to listOf("order", "đặt hàng", "checkout"),
        ShopeeElement.VOUCHER_PICKER_ROW to listOf("voucher", "coupon", "discount"),
        ShopeeElement.APPLY_VOUCHER_BUTTON to listOf("apply", "áp dụng", "confirm")
    )

    // ─── Cache key helper ────────────────────────────────────────────────────

    private fun cacheKey(screen: ShopeeScreen, element: ShopeeElement) =
        "shopee_ui_${screen.key}_${element.key}"

    // ─── Main discovery entry point ──────────────────────────────────────────

    /**
     * Find a Shopee UI node using 4-layer fallback strategy.
     * Returns the first enabled+clickable node found, or null.
     */
    fun find(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement,
        requireClickable: Boolean = true
    ): AccessibilityNodeInfo? {
        // Layer 1: Cached resource ID
        findByCachedId(root, screen, element, requireClickable)?.let { return it }

        // Layer 2: Text content
        findByText(root, screen, element, requireClickable)?.let { return it }

        // Layer 3: Content description
        findByContentDesc(root, screen, element, requireClickable)?.let { return it }

        // Layer 4: Heuristic
        return findByHeuristic(root, screen, element, requireClickable)
    }

    /**
     * Find multiple nodes (e.g., all voucher list items).
     */
    fun findAll(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()

        // Try cached ID first
        val cachedId = loadCachedId(screen, element)
        if (cachedId != null) {
            val nodes = root.findAccessibilityNodeInfosByViewId(cachedId)
            if (nodes.isNotEmpty()) return nodes
        }

        // Fallback: text-based findAll
        val labels = textLabels[element] ?: return results
        for (text in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                if (nodes.first().viewIdResourceName != null) {
                    cacheId(screen, element, nodes.first().viewIdResourceName!!)
                }
                return nodes
            }
        }
        return results
    }

    // ─── Layer 1: Cached Resource ID ────────────────────────────────────────

    private fun findByCachedId(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement,
        requireClickable: Boolean
    ): AccessibilityNodeInfo? {
        val cachedId = loadCachedId(screen, element) ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(cachedId)
        return nodes.firstOrNull { it.isEnabled && (!requireClickable || it.isClickable) }
    }

    // ─── Layer 2: Text Content ───────────────────────────────────────────────

    private fun findByText(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement,
        requireClickable: Boolean
    ): AccessibilityNodeInfo? {
        val labels = textLabels[element] ?: return null
        for (text in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val node = nodes.firstOrNull { it.isEnabled && (!requireClickable || it.isClickable) }
            if (node != null) {
                // Cache the discovered ID for future use
                node.viewIdResourceName?.let { cacheId(screen, element, it) }
                return node
            }
            // If not clickable itself, try parent (text may be in TextView, button wraps it)
            nodes.firstOrNull { it.isEnabled }?.let { textNode ->
                val clickableParent = findClickableParent(textNode)
                if (clickableParent != null) {
                    clickableParent.viewIdResourceName?.let { cacheId(screen, element, it) }
                    return clickableParent
                }
            }
        }
        return null
    }

    // ─── Layer 3: Content Description ────────────────────────────────────────

    private fun findByContentDesc(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement,
        requireClickable: Boolean
    ): AccessibilityNodeInfo? {
        val keywords = contentDescKeywords[element] ?: return null
        return traverseTree(root) { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: return@traverseTree false
            val matches = keywords.any { desc.contains(it.lowercase()) }
            matches && node.isEnabled && (!requireClickable || node.isClickable)
        }
    }

    // ─── Layer 4: Heuristic ───────────────────────────────────────────────────

    private fun findByHeuristic(
        root: AccessibilityNodeInfo,
        screen: ShopeeScreen,
        element: ShopeeElement,
        requireClickable: Boolean
    ): AccessibilityNodeInfo? {
        return when (element) {
            ShopeeElement.PLACE_ORDER_BUTTON -> findLargeBottomButton(root)
            ShopeeElement.APPLY_VOUCHER_BUTTON -> findDialogConfirmButton(root)
            ShopeeElement.VOUCHER_PICKER_ROW -> findVoucherPickerHeuristic(root)
            else -> null
        }
    }

    /**
     * Heuristic: "Đặt hàng" is typically the widest clickable element pinned to
     * the bottom of the screen.
     *
     * React Native (Shopee checkout = OPC_HOME module) renders buttons as plain
     * `android.view.View` / `TextView`, NOT `android.widget.Button` — so we must
     * accept those classes too, and size by a ratio of the actual screen width
     * (read from the root bounds) rather than a hardcoded pixel threshold.
     */
    private fun findLargeBottomButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val screenWidth = rootBounds.width().takeIf { it > 0 } ?: return null
        val screenHeight = rootBounds.height().takeIf { it > 0 } ?: Int.MAX_VALUE
        val minWidth = (screenWidth * 0.6).toInt()      // wide bar: >60% of screen
        val bottomZoneTop = rootBounds.top + (screenHeight * 0.7).toInt() // lower 30%

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        traverseTree(root) { node ->
            if (node.isEnabled && isClickableLike(node)) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() >= minWidth && bounds.bottom >= bottomZoneTop) {
                    candidates.add(node)
                }
            }
            false
        }
        // Prefer the lowest (highest Y) wide button — the place-order CTA.
        return candidates.maxByOrNull {
            val bounds = android.graphics.Rect()
            it.getBoundsInScreen(bounds)
            bounds.bottom
        }
    }

    /**
     * Heuristic: in a dialog/bottom-sheet, the confirm button is usually the
     * last clickable element. Accepts RN View/TextView, not just Button.
     */
    private fun findDialogConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        traverseTree(root) { node ->
            if (node.isEnabled && isClickableLike(node)) {
                buttons.add(node)
            }
            false
        }
        // In dialogs, confirm button is typically the last/lowest button.
        return buttons.maxByOrNull {
            val bounds = android.graphics.Rect()
            it.getBoundsInScreen(bounds)
            bounds.bottom
        }
    }

    /**
     * A node is "clickable-like" if it (or, for RN, its container) accepts a tap.
     * RN buttons surface as clickable View/TextView nodes — so we just require
     * isClickable and a sane class, instead of `className.contains("Button")`.
     */
    private fun isClickableLike(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        val cls = node.className?.toString() ?: return true
        return cls.contains("Button", ignoreCase = true) ||
            cls.contains("View") ||        // android.view.View (RN), ViewGroup
            cls.contains("TextView") ||
            cls.contains("Layout") ||      // FrameLayout/LinearLayout wrappers
            cls.contains("Compose")        // ComposeView fallback
    }

    /**
     * Heuristic: Voucher picker row contains a discount-related icon/text.
     */
    private fun findVoucherPickerHeuristic(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return traverseTree(root) { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            (text.contains("voucher") || text.contains("giảm") || text.contains("mã") ||
                desc.contains("voucher") || desc.contains("coupon")) &&
                node.isEnabled && node.isClickable
        }
    }

    // ─── Tree traversal ───────────────────────────────────────────────────────

    /**
     * DFS traversal. Returns first node where predicate returns true.
     */
    private fun traverseTree(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = traverseTree(child, predicate)
            if (result != null) return result
        }
        return null
    }

    /**
     * Walk up from node to find nearest clickable ancestor.
     * Used when text is in a non-clickable TextView inside a clickable container.
     */
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent ?: return null
        repeat(5) { // max 5 levels up
            if (current?.isClickable == true && current?.isEnabled == true) return current
            current = current?.parent
        }
        return null
    }

    // ─── Cache (DataStore backed) ─────────────────────────────────────────────

    private val memCache = mutableMapOf<String, String>()

    private fun loadCachedId(screen: ShopeeScreen, element: ShopeeElement): String? {
        val key = cacheKey(screen, element)
        return memCache[key] ?: runBlocking {
            AppDataStore.getString(key)?.also { memCache[key] = it }
        }
    }

    private fun cacheId(screen: ShopeeScreen, element: ShopeeElement, id: String) {
        if (id.isBlank()) return
        val key = cacheKey(screen, element)
        if (memCache[key] == id) return // already cached
        memCache[key] = id
        runBlocking { AppDataStore.setString(key, id) }
    }

    /**
     * Clear cache for a specific element (call when Shopee update detected).
     */
    fun invalidateCache(screen: ShopeeScreen, element: ShopeeElement) {
        val key = cacheKey(screen, element)
        memCache.remove(key)
        runBlocking { AppDataStore.remove(key) }
    }

    /**
     * Clear all cached IDs (nuclear option on Shopee major update).
     */
    fun clearAllCache() {
        memCache.clear()
        // Individual keys cleared on next access
    }
}
