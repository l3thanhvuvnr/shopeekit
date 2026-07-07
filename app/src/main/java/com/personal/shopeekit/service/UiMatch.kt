package com.personal.shopeekit.service

import java.text.Normalizer

/**
 * Pure text / geometry matching helpers for [ShopeeUIDiscovery].
 *
 * No Android dependencies → fully unit-testable on the JVM.
 *
 * Why this exists: the old discovery path used
 * `AccessibilityNodeInfo.findAccessibilityNodeInfosByText`, whose match is
 * *case-insensitive substring containment*. That made short/generic labels
 * ("OK", "Voucher", "Áp dụng") match dozens of unrelated nodes, and the code
 * then blindly took the first DFS hit — so it frequently tapped the wrong
 * button. Here we score every candidate and require a real match, so a label
 * like "OK" only wins when the node's whole text actually *is* "OK".
 */
object UiMatch {

    /** Text-layer acceptance floor: below this a candidate is not a match. */
    const val TEXT_ACCEPT_THRESHOLD = 0.70

    /**
     * Lowercase, strip Vietnamese diacritics, collapse whitespace.
     * "Đặt Hàng" → "dat hang", "Áp dụng" → "ap dung".
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // lowercase first so 'Đ' → 'đ', then map đ→d (NFD does NOT decompose đ)
        var s = raw.trim().lowercase().replace('đ', 'd')
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")     // drop combining accent marks
        return s.replace("\\s+".toRegex(), " ").trim()
    }

    /**
     * Score how well [candidate]'s text matches [label], in `[0,1]`.
     * Diacritic- and case-insensitive.
     *
     *  - exact (whole string equals label)      → 1.0
     *  - label appears as a whole word          → 0.5 + 0.45·coverage
     *  - candidate starts with label            → 0.5
     *  - label is an interior substring         → 0.3
     *  - otherwise                              → 0.0
     *
     * `coverage = label.length / candidate.length` — a button whose text *is*
     * the label scores near 1; a paragraph merely containing the label scores
     * low, so long descriptive strings can't masquerade as buttons.
     */
    fun textScore(candidate: String?, label: String): Double {
        val c = normalize(candidate)
        val l = normalize(label)
        if (c.isEmpty() || l.isEmpty()) return 0.0
        if (c == l) return 1.0

        val wholeWord = Regex("(^|\\W)" + Regex.escape(l) + "($|\\W)")
        if (wholeWord.containsMatchIn(c)) {
            val coverage = l.length.toDouble() / c.length
            return (0.5 + 0.45 * coverage).coerceIn(0.5, 0.99)
        }
        if (c.startsWith(l)) return 0.5
        if (c.contains(l)) return 0.3
        return 0.0
    }

    /** Best [textScore] of [candidate] over a set of [labels]. */
    fun bestTextScore(candidate: String?, labels: List<String>): Double =
        labels.maxOfOrNull { textScore(candidate, it) } ?: 0.0

    /**
     * True if [candidate] *begins* with [phrase] as a whole phrase (followed by a
     * word boundary or end), diacritic-insensitive.
     *
     * This is the reliable signal for a **row label**: Shopee renders a settings
     * row as one accessible node that concatenates the label with its value cell
     * — "Shopee Voucher" + "Miễn Phí Vận Chuyển" → "Shopee Voucher Miễn Phí Vận
     * Chuyển". The label is always the prefix, so `startsWithPhrase` matches the
     * row but NOT a sentence that merely mentions the label later (e.g. the
     * "…ở mục Shopee Voucher" free-shipping hint).
     */
    fun startsWithPhrase(candidate: String?, phrase: String): Boolean {
        val c = normalize(candidate)
        val p = normalize(phrase)
        if (c.isEmpty() || p.isEmpty()) return false
        if (c == p) return true
        return c.startsWith("$p ")
    }

    /** True if any of [phrases] is a whole-phrase prefix of [candidate]. */
    fun startsWithAny(candidate: String?, phrases: List<String>): Boolean =
        phrases.any { startsWithPhrase(candidate, it) }

    /**
     * True if [candidate] contains any of [negatives] (diacritic-insensitive
     * substring). Used to veto known false-positive text, e.g. exclude
     * "đặt hàng thành công" when hunting the "Đặt hàng" button.
     */
    fun hasNegative(candidate: String?, negatives: List<String>): Boolean {
        if (negatives.isEmpty()) return false
        val c = normalize(candidate)
        if (c.isEmpty()) return false
        return negatives.any { neg -> normalize(neg).let { it.isNotEmpty() && c.contains(it) } }
    }

    /**
     * Proximity of a live node to a stored signature, in `[0,1]`.
     * All inputs are ratios of screen size (0..1), so this is resolution-
     * independent. 1.0 = same centre & same size.
     *
     * 70% weight on centre distance, 30% on size similarity — position is the
     * stronger signal because Shopee keeps the place-order bar pinned to the
     * same spot even across minor layout changes.
     */
    fun positionScore(
        cx: Float, cy: Float, w: Float, h: Float,
        sx: Float, sy: Float, sw: Float, sh: Float
    ): Double {
        val dist = Math.hypot((cx - sx).toDouble(), (cy - sy).toDouble()) // 0 .. ~1.414
        val posScore = (1.0 - dist / 1.414).coerceIn(0.0, 1.0)
        val sizeDiff = Math.abs(w - sw) + Math.abs(h - sh)               // 0 .. ~2
        val sizeScore = (1.0 - sizeDiff).coerceIn(0.0, 1.0)
        return 0.70 * posScore + 0.30 * sizeScore
    }

    /**
     * Plausibility that a node's bounds look like a tappable *button* rather
     * than a full-screen container or a hairline, in `[0,1]`.
     * Buttons are wide-ish and short: penalise very tall nodes (containers)
     * and near-zero-height nodes.
     */
    fun buttonShapeScore(wRatio: Float, hRatio: Float): Double {
        if (wRatio <= 0f || hRatio <= 0f) return 0.0
        // ideal button height ~2–12% of screen; taller = probably a container
        val heightScore = when {
            hRatio > 0.30f -> 0.0          // almost certainly a container
            hRatio > 0.18f -> 0.3
            hRatio in 0.02f..0.15f -> 1.0  // typical button/bar
            else -> 0.6                    // very thin, still possible
        }
        val widthScore = if (wRatio >= 0.30f) 1.0 else wRatio / 0.30
        return 0.6 * heightScore + 0.4 * widthScore
    }
}
