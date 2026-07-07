package com.personal.shopeekit.service

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pure traversal helpers over the accessibility tree, shared by
 * [ShopeeAccessibilityService] and [ShopeeUIDiscovery] (both used to carry their
 * own near-identical copies of these). No Android UI state, no service instance —
 * just node walking — so the logic lives in one place.
 */
object A11yTreeUtils {

    /** Concatenate every node's text + contentDescription in the subtree (DFS). */
    fun allText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }
            }
        }
        walk(node)
        return sb.toString()
    }

    /** First node in the subtree whose resource-id short name equals [suffix], or null. */
    fun findNodeByIdSuffix(node: AccessibilityNodeInfo, suffix: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.substringAfterLast('/') == suffix) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByIdSuffix(child, suffix)?.let { return it }
        }
        return null
    }

    /** True if any node in the subtree carries a resource-id ending in [suffix]. */
    fun hasNodeWithIdSuffix(node: AccessibilityNodeInfo, suffix: String): Boolean =
        findNodeByIdSuffix(node, suffix) != null

    /** True if any node in the subtree carries a resource-id whose short name starts with [prefix]. */
    fun hasNodeWithIdPrefix(node: AccessibilityNodeInfo, prefix: String): Boolean {
        if (node.viewIdResourceName?.substringAfterLast('/')?.startsWith(prefix) == true) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasNodeWithIdPrefix(child, prefix)) return true
        }
        return false
    }

    /** First scrollable node in the subtree (DFS), or null. */
    fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }
}
