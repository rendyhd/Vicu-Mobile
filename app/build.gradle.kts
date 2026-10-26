import java.util.Properties

plugins {
    // Kotlin support is built into the Android application plugin since AGP 9.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// One version for Android and iOS, kept in version.properties at the repository root.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "com.rendyhd.vicu"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.rendyhd.vicu"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Local speech (native/speech, whisper.cpp). Built for the processors phones and the
        // emulators have; the abiFilters here only choose what CMake builds, not what is packaged,
        // so the other libraries' 32-bit x86 copies stay in the APK and an x86 device still
        // installs it (without local speech: AndroidSpeechEngineNative.isLoaded is false). The
        // native code is always optimized, also in the debug app: unoptimized whisper.cpp is far
        // too slow to try out.
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DVICU_SPEECH_SHARED=ON",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    // The NDK and CMake the native build uses (CI installs these two, see .github/workflows).
    ndkVersion = "27.1.12297006"
    externalNativeBuild {
        cmake {
            path = file("../native/speech/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The bundled Silero voice detector (speech.vad in test-fixtures/ai-tools-v1.json), copied out
    // of the assets on first use (AndroidSpeechStorage).
    sourceSets.getByName("main").assets.directories.add("../native/speech/models")

    if (keystorePropsFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Separate applicationId so debug installs side-by-side with the
            // release/beta build instead of hitting INSTALL_FAILED_UPDATE_INCOMPATIBLE.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // The Koin graph test builds the Android-only classes against the stub android.jar.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":shared"))
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.workmanager)

    // Glance (Widgets)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Coil (Image Loading)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Auth
    implementation(libs.tink.android)
    implementation(libs.kotlinx.datetime)

    // Reorderable (drag-and-drop)
    implementation(libs.reorderable)

    // Location reminders: geofencing (with a fallback to Android's proximity alerts without it)
    implementation(libs.play.services.location)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // KoinGraphTest builds the generated Room database class, whose supertype lives here.
    testImplementation(libs.room.runtime)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
