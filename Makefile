# ShopeeKit Android Build Shortcuts
# Usage: make <target>
# Requires: Android SDK, adb in PATH

GRADLEW     = gradlew.bat
ADB         = adb
PACKAGE     = com.personal.shopeekit
APK_DEBUG   = app\build\outputs\apk\debug\app-debug.apk
APK_RELEASE = app\build\outputs\apk\release\app-release.apk
APK_COPY    = C:\Users\Public\Desktop\ShopeeKit.apk

.PHONY: help build debug release install install-release clean logcat logcat-all \
        devices uninstall deploy-worker restart ping-worker

# ─── Default ──────────────────────────────────────────────────────────────────

help:
	@echo.
	@echo   ShopeeKit Build Shortcuts
	@echo   ─────────────────────────
	@echo   make debug          Build debug APK
	@echo   make release        Build release APK + copy to Desktop
	@echo   make install        Build debug + install on connected device/emulator
	@echo   make install-release  Build release + install on connected device
	@echo   make clean          Clean build artifacts
	@echo   make logcat         Logcat filtered for ShopeeKit
	@echo   make logcat-all     Full logcat (verbose)
	@echo   make devices        List connected ADB devices
	@echo   make uninstall      Uninstall from connected device
	@echo   make deploy-worker  Redeploy Cloudflare Worker relay
	@echo   make ping-worker    Ping the Cloudflare Worker relay
	@echo.

# ─── Build ────────────────────────────────────────────────────────────────────

debug:
	$(GRADLEW) :app:assembleDebug
	@echo.
	@echo APK: $(APK_DEBUG)

release:
	$(GRADLEW) :app:assembleRelease
	@if exist "$(APK_RELEASE)" ( \
		copy /Y "$(APK_RELEASE)" "$(APK_COPY)" && \
		echo. && \
		echo ✅ APK copied to: $(APK_COPY) \
	) else ( \
		echo ⚠  Release APK not found at $(APK_RELEASE) && \
		echo    Check signing config in app\build.gradle.kts \
	)

# ─── Install ──────────────────────────────────────────────────────────────────

install: debug
	$(ADB) install -r $(APK_DEBUG)
	@echo ✅ Debug APK installed

install-release: release
	@if exist "$(APK_RELEASE)" ( \
		$(ADB) install -r $(APK_RELEASE) && \
		echo ✅ Release APK installed \
	) else ( \
		echo ❌ Release APK not found \
	)

# Build release then sideload via Windows file manager (for manual USB transfer)
release-copy: release
	@echo.
	@echo APK is at: $(APK_COPY)
	@echo Transfer this file to your phone and install it.

# ─── Clean ────────────────────────────────────────────────────────────────────

clean:
	$(GRADLEW) clean
	@echo ✅ Build artifacts cleaned

# ─── Device / Logcat ──────────────────────────────────────────────────────────

devices:
	$(ADB) devices -l

uninstall:
	$(ADB) uninstall $(PACKAGE)
	@echo ✅ Uninstalled $(PACKAGE)

logcat:
	$(ADB) logcat -c
	$(ADB) logcat ShopeeKit:V ShopeeSearch:V ShopeeHttpClient:V SniperEngine:V \
	              ShopeeAccess:V ShopeeConfig:V CookieSync:V *:S

logcat-all:
	$(ADB) logcat *:V

# Clear logcat then watch
logcat-fresh:
	$(ADB) logcat -c && $(ADB) logcat ShopeeKit:V ShopeeSearch:V ShopeeHttpClient:V \
	              SniperEngine:V ShopeeAccess:V CookieSync:V *:S

# ─── App Control ──────────────────────────────────────────────────────────────

restart:
	$(ADB) shell am force-stop $(PACKAGE)
	$(ADB) shell monkey -p $(PACKAGE) -c android.intent.category.LAUNCHER 1

open:
	$(ADB) shell monkey -p $(PACKAGE) -c android.intent.category.LAUNCHER 1

# ─── Cloudflare Worker ────────────────────────────────────────────────────────

ping-worker:
	curl -s "https://shopee-relay.vu-lethanh.workers.dev/api/v4/account/basic" | \
	  python -m json.tool 2>nul || \
	  curl -s "https://shopee-relay.vu-lethanh.workers.dev/api/v4/account/basic"

deploy-worker:
	@echo Deploying Cloudflare Worker via REST API...
	@if not exist "C:\Temp\shopee-worker\worker_deploy.js" ( \
		echo ❌ Worker script not found at C:\Temp\shopee-worker\worker_deploy.js \
	) else ( \
		powershell -File "scripts\deploy_worker.ps1" \
	)
