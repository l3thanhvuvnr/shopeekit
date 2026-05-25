# ShopeeKit 🛒

Android app cá nhân — Voucher Sniper + Price History Tracker cho Shopee VN.

> Máy: Xiaomi 15 | Android 16 | API 35+

---

## Tính năng

| Feature | Mô tả |
|---------|-------|
| 🎯 **Voucher Sniper** | Giành voucher Shopee với speculative fire (±2ms) |
| 📊 **Price History** | Track lịch sử giá + alert khi giá drop |
| 💡 **Best Price** | Tính giá tốt nhất: price + voucher combo |
| 🏗️ **Kit Architecture** | Dễ mở rộng thêm tính năng |

---

## Setup (1 lần)

### 1. SSL Bypass cho mitmproxy

```bash
# PC — patch APK để bypass SSL pinning
npm install -g apk-mitm
apk-mitm shopee.apk
adb install com.shopee.vn-patched.apk
```

### 2. Capture Auth Headers

```bash
# PC — chạy mitmproxy
pip install mitmproxy
mitmproxy --listen-port 8080

# Android: Settings → WiFi → [Mạng đang dùng] → Proxy → Manual
# Host: <IP máy tính> | Port: 8080
```

Mở Shopee patched → browse sản phẩm → mitmproxy capture.  
Copy `Cookie` và `User-Agent` → dán vào **ShopeeKit → ⚙️ Config**.

### 3. Build & Install ShopeeKit

```bash
# Mở project trong Android Studio
# File → Settings → Build → Gradle → JDK: jbr-21

# Build APK
gradlew.bat assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Bật Accessibility Service

```bash
# Sau mỗi lần reinstall — chạy lệnh này:
adb shell settings put secure enabled_accessibility_services \
  com.personal.shopeekit/.service.ShopeeAccessibilityService
```

Hoặc: Settings → Accessibility → ShopeeKit → Bật.

### 5. Tắt Battery Optimization (MIUI quan trọng!)

App sẽ tự nhắc khi lần đầu mở.  
Hoặc: Settings → Apps → ShopeeKit → Battery → No restrictions.

---

## Hướng dẫn dùng Voucher Sniper

1. Mở ShopeeKit → **🎯 Voucher Sniper**
2. Paste URL voucher Shopee
3. Chọn ngày + giờ mở voucher
4. Nhấn **"BẮT ĐẦU SNIPE"**
5. Mở Shopee (để AccessibilityService active)
6. App tự countdown + click đúng thời điểm

**Quan trọng**: Mở Shopee và navigate đến trang voucher ~2 phút trước T.

---

## Tech Stack

| Layer | Thư viện |
|-------|---------|
| Language | Kotlin 2.0.21 |
| Timing | `System.nanoTime()` + spin-wait |
| Network | OkHttp 4.12 (raw) |
| Database | Room 2.7 |
| Background | WorkManager 2.10 + ForegroundService |
| UI | Views/XML (minimal) |
| Config | DataStore |

---

## Project Structure

```
app/src/main/java/com/personal/shopeekit/
├── core/
│   ├── network/ShopeeHttpClient.kt     ← OkHttp singleton
│   ├── time/TimeSync.kt                ← Server clock offset
│   ├── time/RttMeasurer.kt             ← RTT measurement
│   └── storage/ShopeeConfig.kt         ← Auth tokens (DataStore)
├── features/
│   ├── sniper/
│   │   ├── SniperState.kt              ← State sealed class
│   │   ├── SpeculativeScheduler.kt     ← ±2ms spin-wait timer
│   │   ├── SniperEngine.kt             ← Orchestrator
│   │   └── VoucherSniperFeature.kt
│   └── price/
│       ├── db/                         ← Room entities + DAO
│       ├── PricePoller.kt              ← WorkManager worker
│       ├── PriceAlertManager.kt        ← Notifications
│       ├── BestPriceCalculator.kt      ← price + voucher combo
│       └── PriceHistoryFeature.kt
├── service/
│   ├── ShopeeAccessibilityService.kt   ← Primary click trigger
│   └── ShopeeKitForegroundService.kt   ← Keeps alive during countdown
└── ui/
    ├── MainActivity.kt
    ├── SniperSetupActivity.kt
    ├── PriceHistoryActivity.kt
    ├── overlay/SniperOverlayView.kt    ← Floating countdown
    └── setup/TokenSetupActivity.kt
```

---

## ADB Commands

```bash
# Xem log realtime
adb logcat -s ShopeeKit:D ShopeeSniper:D

# Bật Accessibility Service
adb shell settings put secure enabled_accessibility_services \
  com.personal.shopeekit/.service.ShopeeAccessibilityService

# Check service có bật không
adb shell settings get secure enabled_accessibility_services

# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Cấp overlay permission
adb shell appops set com.personal.shopeekit SYSTEM_ALERT_WINDOW allow
```

---

## Gradle Version Stack

| Component | Version |
|-----------|---------|
| Gradle Wrapper | 9.0.0 |
| AGP | 8.7.0 |
| Kotlin | 2.0.21 |
| JVM Target | 17 |
| Compile SDK | 35 |
| Min SDK | 26 |

> ⚠️ Xem `android-setup-guide.md` ở thư mục cha để tránh lỗi build.
