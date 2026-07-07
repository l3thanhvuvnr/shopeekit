# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ShopeeKit — a personal, single-module Android app (Kotlin, Views/XML, no Compose) for Shopee VN. Two features:
- **CheckoutSniper** — grabs time-gated flash/"săn" vouchers by driving Shopee's own UI at a server-clock-gated instant.
- **Price History** — polls product prices into Room, alerts on drops, computes best price+voucher combo.

It is a real-device testing tool, not a Play Store app. Comments and many UI strings are in Vietnamese — match that in surrounding code.

## Build / test / run

From repo root (Windows, git-bash — use `./gradlew.bat`):

```bash
./gradlew.bat assembleDebug          # debug APK  -> app/build/outputs/apk/debug/app-debug.apk
./gradlew.bat assembleRelease        # release APK (signed with DEBUG keystore, see below)
./gradlew.bat testDebugUnitTest      # run all JVM unit tests
./gradlew.bat lint                   # Android lint

# single test class / method:
./gradlew.bat testDebugUnitTest --tests "com.personal.shopeekit.features.checkout.OrderResultParserTest"
./gradlew.bat testDebugUnitTest --tests "com.personal.shopeekit.core.time.TimeSyncEdgeDetectionTest.*"
```

Device bring-up after install (non-obvious, needed for the app to function):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Accessibility service MUST be re-enabled after every reinstall:
adb shell settings put secure enabled_accessibility_services com.personal.shopeekit/.service.ShopeeAccessibilityService
adb shell appops set com.personal.shopeekit SYSTEM_ALERT_WINDOW allow   # overlay countdown
adb logcat -s ShopeeKit:*                                              # live logs (see KitLogger below)
```

Auth tokens (`Cookie` + `User-Agent`) are captured with mitmproxy against a patched Shopee APK and entered in-app via **TokenSetupActivity** (⚙️ Config) — see README.md for the mitmproxy steps.

**Remote device testing:** `tools/remote-adb/` wires the test phone to the dev PC over Tailscale so `adb`/logcat/install work from a different network (`connect-phone.bat`, `rearm-phone.bat`, `screen-phone.bat` + README there).

## Architecture

**Feature plugin pattern.** `KitFeature` (root package) is the extension point: `initialize/release`, `createMainView`, `createSettingsView`. Features are registered as a list in `ShopeeKitApp.features` (currently `PriceHistoryFeature`, `CheckoutSniperFeature`). `Application.onCreate` runs `KitLogger.init` → `ShopeeHttpClient.init` → each `feature.initialize`. Add a feature by implementing `KitFeature` and adding it to that list.

**CheckoutSniper (`features/checkout/`) is the core and the subtle part.** The snipe is gated on **server time, not the device clock**, and deliberately **fires just AFTER** the release instant T:
- `CheckoutSniperEngine` orchestrates: `arm(config)` → calibrate server-clock offset (`TimeSync`) + `RttMeasurer` → schedule fire at `T + COMMIT_MARGIN_MS` (Shopee's voucher backend lags wall-clock 00:00) → on fire, drive the UI to open the voucher drawer, confirm best voucher, place order → **retry by reopening the drawer fresh** until `T + retryTimeout`, because Shopee commonly releases the voucher late. Stops on SUCCESS / OUT_OF_STOCK / PAYMENT_ERROR / REQUIRES_PIN / WINDOW_END / MAX_ATTEMPTS.
- Actions execute through **`ShopeeAccessibilityService` (`service/`)** via `performAction`/gestures. **Design reason (important):** tapping Shopee's real UI routes each action through Shopee's own request signing (`X-Sap-Ri` etc.), so automation is UI-driven rather than direct API calls.
- Element location: `ShopeeUIDiscovery` + `UiMatch` do scoring-based matching over the a11y tree, keyed off Shopee's stable resource-ids (which win outright) with scored i18n text + a shape/position heuristic as fallback. Ids that shift between Shopee versions self-heal via a persisted id-hint cache (`cacheId`/`preloadCache`). The old user-pinned calibration layer was **intentionally removed** — it caused a wrong-button drift bug; don't reintroduce it. The destructive place-order tap still refuses a heuristic-only guess (`PlaceOrderResult.NeedsCalibration` = "detection failed, stop"). Parsers: `VoucherApplyParser`, `OrderResultParser`, `CheckoutModels`. `HumanBehavior` adds tap jitter.
- Timing subsystem (`core/time/`): `TimeSync` (server offset via HTTP `Date`-header edge detection), `RttMeasurer`, `SpeculativeScheduler` (`nanoTime` spin-wait). Poll cadence (~16ms) is the main latency knob; drawer animations are Shopee's and can't be shortened.

**PriceHistory (`features/price/`).** `PricePoller` (WorkManager) fetches Shopee API → Room (`PriceDatabase`, DAO in `db/`; schemas exported to `app/schemas/`). `BestPriceCalculator`, `PriceAlertManager` (notifications), `ShopeeSearchRepository`, `PriceRepository`. UI: `PriceHistoryActivity` + `PriceHistoryViewModel` + MPAndroidChart via `PriceChartRenderer`.

**Networking (`core/network/ShopeeHttpClient`).** OkHttp singleton. Base URL comes from `BuildConfig.SHOPEE_BASE_URL`, set per build type:
- **debug** → Cloudflare Worker relay (`shopee-relay.vu-lethanh.workers.dev`, `USE_RELAY=true`, trust-all SSL) to bypass corporate SSL inspection.
- **release** → `https://shopee.vn` direct.
`buildRequest()` attaches Shopee anti-bot headers. Users can override the base URL at runtime. All app-originated calls are read-only GETs against Shopee's own `/api/v4/...`.

**Config/auth (`core/storage/`).** `ShopeeConfig` (+ `AppDataStore`, DataStore) stores Cookie/User-Agent and base-URL override.

**Logging (`core/logging/KitLogger`).** Singleton `object`; every entry fans out to an in-memory ring buffer (2000), a daily file (`filesDir/logs/kit_log_<date>.txt`), and `android.util.Log` under tag `ShopeeKit/<tag>`. Exposes `flow: SharedFlow<Entry>` for live viewing; `LogViewerActivity` renders it in realtime. Writers use short tag codes: `ENG` (engine), `ACC`/`ShopeeAccess` (a11y), `PRC` (price), `TSY`/`RTT`/`NET` (time/net), `ORP` (order parse), etc.

**Services (`service/`).** `ShopeeKitForegroundService` (`specialUse`, `START_STICKY`) keeps the engine alive during a countdown; `BootReceiver` reschedules on boot; `ShopeeAccessibilityService` is bound via `BIND_ACCESSIBILITY_SERVICE`.

## Build config notes

- Single Gradle module `:app`, Kotlin DSL + version catalog `gradle/libs.versions.toml`. AGP 8.7, Gradle 9, Kotlin 2.0.21, Java 17. `minSdk 26`, target/compile `35`. `viewBinding` + `buildConfig` on; KSP for Room.
- Release is signed with the **debug keystore** (`releaseDebug` signingConfig) so the APK sideloads without a production keystore — swap before publishing.
- Room schemas are exported to `app/schemas/` (KSP `room.schemaLocation`); keep them in VCS so migrations stay reviewable.

## Testing

Unit tests in `app/src/test/` are pure-JVM (JUnit4 + `kotlinx-coroutines-test`), covering the logic that must be right without a device: parsers (`OrderResultParser`, `VoucherApplyParser`, `CheckoutModels`, Shopee API parsing), UI-match scoring (`UiMatch`, `UIDiscovery`), `TimeSync` edge detection, retry logic, and `HumanBehavior`. No emulator needed. Prefer adding to these when changing sniper/parser logic.
