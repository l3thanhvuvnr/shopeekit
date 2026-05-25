# ShopeeKit — Implementation Tasks
> Generated: 2026-05-25
> Source: docs/specs/2026-05-25-shopeekit-core.md
> Project: Android Kotlin app, Min SDK 26, Gradle 9.0.0 / AGP 8.7.0 / JVM 17

---

## Phase 1 — Project Foundation

- [ ] TASK-01: Create Android project skeleton
  - Tạo `app/build.gradle.kts` với compileSdk=35, minSdk=26, targetSdk=35
  - Tạo `gradle/libs.versions.toml` với đủ dependencies
  - Tạo `gradle/wrapper/gradle-wrapper.properties` (Gradle 9.0.0)
  - Tạo `settings.gradle.kts`
  - Tạo `build.gradle.kts` (root)
  - Output: project compiles

- [ ] TASK-02: AndroidManifest.xml + App class
  - Tạo `app/src/main/AndroidManifest.xml` với tất cả permissions
  - Tạo `ShopeeKitApp.kt` (Application class) với feature registration
  - Tạo `KitFeature.kt` interface
  - Tạo `res/mipmap-anydpi-v26/ic_launcher.xml` + drawable resources
  - Output: manifest + app class

- [ ] TASK-03: Core module — Network & Time
  - Tạo `core/network/ShopeeHttpClient.kt` (OkHttp singleton)
  - Tạo `core/time/TimeSync.kt` (server offset calibration)
  - Tạo `core/time/RttMeasurer.kt` (RTT measurement)
  - Output: OkHttp client + TimeSync ready

- [ ] TASK-04: Core module — Storage
  - Tạo `core/storage/AppDataStore.kt` (DataStore helper)
  - Tạo `core/storage/ShopeeConfig.kt` (tokens, headers, endpoints)
  - Output: DataStore + config storage

---

## Phase 2 — Services

- [ ] TASK-05: ShopeeAccessibilityService
  - Tạo `service/ShopeeAccessibilityService.kt`
  - Tạo `res/xml/accessibility_service_config.xml`
  - Implement: `onAccessibilityEvent`, `findClaimButton`, `performClaimClick`
  - Output: Accessibility service registers + can detect Shopee UI

- [ ] TASK-06: ShopeeKitForegroundService
  - Tạo `service/ShopeeKitForegroundService.kt`
  - Implement: startForeground notification, lifecycle management
  - Expose binder cho activity communication
  - Output: ForegroundService starts + survives MIUI kill

---

## Phase 3 — VoucherSniper

- [ ] TASK-07: SniperState + SpeculativeScheduler
  - Tạo `features/sniper/SniperState.kt` (sealed class)
  - Tạo `features/sniper/SpeculativeScheduler.kt`
    - `scheduleAt(targetEpochMs: Long, callback: () -> Unit)`
    - Coarse Thread.sleep đến T-50ms
    - Spin-wait `System.nanoTime()` với `THREAD_PRIORITY_URGENT_AUDIO`
  - Output: scheduler fires within ±2ms of target

- [ ] TASK-08: SniperEngine — Orchestrator
  - Tạo `features/sniper/SniperEngine.kt`
  - Implement full flow:
    1. `arm(voucherUrl, releaseTimeMs)` 
    2. `calibrate()` → TimeSync + RttMeasurer
    3. `scheduleSpeculativeFire()`
    4. On fire → AccessibilityService.performClaimClick()
    5. `monitorResult()` → detect success/fail from Shopee UI
    6. Retry logic (max 3x, +50ms between)
  - StateFlow<SniperState> cho UI observation
  - Output: full sniper flow works end-to-end

- [ ] TASK-09: VoucherSniperFeature + UI
  - Tạo `features/sniper/VoucherSniperFeature.kt` (implements KitFeature)
  - Tạo `ui/SniperSetupActivity.kt`
    - Input: Voucher URL + datetime picker
    - Countdown display
    - ARM button
    - Status display (IDLE/ARMED/FIRED/SUCCESS/FAILED)
  - Tạo `ui/overlay/SniperOverlayView.kt`
    - TYPE_ACCESSIBILITY_OVERLAY floating countdown
    - 1px trick cho non-blocking
    - Shows: T-Xs countdown, status indicator
  - Output: user can setup + monitor sniper

---

## Phase 4 — PriceHistory

- [ ] TASK-10: Room Database
  - Tạo `features/price/db/PriceRecord.kt` (@Entity)
  - Tạo `features/price/db/TrackedProduct.kt` (@Entity)
  - Tạo `features/price/db/PriceDao.kt` (@Dao)
  - Tạo `features/price/db/PriceDatabase.kt` (@Database)
  - Output: Room DB compiles + migrations OK

- [ ] TASK-11: PricePoller (WorkManager)
  - Tạo `features/price/PricePoller.kt` (ListenableWorker)
  - Implement: fetch Shopee product price via OkHttp
    - URL: `https://shopee.vn/api/v4/pdp/get_pc?item_id={id}&shop_id={shopId}`
    - Parse price from JSON response
    - Store in Room
    - Check alert threshold → fire notification if triggered
  - Tạo `features/price/PriceAlertManager.kt` (NotificationManager wrapper)
  - Output: background poll works + notification fires on price drop

- [ ] TASK-12: BestPriceCalculator
  - Tạo `features/price/BestPriceCalculator.kt`
  - Logic: `effectivePrice = currentPrice - bestVoucherDiscount`
  - Expose: `getCurrentBestPrice(productId)` 
  - Output: calculator returns correct effective price

- [ ] TASK-13: PriceHistoryFeature + UI
  - Tạo `features/price/PriceHistoryFeature.kt` (implements KitFeature)
  - Tạo `ui/PriceHistoryActivity.kt`
    - List of tracked products
    - Add product (URL input → extract productId/shopId)
    - Per-product: price chart (custom LineChartView) + best price + alert threshold
  - Tạo `ui/views/LineChartView.kt` (custom View, simple line chart from Room data)
  - Output: user can track + view price history

---

## Phase 5 — Main UI + Polish

- [ ] TASK-14: MainActivity + Navigation
  - Tạo `ui/MainActivity.kt`
    - Tab/bottom nav: VoucherSniper | PriceHistory
    - Launch Accessibility Service setup if not enabled
    - Launch battery optimization exemption request
  - Tạo `ui/setup/TokenSetupActivity.kt`
    - Input fields: Cookie, X-Sap-Ri template, User-Agent
    - Save to ShopeeConfig DataStore
    - Test connection button
  - Output: full app navigation works

- [ ] TASK-15: .gitignore + Build Config + README
  - Tạo `.gitignore` (Kotlin/Android patterns)
  - Tạo `local.properties` (sdk.dir placeholder)
  - Tạo `README.md` với setup instructions (mitmproxy, apk-mitm, ADB commands)
  - Verify all res files exist (strings.xml, themes.xml, colors.xml)
  - Output: project ready to open in Android Studio

---

## Dependencies (libs.versions.toml)

```toml
[versions]
agp = "8.7.0"
kotlin = "2.0.21"
coreKtx = "1.16.0"
appcompat = "1.7.0"
material = "1.12.0"
cardview = "1.0.0"
datastore = "1.1.1"
coroutines = "1.10.1"
okhttp = "4.12.0"
room = "2.7.0"
work = "2.10.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
androidx-cardview = { group = "androidx.cardview", name = "cardview", version.ref = "cardview" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version = "2.0.21-1.0.28" }
```

## Package Structure
```
app/src/main/java/com/personal/shopeekit/
├── ShopeeKitApp.kt
├── KitFeature.kt
├── core/
│   ├── network/ShopeeHttpClient.kt
│   ├── time/TimeSync.kt
│   ├── time/RttMeasurer.kt
│   └── storage/
│       ├── AppDataStore.kt
│       └── ShopeeConfig.kt
├── features/
│   ├── sniper/
│   │   ├── SniperState.kt
│   │   ├── SpeculativeScheduler.kt
│   │   ├── SniperEngine.kt
│   │   └── VoucherSniperFeature.kt
│   └── price/
│       ├── db/
│       │   ├── PriceRecord.kt
│       │   ├── TrackedProduct.kt
│       │   ├── PriceDao.kt
│       │   └── PriceDatabase.kt
│       ├── PricePoller.kt
│       ├── PriceAlertManager.kt
│       ├── BestPriceCalculator.kt
│       └── PriceHistoryFeature.kt
├── service/
│   ├── ShopeeKitForegroundService.kt
│   └── ShopeeAccessibilityService.kt
└── ui/
    ├── MainActivity.kt
    ├── SniperSetupActivity.kt
    ├── PriceHistoryActivity.kt
    ├── overlay/SniperOverlayView.kt
    ├── views/LineChartView.kt
    └── setup/TokenSetupActivity.kt
```
