# ShopeeKit — Core Design Spec (v2)
> Created: 2026-05-25 | Updated: 2026-05-25  
> Status: APPROVED  
> Scope: VoucherSniper + PriceHistory + Extensible Kit Architecture

---

## 1. Overview

ShopeeKit là Android app cá nhân (không publish) chạy trên Xiaomi 15, Android 16, API 35+.  
**Goals**:
1. **VoucherSniper**: Giành voucher Shopee với speculative fire + spin-wait precision
2. **PriceHistory**: Track giá + alert khi giá drop + combine với voucher để tính "best price"
3. **Kit Architecture**: Extensible foundation cho nhiều chức năng sau này

---

## 2. Tech Stack

| Component | Choice | Notes |
|-----------|--------|-------|
| Language | Kotlin 2.0.21 | Coroutines, concise |
| UI | Views + XML | Minimal; app chủ yếu background |
| Network | OkHttp 4.12.x (raw) | Price API. No Retrofit — fine control |
| Database | Room 2.7.x | Price history |
| Background (sniper) | ForegroundService | Countdown không bị kill |
| Background (price) | WorkManager 2.10.x | Periodic polling |
| Accessibility | AccessibilityService | Primary click trigger |
| Overlay | TYPE_ACCESSIBILITY_OVERLAY | Countdown floating UI |
| Timing | System.nanoTime() + spin-wait | ±2ms precision |
| Storage | DataStore | Per-feature namespaced |
| Min SDK | API 26 (Android 8.0) | Wide device support |
| Gradle | 9.0.0 / AGP 8.7.0 / JVM 17 | Per android-setup-guide |

---

## 3. Key Technical Insights

### 3.1 X-Sap-Ri — Why Direct HTTP Won't Work for Voucher Claim

Shopee's voucher claim endpoint requires `X-Sap-Ri` header:
- Generated inside `libmtguard.so` (native, obfuscated)
- Inputs: timestamp + URL + body hash + device fingerprint
- **Valid for ~seconds only** — captured headers cannot be replayed

**Consequence**: AccessibilityService click IS the primary strategy.  
The Shopee app internally generates valid X-Sap-Ri when processing the click.

**Direct OkHttp used for**: Price API endpoints (do NOT require X-Sap-Ri).

### 3.2 Speculative Fire > Reactive Fire

```
❌ Reactive (naive): Detect UI button at T → click → request leaves device at T+100-300ms
✅ Speculative (correct): Fire click at (T - RTT_estimate - 50ms) 
   → request arrives at Shopee server ≈ T
   → sits in server queue, processed at T
```

**RTT estimation**: Average of last 5 pings to `shopee.vn`. Re-calibrate before each snipe.

### 3.3 Timing Precision: Spin-Wait

```kotlin
// ForegroundService, dedicated thread
Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

val targetNano = System.nanoTime() + delayMs * 1_000_000L

// Coarse sleep until T-50ms (save battery)
while (System.currentTimeMillis() < targetMs - 50) Thread.sleep(10)

// Spin-wait last 50ms (±2ms precision, burns CPU briefly)
while (System.nanoTime() < targetNano) { /* spin */ }

triggerAccessibilityClick()
```

Handler.postAtTime() on main thread: ±16-200ms — NOT usable.  
Dedicated HandlerThread: ±5-20ms — not good enough.  
Spin-wait with URGENT_AUDIO priority: **±2ms achievable** on real device.

### 3.4 Server Clock Sync

```kotlin
// Compare local time vs Shopee server's "Date:" response header
// Run 5 times, take median to reduce noise
suspend fun calibrateServerOffset(): Long {
    return (1..5).map {
        val before = System.currentTimeMillis()
        val response = okHttpClient.newCall(pingRequest).execute()
        val after = System.currentTimeMillis()
        val serverTime = response.header("Date")!!.parseHttpDate()
        serverTime - (before + after) / 2
    }.sorted()[2] // median
}
```

---

## 4. Module: VoucherSniper

### 4.1 Flow

```
User Input: Voucher URL + Release DateTime (epoch ms)
                          ↓
[T-10min] TimeSync.calibrate() → serverOffset (ms)
[T-10min] RTT.measure() → estimatedRtt (ms)  
[T-5min]  Navigate Shopee to voucher page (deep link or Accessibility swipe)
[T-30s]   SniperEngine.arm() → state = ARMED, verify button is visible
[T-Xms]   X = estimatedRtt + 50ms (buffer)
          SpeculativeScheduler fires spin-wait thread
[spin]    AccessibilityService.performClick(claimButton)
[+50ms]   Retry if no success response detected (max 3x)
                          ↓
ResultMonitor → check Shopee's response overlay/snackbar for success/fail
```

### 4.2 State Machine

```kotlin
sealed class SniperState {
    object Idle : SniperState()
    data class Scheduled(val releaseTime: Long, val voucherUrl: String) : SniperState()
    object Armed : SniperState()        // T-30s: button verified visible
    object Firing : SniperState()       // In spin-wait phase
    data class Success(val claimedAt: Long, val latency: Long) : SniperState()
    data class Failed(val reason: String, val retryCount: Int) : SniperState()
    object TokenExpired : SniperState() // Needs re-setup
}
```

### 4.3 Anti-Ban

| Measure | Implementation |
|---------|---------------|
| Legitimate context | AccessibilityService is inside real Shopee app |
| Jitter | +Random(0..15ms) added to fire time |
| Rate limit | Max 3 attempts per voucher event |
| Real User-Agent | Accessibility uses Shopee's own network stack |
| Device IDs | App uses real `ANDROID_ID` of device |

---

## 5. Module: PriceHistory + Best Price

### 5.1 Overview

- Poll product price every 4h (WorkManager)
- Lưu Room DB: `(productId, price, originalPrice, timestamp)`
- Hiển thị chart lịch sử giá
- **Best Price Calculator**: `effective_price = price - best_voucher_discount`
- Alert khi `effective_price` thấp hơn ngưỡng user đặt

### 5.2 Data Model

```kotlin
@Entity(tableName = "price_history")
data class PriceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    val productName: String,
    val shopId: Long,
    val price: Long,           // cents VND
    val originalPrice: Long,   // before discount
    val timestamp: Long        // epoch ms
)

@Entity(tableName = "tracked_products")
data class TrackedProduct(
    @PrimaryKey val productId: String,
    val productName: String,
    val productUrl: String,
    val alertThresholdVnd: Long,  // 0 = no alert
    val pollIntervalHours: Int = 4,
    val addedAt: Long
)
```

### 5.3 Price API

Shopee's product API (no X-Sap-Ri needed for basic price fetch):
```
GET https://shopee.vn/api/v4/pdp/get_pc?item_id={id}&shop_id={shopId}
Headers: standard (no special auth for public price data)
```

Extract from URL: `shopee.vn/{shop-name}.{shopId}.{productId}`

---

## 6. Kit Extensibility Architecture

### 6.1 KitFeature Interface

```kotlin
interface KitFeature {
    val featureId: String
    val displayName: String
    val iconRes: Int
    fun initialize(context: Context)
    fun release()
    fun createMainView(context: Context): View
    fun createSettingsView(context: Context): View? = null
}
```

### 6.2 Feature Registration

```kotlin
// In Application class:
class ShopeeKitApp : Application() {
    val features: List<KitFeature> by lazy {
        listOf(
            VoucherSniperFeature(),
            PriceHistoryFeature(),
            // Future: DailyCoinsFeature(), FlashSaleFeature(), etc.
        )
    }
}
```

### 6.3 Future Features Roadmap

| Feature | Complexity | Priority |
|---------|-----------|----------|
| Shopee Coins daily auto-claim | Low | Next |
| Price drop → best voucher combo alert | Medium | Next |
| Flash sale add-to-cart bot | Medium | Later |
| Multi-platform price compare (Lazada/Tiki) | High | Later |
| Order tracking overlay | Low | Later |

---

## 7. Project Structure

```
ShopeeKit/
├── app/src/main/java/com/personal/shopeekit/
│   ├── core/
│   │   ├── KitFeature.kt               ← Feature interface
│   │   ├── network/
│   │   │   └── ShopeeHttpClient.kt     ← OkHttp singleton
│   │   └── time/
│   │       └── TimeSync.kt             ← Server offset calibration
│   │
│   ├── features/
│   │   ├── sniper/
│   │   │   ├── VoucherSniperFeature.kt
│   │   │   ├── SniperEngine.kt         ← Orchestrator
│   │   │   ├── SpeculativeScheduler.kt ← Spin-wait timer
│   │   │   ├── SniperState.kt          ← Sealed class
│   │   │   └── RttMeasurer.kt
│   │   │
│   │   └── price/
│   │       ├── PriceHistoryFeature.kt
│   │       ├── PricePoller.kt          ← WorkManager Worker
│   │       ├── PriceDatabase.kt        ← Room
│   │       ├── PriceDao.kt
│   │       └── BestPriceCalculator.kt
│   │
│   ├── service/
│   │   ├── ShopeeKitForegroundService.kt
│   │   └── ShopeeAccessibilityService.kt
│   │
│   └── ui/
│       ├── MainActivity.kt
│       ├── overlay/
│       │   └── SniperOverlayView.kt    ← Countdown floating window
│       └── setup/
│           └── TokenSetupActivity.kt   ← mitmproxy config input
│
└── docs/specs/
    └── 2026-05-25-shopeekit-core.md    ← This file
```

---

## 8. Permissions

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
```

---

## 9. Setup Guide (One-time)

### mitmproxy + SSL Bypass (for Price API capture)

```bash
# PC
npm install -g apk-mitm
apk-mitm shopee.apk          # Auto-patch APK + disable SSL pinning
adb install shopee-patched.apk

# Run mitmproxy
pip install mitmproxy
mitmproxy --listen-port 8080

# Android: WiFi → Proxy → PC_IP:8080
# Open Shopee → browse products → mitmproxy captures price API calls
```

### MIUI Battery Optimization

```kotlin
// Request in setup flow:
startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    .apply { data = Uri.parse("package:$packageName") })
```

### Accessibility Service

```bash
# After each reinstall:
adb shell settings put secure enabled_accessibility_services \
  com.personal.shopeekit/.service.ShopeeAccessibilityService
```

---

## 10. Implementation Phases

### Phase 1 — Foundation (Day 1-2)
- [ ] Android project setup (per android-setup-guide.md)
- [ ] Dependencies: OkHttp, Room, WorkManager, DataStore
- [ ] KitFeature interface
- [ ] ShopeeKitForegroundService + ShopeeAccessibilityService skeletons
- [ ] MainActivity với feature tab navigation
- [ ] MIUI battery exemption request

### Phase 2 — VoucherSniper (Day 3-5)
- [ ] TimeSync (server offset calibration)
- [ ] RttMeasurer
- [ ] SpeculativeScheduler với spin-wait
- [ ] SniperEngine state machine
- [ ] SniperOverlayView countdown
- [ ] AccessibilityService click trigger
- [ ] Manual test với real voucher

### Phase 3 — PriceHistory (Day 6-7)
- [ ] Room DB (PriceRecord, TrackedProduct)
- [ ] PricePoller WorkManager
- [ ] BestPriceCalculator (price + voucher combo)
- [ ] Price alert notification
- [ ] PriceHistoryView with LineChart

### Phase 4 — Polish & Extensibility (Day 8)
- [ ] TokenSetupActivity (mitmproxy config)
- [ ] Error handling + retry logic
- [ ] Logcat-friendly logging
- [ ] Release APK build

---

## 11. Known Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| X-Sap-Ri blocks direct HTTP voucher claim | Use Accessibility click (Shopee generates internally) |
| Shopee updates API price endpoints | Config-driven URL, update in DataStore |
| MIUI kills ForegroundService | Battery exemption + startForeground with notification |
| AccessibilityService disabled on reinstall | ADB one-liner in setup guide |
| Shopee detects automated behavior | Jitter + rate limit + real device identity |
| Spin-wait drains battery | Only active 50ms per snipe attempt |
