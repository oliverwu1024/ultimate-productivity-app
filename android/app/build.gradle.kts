import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    // §9.8 — Generates the Firebase init resources from google-services.json.
    // Must be listed AFTER com.android.application.
    id("com.google.gms.google-services")
}

val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.ultiq.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ultiq.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 106
        versionName = "2.16.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            keystoreProps.getProperty("storeFile")?.let { storeFile = rootProject.file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // §dogfood — install side-by-side with the production APK.
            // Different applicationId = separate app on device with separate
            // data / token store / Room DB; the "Ultiq Debug" label makes the
            // two distinguishable in the launcher and in Settings → Apps.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Ultiq Debug")
            buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.119:8080/\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "API_BASE_URL", "\"https://api.ultiqapp.com/\"")
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else null
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
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-sse:5.3.2")

    // DataStore (still used by other prefs; auth tokens moved to EncryptedSharedPreferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Encrypted prefs for auth tokens — Keystore-backed AEAD via Tink.
    implementation("androidx.security:security-crypto:1.1.0")

    // SQLCipher: encrypts the entire Room DB file at rest. Passphrase is stored
    // in EncryptedSharedPreferences (Keystore-rooted), so it never lives in
    // plaintext on disk.
    implementation("net.zetetic:sqlcipher-android:4.16.0")
    implementation("androidx.sqlite:sqlite:2.6.2")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Vico charting
    implementation("com.patrykandpatrick.vico:compose-m3:2.0.0-beta.3")

    // CameraX — used by Phase 8 §8.9 photo dismiss mission.
    val cameraX = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Firebase Cloud Messaging — §9.8 anomaly detection push channel. BoM
    // pins all Firebase artefact versions to one tested set; only request
    // the messaging library (we don't use Auth, Firestore, Crashlytics, etc).
    // BoM 34.x merged the `-ktx` Kotlin extensions into the main artefact,
    // so we depend on `firebase-messaging` (no -ktx suffix).
    implementation(platform("com.google.firebase:firebase-bom:34.13.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Lets us `.await()` Firebase's Task<T> results inside suspend functions
    // (Firebase's Android APIs are Task-based; coroutines play-services adds
    // the bridge). Used by FcmTokenSyncer.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // §10 — MediaPipe Audio Classifier with bundled YAMNet model for on-device
    // snore + cough detection during sleep sessions. Audio never leaves the
    // phone; only labels + timestamps + confidences are persisted. Model file
    // `yamnet.tflite` must live at `app/src/main/assets/yamnet.tflite`.
    implementation("com.google.mediapipe:tasks-audio:0.10.21")
}
