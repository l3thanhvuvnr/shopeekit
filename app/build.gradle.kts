plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.personal.shopeekit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.personal.shopeekit"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // "release-debug" uses the same debug keystore so you can sideload without
        // creating a production keystore.  Replace with a real keystore before publishing.
        create("releaseDebug") {
            val debugKeystorePath = System.getProperty("user.home") +
                "/.android/debug.keystore"
            storeFile     = file(debugKeystorePath)
            storePassword = "android"
            keyAlias      = "androiddebugkey"
            keyPassword   = "android"
        }
    }

    buildTypes {
        debug {
            // Emulator / PC cong ty -> qua Cloudflare Worker de bypass Kaspersky
            buildConfigField("String", "SHOPEE_BASE_URL", "\"https://shopee-relay.vu-lethanh.workers.dev\"")
            buildConfigField("Boolean", "USE_RELAY", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with debug keystore so APK can be installed on real device without
            // going through Play Store.  Swap to a production keystore when publishing.
            signingConfig = signingConfigs.getByName("releaseDebug")
            // Dien thoai that -> goi thang shopee.vn, cookie khong di qua trung gian
            buildConfigField("String", "SHOPEE_BASE_URL", "\"https://shopee.vn\"")
            buildConfigField("Boolean", "USE_RELAY", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Export Room schemas to VCS so migrations are reviewable.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // OkHttp (raw HTTP client)
    implementation(libs.okhttp)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Lifecycle (ViewModel) + Activity KTX (by viewModels())
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)

    // Charts
    implementation(libs.mpandroidchart)

    // Unit Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20180813")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
