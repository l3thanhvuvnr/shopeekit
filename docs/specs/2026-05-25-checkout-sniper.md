# CheckoutSniper — Feature Spec
**Date**: 2026-05-25
**Project**: ShopeeKit (Android, Kotlin, API 35+, Xiaomi 15)
**Status**: Approved → Implementation

---

## 1. Problem Statement

Shopee flash vouchers drop at a precise millisecond (e.g., 12:00:00.000). Winning requires:
1. Checkout screen pre-filled (address, payment, shipping)
2. Best available voucher applied **at exactly T**
3. "Đặt hàng" pressed with ≤2ms latency from T

Human reaction time (~200ms) cannot compete. ShopeeKit automates steps 2–3 via AccessibilityService.

**Key insights**:
- Shopee pushes vouchers to each account's wallet automatically at T — no pre-claim needed
- Vouchers may appear delayed (e.g., T+60s) — must retry with **fresh scan** each attempt
- Cannot know in advance which voucher will appear → re-evaluate every retry
- Retry is safe because rejected orders are never created (idempotent until confirmed)
- Must check for duplicate order before each retry (network lag edge case)

---

## 2. Scope

### In Scope — v1
- **CheckoutSniper**: auto-apply best wallet voucher + place order at T
- **ShopeeUIDiscovery**: multi-strategy UI element detection (no hardcoded IDs)
- **Self-learning cache**: discover & remember Shopee's actual resource IDs
- **Aggressive retry loop**: re-evaluate vouchers every 50ms until success or timeout
- **Idempotency check**: detect duplicate order before retry
- **Per-product selection**: user pre-configures which items are in checkout
- **Configurable retry timeout**: default 120s, range 30s–600s

### Out of Scope — v1
- Cart manipulation (user pre-navigates to checkout)
- Multiple concurrent snipe sessions
- Automatic shipping/address selection
- VoucherClaimSniper changes (keep as-is)

---

## 3. User Flow A→Z

```
[PREPARATION — one-time setup]
① Install ShopeeKit → Enable Accessibility Service
   Settings → Accessibility → ShopeeKit → ON
② Grant Overlay permission
   Settings → Apps → ShopeeKit → Display over other apps → ON
③ Disable MIUI battery optimization for ShopeeKit

[BEFORE T — user does this]
① Open Shopee → Cart → tick desired products → "Mua hàng"
② On Checkout screen: fill address, shipping, payment
   ⚠️ Do NOT press "Đặt hàng" — stay on this screen
③ Open ShopeeKit (runs alongside Shopee) → "Checkout Sniper" tab
④ Configure:
   - Release time: HH:mm:ss (e.g. 12:00:00)
   - Voucher priority: Auto-best / Max discount / Max cashback
   - Retry timeout: 120s (adjustable slider 30s–600s)
⑤ Tap "🎯 Sẵn sàng" → overlay countdown appears on screen
⑥ Switch back to Shopee — keep checkout screen open

[AT T — ShopeeKit does everything]
T-50ms  → nanoTime spin-wait begins
T+0ms   → Open voucher picker dialog
         → Select best voucher (via UIDiscovery)
         → Tap "Áp dụng"
         → Tap "Đặt hàng"
         → Parse result

[RETRY LOOP — if voucher not yet available]
Each 50ms retry:
  1. Check: has order already been created? (idempotency) → if yes, STOP SUCCESS
  2. Re-open voucher picker → fresh scan voucher list
  3. Pick best voucher currently available
  4. Tap "Áp dụng" → Tap "Đặt hàng"
  5. Parse result → SUCCESS / RETRY / FAIL

[RESULT]
✅ SUCCESS: Toast "Đặt hàng thành công! Tiết kiệm Xđ | Latency: +Yms"
❌ OUT OF STOCK: Toast "Hết voucher. Đặt hàng không voucher?" [Yes/No]
❌ TIMEOUT: Toast "Hết thời gian retry (2 phút)"
❌ DUPLICATE DETECTED: Toast "Order đã tạo thành công (detected via order check)"
```

---

## 4. ShopeeUIDiscovery — Architecture

The core innovation solving the "hardcoded ID" problem.

### 4-Layer Fallback Strategy

```
Layer 1 — Cached Resource ID (fastest, learned from previous sessions)
  → DataStore.get("shopee_ui_${screen}_${element}")
  → If found AND node exists → USE IT, done
  → If not found or node missing → Layer 2

Layer 2 — Text Content Match (language-aware)
  → findAccessibilityNodeInfosByText(text)
  → Vietnamese: "Đặt hàng", "Áp dụng", "Lấy voucher", "Chọn voucher"
  → English: "Place Order", "Apply", "Claim", "Select Voucher"
  → If found → cache ID → USE IT
  → If not found → Layer 3

Layer 3 — Content Description Match
  → Traverse tree, check contentDescription contains keyword
  → Keywords: "order", "voucher", "apply", "claim", "checkout"
  → If found → cache ID → USE IT
  → If not found → Layer 4

Layer 4 — Heuristic (position + size + role)
  → "Place Order" button: large button (width > 60% screen), at bottom, clickable
  → "Apply" button: button inside dialog, confirm position
  → "Voucher picker": clickable row with discount icon
  → If found → cache ID → USE IT
  → If not found → return null (handle gracefully)
```

### Self-Healing on Shopee Update

```
Shopee releases new version → resource IDs change
→ Layer 1 cache miss → fall through to Layer 2 (text)
→ Text "Đặt hàng" always exists regardless of ID
→ Found → overwrite cache with new ID
→ Next call uses new cached ID automatically
→ Zero user intervention required
```

### Screens Discovered

```kotlin
enum class ShopeeScreen {
    VOUCHER_CLAIM,    // free voucher claim page
    CHECKOUT,         // order checkout page
    VOUCHER_PICKER,   // voucher selection dialog/sheet
    VOUCHER_LIST,     // list of available vouchers
    ORDER_LIST        // my orders page (for idempotency check)
}

enum class ShopeeElement {
    CLAIM_BUTTON,         // "Lấy voucher"
    PLACE_ORDER_BUTTON,   // "Đặt hàng"
    VOUCHER_PICKER_ROW,   // row to open voucher picker
    AUTO_SELECT_VOUCHER,  // "Tự động chọn tốt nhất" button
    APPLY_VOUCHER_BUTTON, // "Áp dụng" in picker
    VOUCHER_LIST_ITEM,    // individual voucher in list
    VOUCHER_DISCOUNT_TEXT,// discount amount/percent text
    ORDER_SUCCESS_TEXT    // success confirmation text
}
```

---

## 5. CheckoutSniperEngine — Architecture

```kotlin
class CheckoutSniperEngine(context: Context) {

    // Config
    data class CheckoutConfig(
        val releaseTimeMs: Long,
        val voucherPreference: VoucherPreference = VoucherPreference.AutoBest,
        val retryTimeoutMs: Long = 120_000L,      // 2 min default
        val selectedProductIds: List<String> = emptyList()
    )

    sealed class VoucherPreference {
        object AutoBest : VoucherPreference()      // click Shopee's "tự chọn" button
        object MaxDiscount : VoucherPreference()   // max absolute discount amount
        object MaxCashback : VoucherPreference()   // max cashback %
        data class ManualCode(val code: String) : VoucherPreference()
    }

    sealed class CheckoutSniperState {
        object Idle : CheckoutSniperState()
        data class Armed(val config: CheckoutConfig, val fireAtMs: Long, val rttMs: Long)
        data class Firing(val attemptCount: Int, val lastAttemptMs: Long)
        object ApplyingVoucher : CheckoutSniperState()
        object PlacingOrder : CheckoutSniperState()
        data class RetryLoop(val attemptCount: Int, val nextRetryMs: Long, val lastError: String)
        data class Success(val latencyMs: Long, val appliedVoucher: String?, val savedAmount: Long)
        data class OutOfStock(val timestamp: Long)
        data class Failed(val reason: String, val attemptCount: Int)
    }
}
```

### Retry Loop Logic

```kotlin
// Pseudo-code
suspend fun fireLoop(config: CheckoutConfig) {
    val deadline = System.currentTimeMillis() + config.retryTimeoutMs
    var attempt = 0

    while (System.currentTimeMillis() < deadline) {
        attempt++

        // IDEMPOTENCY CHECK — before every retry
        if (ShopeeAccessibilityService.hasRecentOrder()) {
            _state.value = Success(latency, detected = true)
            return
        }

        _state.value = Firing(attempt, System.currentTimeMillis())

        // Apply voucher (fresh scan each time)
        val voucher = applyBestVoucher(config.voucherPreference)

        // Place order
        val result = ShopeeAccessibilityService.clickPlaceOrder()

        when {
            result.isSuccess -> { _state.value = Success(...); return }
            result.isVoucherNotYet -> { delay(50); continue }  // retry with fresh voucher scan
            result.isOutOfStock -> { _state.value = OutOfStock(...); return }
            result.isPaymentError -> { _state.value = Failed(...); return }
        }
    }
    _state.value = Failed("Timeout after ${config.retryTimeoutMs}ms", attempt)
}
```

---

## 6. Voucher Best-Pick Algorithm

```
Priority 1: If VoucherPreference.AutoBest
  → Find and click Shopee's own "Tự động chọn" / "Auto select" button
  → Shopee's algorithm knows combo platform+shop voucher optimal value
  → Most reliable — delegates scoring to Shopee itself

Priority 2: If MaxDiscount (or AutoBest button not found)
  → Scan voucher list nodes
  → Extract discount amount text from each item
  → Parse: "Giảm 200.000đ" → 200000, "Giảm 25%" → calculate from order total
  → Select node with highest absolute discount amount
  → Tap it

Priority 3: If MaxCashback
  → Same scan, extract cashback % values
  → Select highest cashback

Priority 4: If ManualCode
  → Type code into voucher code input field
  → Tap "Áp dụng"

Fallback: If list is empty or unparseable
  → Select first available voucher (any is better than none)
```

---

## 7. UI Layout — CheckoutSniperSetupActivity

```
┌─────────────────────────────────────────┐
│  🎯 Checkout Sniper                      │
├─────────────────────────────────────────┤
│  Giờ mở voucher                          │
│  ┌──────┐ : ┌──────┐ : ┌──────┐         │
│  │  12  │   │  00  │   │  00  │         │
│  └──────┘   └──────┘   └──────┘         │
│                                          │
│  Ưu tiên voucher                         │
│  ● Tự động chọn tốt nhất ⭐              │
│  ○ Giảm nhiều nhất (tuyệt đối)           │
│  ○ Cashback cao nhất                     │
│  ○ Nhập mã: [_______________]            │
│                                          │
│  Retry timeout                           │
│  30s ──────●────────────── 10 phút       │
│            2 phút (mặc định)             │
│                                          │
│  Sản phẩm (v1: đã chọn trong Shopee)    │
│  ℹ️ Vào Shopee → Giỏ hàng → tick SP     │
│     → Mua hàng → giữ màn hình checkout  │
│                                          │
│  Trạng thái AccessibilityService         │
│  ✅ Đã kết nối  /  ❌ Chưa bật           │
│                                          │
│  [       🎯 Sẵn sàng bắn       ]        │
│  (disabled nếu AccessibilityService off) │
└─────────────────────────────────────────┘
```

---

## 8. Integration Points

| Component | Change |
|-----------|--------|
| `ShopeeUIDiscovery` | **NEW** — replaces all hardcoded IDs |
| `ShopeeAccessibilityService` | Extend: 6 new checkout methods |
| `CheckoutSniperEngine` | **NEW** |
| `CheckoutSniperFeature` | **NEW** — KitFeature impl |
| `CheckoutSniperSetupActivity` | **NEW** |
| `ShopeeKitApp` | Register CheckoutSniperFeature |
| `MainActivity` | Add "Checkout Sniper" tab |
| `AppDataStore` | Add UIDiscovery cache keys |

---

## 9. Anti-Ban

- All interactions via `AccessibilityService.performAction()` — Shopee generates X-Sap-Ri
- Fire jitter: +Random(0..15ms)
- Human-like delay between voucher apply and place order: 30–80ms random
- Retry interval: 50ms (not instant spam)
- No direct HTTP calls to Shopee checkout API

---

## 10. Acceptance Criteria

- [ ] UIDiscovery finds "Đặt hàng" button without hardcoded ID
- [ ] UIDiscovery caches discovered ID and uses it next run
- [ ] UIDiscovery self-heals: if cached ID fails, falls back to text search
- [ ] CheckoutSniperEngine arms, fires, retries every 50ms
- [ ] Retry re-scans voucher list fresh each time
- [ ] Idempotency check prevents double-order
- [ ] "Tự động chọn" Shopee button used when available
- [ ] Retry timeout configurable 30s–600s, default 120s
- [ ] Success toast shows latency + saved amount
- [ ] OutOfStock offers "place order without voucher" option
- [ ] AccessibilityService status shown in setup screen
- [ ] Unit tests: retry logic, UIDiscovery fallback chain, voucher scoring

---

## 11. Clarifications

- Voucher flow: Shopee pushes to wallet automatically → ShopeeKit selects best from list
- Retry: re-evaluate voucher list every 50ms → catches delayed vouchers naturally
- "Best voucher" at T vs T+1min: re-scan each retry = always pick best available NOW
- Double-order prevention: idempotency check via order list before each retry
- Per-product v1: user pre-selects in Shopee cart; ShopeeKit does not touch cart
- Default retry timeout: 120s, slider range 30s–600s

---

## 12. APK reverse-engineering findings (Shopee VN 3.76.25) — 2026-06-25

Decompiled the shipped APK (`com.shopee.vn` 3.76.25, jadx) to validate the
automation assumptions. Outcome: the original resource-ID-first strategy does
**not** work on this version; the implementation was revised accordingly.

| Finding | Evidence | Action taken |
|---|---|---|
| Checkout is **React Native** (module `OPC_HOME`), hosted by `ReactActivity_`/`w1`. There is **no native CheckoutActivity** and **no native resource IDs** for the buttons. | `com/shopee/app/react/util/f.java:7` (`^OPC_HOME$`); `w1.java:316` `startReactApplication` | UIDiscovery Layer-1 (cached resource ID) is now a best-effort optimisation only; **text + content-description matching is the primary path**. Heuristics accept `View`/`TextView` (RN), not only `Button`. |
| Real VN labels differ from the original guesses. Claim button is **"Lưu"** (not "Lấy voucher"); apply is **"Áp dụng"** ✓; also **"Dùng ngay"**, **"Chọn Voucher khác"**. | `dre-extract/.../i18n/live/vi/*.json` (`label_apply`, `label_use`, `voucher_wallet_popup_cancel`) | `textLabels` in `ShopeeUIDiscovery` rewritten to the verified 3.76.25 strings. (Claim removed entirely — VoucherSniper feature dropped.) |
| `OPC_HOME` sets **FLAG_SECURE**. | `w1.java:495` `setFlags(8192, 8192)` | Screenshots of the checkout screen are black (cannot QA by screenshot). **AccessibilityService still works** over FLAG_SECURE, and the `TYPE_APPLICATION_OVERLAY` countdown still renders. |
| Anti-fraud **SHPSSDK reports the set of enabled accessibility services** to Shopee's backend risk engine. It does **not** block locally. | `com/shopee/shpssdk/.../wvvuwvwuv.java:74,109`; `SPSExtType.TYPE_ACC_ENABLE=100` | Added `HumanBehavior`: right-skewed (log-normal) delays instead of fixed 50 ms, randomised fire-lead jitter, off-centre variable-duration taps, and a pre-fire warm-up (harmless scroll) so the session isn't dead-still then tapping at the millisecond. **Recommendation (not yet applied):** ship with an `applicationId` that does not contain "shopee"/"bot"/"auto"; changing it requires re-doing signing, so it's left to a deliberate release step. |

### Honest limitation
Because checkout is React Native + FLAG_SECURE and the backend risk engine is
server-side, **no client can guarantee a successful, undetected snipe**. These
changes make automation *materially more robust* than the previous build (which
relied on resource IDs that do not exist in 3.76.25) and *harder to fingerprint*,
but real-device testing against a live drop is still required, and Shopee can
break it at any time by changing labels or tightening risk rules.

### Removed in this revision
- **VoucherSniper** (auto-click the claim button) deleted entirely:
  `features/sniper/{SniperEngine,SniperState,VoucherSniperFeature}.kt`,
  `ui/SniperSetupActivity.kt`, claim methods in `ShopeeAccessibilityService`.
- `SpeculativeScheduler` kept and moved to `core/time/` (still used by CheckoutSniper).
- The floating countdown overlay was repurposed to `CheckoutOverlayView` (reads `CheckoutSniperState`).
