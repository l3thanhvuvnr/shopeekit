# sk.ps1 - ShopeeKit Build Shortcuts (Windows PowerShell, no make required)
# Usage: .\sk.ps1 <command>
#
# Commands:
#   debug           Build debug APK
#   release         Build release APK + copy to Desktop
#   install         Build debug + install via ADB
#   install-release Build release + install via ADB
#   clean           Clean build artifacts
#   logcat          Filtered logcat (ShopeeKit tags only)
#   logcat-all      Full logcat
#   devices         List ADB devices
#   uninstall       Uninstall app from device
#   restart         Force-stop + relaunch app
#   ping-worker     Ping Cloudflare Worker relay
#   deploy-worker   Redeploy Cloudflare Worker via REST API

param(
    [Parameter(Position=0)]
    [string]$Command = "help"
)

$PACKAGE     = "com.personal.shopeekit"
$APK_DEBUG   = "app\build\outputs\apk\debug\app-debug.apk"
$APK_RELEASE = "app\build\outputs\apk\release\app-release.apk"
$APK_COPY    = "$env:USERPROFILE\Desktop\ShopeeKit.apk"
$WORKER_URL  = "https://shopee-relay.vu-lethanh.workers.dev"

function Write-Header($text) {
    Write-Host ""
    Write-Host "  $text" -ForegroundColor Cyan
    Write-Host "  $("─" * $text.Length)" -ForegroundColor DarkCyan
}

function Run-Gradle($args_) {
    & ".\gradlew.bat" $args_
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Gradle failed (exit code $LASTEXITCODE)" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

switch ($Command.ToLower()) {

    "help" {
        Write-Header "ShopeeKit Build Shortcuts"
        $cmds = @(
            @("debug",           "Build debug APK"),
            @("release",         "Build release APK + copy to Desktop"),
            @("install",         "Build debug + install on device/emulator"),
            @("install-release", "Build release + install on device"),
            @("clean",           "Clean build artifacts"),
            @("logcat",          "Logcat filtered for ShopeeKit"),
            @("logcat-all",      "Full logcat"),
            @("devices",         "List connected ADB devices"),
            @("uninstall",       "Uninstall from device"),
            @("restart",         "Force-stop + relaunch app"),
            @("ping-worker",     "Ping Cloudflare Worker relay"),
            @("deploy-worker",   "Redeploy Cloudflare Worker via API")
        )
        foreach ($c in $cmds) {
            Write-Host ("  {0,-20} {1}" -f $c[0], $c[1]) -ForegroundColor White
        }
        Write-Host ""
    }

    "debug" {
        Write-Header "Building debug APK..."
        Run-Gradle ":app:assembleDebug"
        Write-Host ""
        Write-Host "  ✅ APK: $APK_DEBUG" -ForegroundColor Green
    }

    "release" {
        Write-Header "Building release APK..."
        Run-Gradle ":app:assembleRelease"
        if (Test-Path $APK_RELEASE) {
            Copy-Item $APK_RELEASE $APK_COPY -Force
            Write-Host ""
            Write-Host "  ✅ APK copied to: $APK_COPY" -ForegroundColor Green
            Write-Host "  Transfer this file to your phone and install it." -ForegroundColor Yellow
        } else {
            Write-Host ""
            Write-Host "  ⚠  Release APK not found." -ForegroundColor Yellow
            Write-Host "  Check signing config in app\build.gradle.kts" -ForegroundColor Yellow
            Write-Host "  Tip: For unsigned release, use 'debug' instead or add signingConfigs" -ForegroundColor DarkYellow
        }
    }

    "install" {
        Write-Header "Building + installing debug APK..."
        Run-Gradle ":app:assembleDebug"
        & adb install -r $APK_DEBUG
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  ✅ Debug APK installed" -ForegroundColor Green
        }
    }

    "install-release" {
        Write-Header "Building + installing release APK..."
        Run-Gradle ":app:assembleRelease"
        if (Test-Path $APK_RELEASE) {
            & adb install -r $APK_RELEASE
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  ✅ Release APK installed" -ForegroundColor Green
            }
        } else {
            Write-Host "  ❌ Release APK not found. Check signing config." -ForegroundColor Red
        }
    }

    "clean" {
        Write-Header "Cleaning build artifacts..."
        Run-Gradle "clean"
        Write-Host "  ✅ Done" -ForegroundColor Green
    }

    "logcat" {
        Write-Host "  Clearing logcat..." -ForegroundColor DarkGray
        & adb logcat -c
        Write-Host "  Watching ShopeeKit logs (Ctrl+C to stop)..." -ForegroundColor Cyan
        & adb logcat `
            ShopeeKit:V ShopeeSearch:V ShopeeHttpClient:V `
            SniperEngine:V ShopeeAccess:V ShopeeConfig:V `
            CookieSync:V ShopeeCookieSync:V `
            "*:S"
    }

    "logcat-all" {
        Write-Host "  Full logcat (Ctrl+C to stop)..." -ForegroundColor Cyan
        & adb logcat "*:V"
    }

    "devices" {
        & adb devices -l
    }

    "uninstall" {
        Write-Host "  Uninstalling $PACKAGE..." -ForegroundColor Yellow
        & adb uninstall $PACKAGE
        Write-Host "  ✅ Done" -ForegroundColor Green
    }

    "restart" {
        Write-Host "  Restarting $PACKAGE..." -ForegroundColor Yellow
        & adb shell am force-stop $PACKAGE
        Start-Sleep -Milliseconds 500
        & adb shell monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1
        Write-Host "  ✅ App restarted" -ForegroundColor Green
    }

    "ping-worker" {
        Write-Header "Pinging Cloudflare Worker..."
        Write-Host "  URL: $WORKER_URL/api/v4/account/basic" -ForegroundColor DarkGray
        try {
            $resp = Invoke-WebRequest -Uri "$WORKER_URL/api/v4/account/basic" `
                                      -UseBasicParsing -TimeoutSec 10
            Write-Host "  Status: $($resp.StatusCode)" -ForegroundColor Green
            Write-Host "  Body: $($resp.Content.Substring(0, [Math]::Min(200, $resp.Content.Length)))"
        } catch {
            Write-Host "  ❌ Request failed: $_" -ForegroundColor Red
        }
    }

    "deploy-worker" {
        $scriptPath = "C:\Temp\shopee-worker\worker_deploy.js"
        if (-not (Test-Path $scriptPath)) {
            Write-Host "  ❌ Worker script not found at $scriptPath" -ForegroundColor Red
            exit 1
        }
        Write-Header "Deploying Cloudflare Worker..."
        # Reads CF_API_TOKEN from environment or prompts
        $token = $env:CF_API_TOKEN
        if (-not $token) {
            $token = Read-Host "Cloudflare API Token"
        }
        $accountId = $env:CF_ACCOUNT_ID
        if (-not $accountId) {
            $accountId = Read-Host "Cloudflare Account ID"
        }
        $workerName = "shopee-relay"
        $scriptContent = Get-Content $scriptPath -Raw

        $boundary = "----FormBoundary" + [System.Guid]::NewGuid().ToString("N")
        $metadata = '{"main_module":"worker.js","compatibility_date":"2024-01-01"}'
        $body = "--$boundary`r`n"
        $body += "Content-Disposition: form-data; name=`"metadata`"`r`n"
        $body += "Content-Type: application/json`r`n`r`n"
        $body += "$metadata`r`n"
        $body += "--$boundary`r`n"
        $body += "Content-Disposition: form-data; name=`"worker.js`"; filename=`"worker.js`"`r`n"
        $body += "Content-Type: application/javascript+module`r`n`r`n"
        $body += "$scriptContent`r`n"
        $body += "--$boundary--"

        try {
            $resp = Invoke-WebRequest `
                -Uri "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName" `
                -Method PUT `
                -Headers @{ "Authorization" = "Bearer $token" } `
                -ContentType "multipart/form-data; boundary=$boundary" `
                -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
                -UseBasicParsing
            $json = $resp.Content | ConvertFrom-Json
            if ($json.success) {
                Write-Host "  ✅ Worker deployed successfully!" -ForegroundColor Green
            } else {
                Write-Host "  ❌ Deploy failed: $($json.errors | ConvertTo-Json)" -ForegroundColor Red
            }
        } catch {
            Write-Host "  ❌ Request failed: $_" -ForegroundColor Red
        }
    }

    default {
        Write-Host "  Unknown command: $Command" -ForegroundColor Red
        Write-Host "  Run '.\sk.ps1 help' for available commands." -ForegroundColor Yellow
    }
}
