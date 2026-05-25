# ShopeeKit — Core Design Spec
> Created: 2026-05-25  
> Status: APPROVED  
> Scope: VoucherSniper + PriceHistory Android App

---

## 1. Overview

ShopeeKit là Android app cá nhân (không publish) chạy trên Xiaomi 15, Android 16, API 35+.  
Mục tiêu: **giành voucher Shopee với độ chính xác millisecond** và **track lịch sử giá sản phẩm**.

---

## 2. Tech Stack

| Component | Choice | Version |
|-----------|--------|---------|
| Language | Kotlin | 2.0.21 |
| UI | Views + XML (minimal) | — |
| Network | OkHttp (raw, no Retrofit) | 4.12.x |
| Database | Room | 2.7.x |
| Background (sniper) | ForegroundService | — |
| Background (price) | WorkManager | 2.10.x |
| Accessibility | AccessibilityService | Android API |
| Overlay | TYPE_ACCESSIBILITY_OVERLAY | 1px trick (BVC pattern) |
| Gradle | 9.0.0 / AGP 8.7.0 / JVM 17 | (per android-setup-guide) |
| Min SDK | API 26 (Android 8.0) | — |

---

## 3. Module: VoucherSniper

### 3.1 Problem

Voucher Shopee được enable tại thời điểm T trên server.  
User tap thủ công: T + 200-500ms. Quá chậm khi compete với hundreds of users.

### 3.2 Latency Chain (Baseline)

```
Server enables voucher at T
  → UI render cycle: +16ms/frame
  → Accessibility tree update: +16-50ms  
  → Code detect: +10-50ms
  → performAction(click): +5-20ms
  → HTTP request leave device: +5ms
  → Network RTT: +30-100ms
  → Server process: +10-50ms
Total: ~100-300ms AFTER T
```

### 3.3 Solution: Hybrid Architecture

**Layer 1 — AccessibilityService** (safety net + context detection):
- Monitor Shopee app state
- Detect khi đúng product/voucher page
- Fallback click nếu Direct API fail

**Layer 2 — Direct HTTP** (primary sniper):
- Bypass UI render hoàn toàn
- Fire request tại T - RTT_estimate
- Sử dụng real auth headers captured via mitmproxy

**Layer 3 — TimeSync** (precision):
- Calibrate device clock vs Shopee server clock
- Offset thường ±500ms → phải compensate
- Method: compare local `System.currentTimeMillis()` vs `Date:` response header

### 3.4 Token Extraction (One-time Setup)

```
Setup (~30 min, once per token rotation):
1. PC: install mitmproxy, run at port 8080
2. Android: Settings → WiFi → Proxy → PC:8080
3. Android: install mitmproxy CA cert
4. Open Shopee, browse/claim voucher manually
5. mitmproxy captures: Cookie, SPC_CDS, X-Sap-Ri, User-Agent
6. Save captured values into ShopeeKit Config screen
```

Tokens refresh automatically (detect 401 → prompt user to re-capture).

### 3.5 VoucherSniper Flow

```
Input: Voucher URL + Release Datetime (epoch ms)

T-5min: TimeSync.calibrate() → serverOffset (ms)
T-2min: Navigate Shopee to voucher page (deep link / Accessibility)
T-30s:  SniperEngine.arm() → state = ARMED
T-RTT:  DirectApiClient.claimVoucher() [fire primary]
T+50ms: AccessibilityService.clickClaimButton() [fire fallback]

Response handling:
  200 → SUCCESS, show notification
  429 → RATE_LIMITED, retry after 1s (max 3x)  
  401 → TOKEN_EXPIRED, show re-capture prompt
  Other → FAILED, log for debugging
```

### 3.6 Anti-Ban Measures

| Measure | Implementation |
|---------|---------------|
| Real auth tokens | Captured from real Shopee session |
| Real User-Agent | Copied from mitmproxy capture |
| Real device IDs | Use `Settings.Secure.ANDROID_ID` |
| Request jitter | +Random(0..15ms) to fire time |
| Rate limiting | Max 3 attempts per voucher |
| Legitimate context | AccessibilityService is open inside Shopee |

### 3.7 State Machine

```kotlin
sealed class SniperState {
    object Idle : SniperState()
    data class Scheduled(val releaseTime: Long, val voucherUrl: String) : SniperState()
    object Armed : SniperState()    // T-30s
    object Fired : SniperState()    // Request sent
    data class Success(val claimedAt: Long) : SniperState()
    data class Failed(val reason: String, val retryCount: Int) : SniperState()
}
```

---

## 4. Module: PriceHistory

### 4.1 Overview

Track giá sản phẩm theo thời gian.  
Gồm: background polling + Room DB + notification khi giá giảm ngưỡng.

### 4.2 Flow

```
User adds product URL → extract product ID
WorkManager schedules PricePoller (interval: 4h default)
PricePoller: GET Shopee product API → extract current price
Compare with last price → if drop > threshold → Notification
Store in Room: (productId, price, timestamp)
UI: show chart (custom LineChart View)
```

### 4.3 Data Model

```kotlin
@Entity(tableName = "price_history")
data class PriceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    val productName: String,
    val price: Long,         // in VND cents to avoid float
    val originalPrice: Long,
    val timestamp: Long      // epoch ms
)
```

---

## 5. Project Structure

```
app/src/main/java/com/personal/shopeekit/
├── service/
│   ├── ShopeeKitForegroundService.kt   ← Sniper countdown
│   └── ShopeeAccessibilityService.kt   ← UI monitoring
│
├── sniper/
│   ├── VoucherSniper.kt
│   ├── TimeSync.kt
│   ├── DirectApiClient.kt              ← OkHttp raw
│   └── SniperState.kt
│
├── price/
│   ├── PricePoller.kt                  ← WorkManager Worker
│   ├── PriceDatabase.kt
│   ├── PriceDao.kt
│   └── PriceAlert.kt
│
├── config/
│   └── ShopeeConfig.kt                 ← DataStore: tokens, headers
│
└── ui/
    ├── MainActivity.kt
    ├── SniperSetupActivity.kt
    ├── PriceHistoryActivity.kt
    └── overlay/
        └── SniperOverlayView.kt        ← Countdown floating window
```

---

## 6. Permissions Required

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
```

---

## 7. Implementation Phases

### Phase 1 — Foundation (Day 1-2)
- [ ] Create Android project (per android-setup-guide.md)
- [ ] Setup dependencies (OkHttp, Room, WorkManager, DataStore)
- [ ] ShopeeAccessibilityService basic skeleton
- [ ] ShopeeKitForegroundService skeleton
- [ ] MainActivity với 2 tabs: Sniper / Price History

### Phase 2 — VoucherSniper Core (Day 3-5)
- [ ] TimeSync implementation
- [ ] DirectApiClient với configurable headers
- [ ] SniperEngine state machine
- [ ] SniperOverlayView countdown
- [ ] Manual test với real Shopee voucher

### Phase 3 — PriceHistory (Day 6-7)
- [ ] Room DB setup (PriceRecord, PriceDao)
- [ ] PricePoller WorkManager job
- [ ] PriceAlert notification
- [ ] PriceHistoryActivity + LineChart

### Phase 4 — Polish (Day 8)
- [ ] Config screen (token input)
- [ ] Error handling + retry logic
- [ ] Logging (adb logcat friendly)
- [ ] Build release APK

---

## 8. Known Risks

| Risk | Mitigation |
|------|-----------|
| Shopee API changes endpoints | Config-driven, easy update |
| Token expires mid-snipe | Pre-flight token validation at T-5min |
| AccessibilityService disabled on reinstall | ADB script in readme |
| Android kills ForegroundService | FOREGROUND_SERVICE_SPECIAL_USE + startForeground() |
| Shopee detects bot → ban | Anti-ban measures in section 3.6 |
