import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets GITHUB_RUN_NUMBER, so every build installs as an update of the previous one
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// version name <line>.<builds since the line started> (version.properties; a release moves to the next line)
val versionProps = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val versionLine: String = versionProps.getProperty("line", "1.2").trim()
val versionBase: Int = versionProps.getProperty("base", "0").trim().toInt()

android {
    namespace = "io.github.pmaxai.cardscanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.pmaxai.cardscanner"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "$versionLine.${(buildNumber - versionBase).coerceAtLeast(0)}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // screenshots from the tests land in build/outputs/connected_android_test_additional_output
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        // the staging shop's key for the Webshop test (CI secrets; the test is skipped without them).
        // Only the test run gets them, never the app.
        System.getenv("POKESHOP_STAGING_KEY_ID")?.takeIf { it.isNotBlank() }?.let { testInstrumentationRunnerArguments["shopKeyId"] = it }
        System.getenv("POKESHOP_STAGING_SECRET")?.takeIf { it.isNotBlank() }?.let { testInstrumentationRunnerArguments["shopSecret"] = it }
        // a MinIO the CI starts for the S3 upload test (recordings); the test is skipped without it
        System.getenv("S3_TEST_ENDPOINT")?.takeIf { it.isNotBlank() }?.let { testInstrumentationRunnerArguments["s3Endpoint"] = it }
        // The account on overpricedcardboard.com (gradle.properties): the app needs one when this is on.
        // accountUrl is the server for codes typed by hand (a scanned QR code names its own host);
        // a staging build: -Pcardscanner.accountUrl=https://staging.overpricedcardboard.com
        val accountRequired = providers.gradleProperty("cardscanner.accountRequired").orNull != "false"
        val accountUrl = providers.gradleProperty("cardscanner.accountUrl").orNull ?: "https://overpricedcardboard.com"
        buildConfigField("boolean", "ACCOUNT_REQUIRED", accountRequired.toString())
        buildConfigField("String", "ACCOUNT_URL", "\"$accountUrl\"")
        // Continue with Google (store editions): the shop's server client id (public; empty: only "Use another email")
        val googleClient = providers.gradleProperty("cardscanner.googleServerClientId").orNull.orEmpty()
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleClient\"")
        // the staging server's access header for the store tests (CI secret; only the test run gets it)
        System.getenv("ACCOUNT_STAGING_ACCESS")?.takeIf { it.isNotBlank() }?.let { testInstrumentationRunnerArguments["stagingAccess"] = it }
        // the account test against staging (optional: a device token of a test subscription, CI secret)
        System.getenv("ACCOUNT_STAGING_TOKEN")?.takeIf { it.isNotBlank() }?.let { testInstrumentationRunnerArguments["accountToken"] = it }
    }

    // Two editions of the same app:
    //  direct: the APK on GitHub (release 'app-latest'), updates itself every night (the Update button)
    //  play:   Google Play, its own app ID; updates come from Play, so no self-update and no permission to install APKs
    flavorDimensions += "store"
    productFlavors {
        create("direct") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "STORE_BILLING", "false")
        }
        create("play") {
            dimension = "store"
            applicationId = "com.overpricedcardboard.app"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            // sells the subscription with Play Billing, signs in with Google, downloads the nightly prices
            buildConfigField("boolean", "STORE_BILLING", "true")
        }
    }

    // one APK per processor type: arm64 for phones (the download), x86_64 for the CI emulator
    // (APKs only: Play makes its own per-device split from the bundle)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        // committed key: lets every new APK install over the previous one
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The Play upload key: only from CI secrets, never in the repo (release builds are unsigned without it).
        // PLAY_UPLOAD_KEYSTORE is the path of the keystore file the CI writes from its secret.
        val uploadStore = System.getenv("PLAY_UPLOAD_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.exists() }
        if (uploadStore != null) create("upload") {
            storeFile = uploadStore
            storePassword = System.getenv("PLAY_UPLOAD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PLAY_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("PLAY_UPLOAD_KEY_PASSWORD") ?: System.getenv("PLAY_UPLOAD_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        // the Play bundle (bundlePlayRelease)
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // model and index hardly compress and are copied out once: store them as they are
        noCompress += listOf("onnx", "f16")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // compressed native libraries: a much smaller download (unpacked once at install)
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

androidComponents {
    // only the editions that are used: direct as debug (the APK people install), play as release (the bundle)
    // and debug (to try it on a phone next to the direct one)
    beforeVariants { v ->
        if (v.productFlavors.contains("store" to "direct") && v.buildType == "release") v.enable = false
    }
    onVariants(selector().withFlavor("store" to "play")) { v ->
        // 64-bit only (arm64 phones, x86_64 Chromebooks): the 32-bit libraries aren't tested anywhere
        v.packaging.jniLibs.excludes.addAll(listOf("**/armeabi-v7a/*.so", "**/x86/*.so"))
        // native libraries stored uncompressed: Play compresses the download itself, and they load in place
        v.packaging.jniLibs.useLegacyPackaging.set(false)
        v.packaging.jniLibs.useLegacyPackagingFromBundle.set(false)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    // Webshop Mode: the Pokeshop API (signed HTTPS requests)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // the recording's clean camera file (camera.mp4)
    implementation("androidx.camera:camera-video:$camerax")

    // both with native libraries aligned to 16 KB memory pages (Google Play requires it for Android 15+ phones)
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // on-device text recognition (offline): card number and language
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    // the printed script of Asian cards: Hangul (Korean), and Chinese (traditional / simplified)
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // QR codes for the account: reading (connect a device) and showing (move to a new phone)
    implementation("com.google.zxing:core:3.5.3")
    // recording uploads that go on with the app closed, when the network fits (spec UP-7)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Google Play edition: the subscription and extra devices (add-ons) with Play Billing,
    // Continue with Google (Credential Manager)
    "playImplementation"("com.android.billingclient:billing:8.3.0")
    "playImplementation"("androidx.credentials:credentials:1.5.0")
    "playImplementation"("androidx.credentials:credentials-play-services-auth:1.5.0")
    "playImplementation"("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.services:storage:1.5.0")
    androidTestUtil("androidx.test.services:test-services:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
