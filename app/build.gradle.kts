import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.wmc.mediacenter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wmc.mediacenter"
        minSdk = 26
        targetSdk = 34
        versionCode = 39
        versionName = "0.9.25-screensaver"

        ndk {
            // Match the onn box's arm64 chip; armeabi-v7a kept for older Android TV devices.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // R8 was shrinking code but not resources, so the APK shipped
            // every unused drawable/string the dependencies bring along.
            // Enabling this is what lint's NotShrinkingResources asks for.
            //
            // Safe here because nothing is looked up reflectively by name:
            // the one indirect reference is the screensaver's
            // @xml/photo_wall_dream, reached through a manifest <meta-data>
            // element, and the shrinker parses the manifest as a root. Worth
            // knowing that a dream whose meta-data resource goes missing
            // fails SILENTLY (see PhotoWallDreamService) — so if resources
            // ever do get over-shrunk, the symptom is the screensaver simply
            // never appearing in the system list, with nothing in logcat.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the debug key so Run/adb-install works directly —
            // fine for a personal sideloaded app, and it makes testing the
            // fast (optimized) build one click instead of a keystore setup.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true // exposes BuildConfig.VERSION_NAME for the Settings screen
    }

    // No composeOptions/kotlinCompilerExtensionVersion block needed: with Kotlin 2.x,
    // the Compose compiler is configured by the org.jetbrains.kotlin.plugin.compose
    // plugin applied above instead.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// Kotlin 2.x replacement for the old android.kotlinOptions { jvmTarget = "17" } block.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation") // Crossfade, for P5's screen-transition fade

    // Compose for TV — TV-styled Material3 (Text, MaterialTheme, colorScheme, etc.).
    // tv-foundation is no longer needed: its TvLazyRow/TvLazyColumn were removed
    // upstream once regular Compose Foundation LazyRow/LazyColumn gained the same
    // D-pad focus-positioning behavior built in (Compose Foundation 1.7.0+).
    implementation("androidx.tv:tv-material:1.1.0")

    // P2: row config persistence.
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // S35 — screensaver: EXIF capture-date reads, and an explicit
    // savedstate dependency (setViewTreeSavedStateRegistryOwner) rather than
    // leaving it to resolve transitively.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
}
