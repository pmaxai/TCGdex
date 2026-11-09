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
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pmaxai.cardscanner"
        minSdk = 26
        targetSdk = 35
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
        // Licence: the app asks for one only when this is on (gradle.properties), i.e. once the
        // license service runs. The service's address can be changed the same way.
        val licenceRequired = providers.gradleProperty("cardscanner.licenceRequired").orNull == "true"
        val licenceUrl = providers.gradleProperty("cardscanner.licenceUrl").orNull ?: "https://license.pokemonkarten.at"
        buildConfigField("boolean", "LICENSE_REQUIRED", licenceRequired.toString())
        buildConfigField("String", "LICENSE_URL", "\"$licenceUrl\"")
    }

    // one APK per processor type: arm64 for phones (the download), x86_64 for the CI emulator
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
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
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

    implementation("org.opencv:opencv:4.10.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // on-device text recognition (offline): card number and language
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // QR codes for the licence: reading (activation) and showing (switch to new device)
    implementation("com.google.zxing:core:3.5.3")
    // recording uploads that go on with the app closed, when the network fits (spec UP-7)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.services:storage:1.5.0")
    androidTestUtil("androidx.test.services:test-services:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
