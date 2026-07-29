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
        versionCode = 13
        versionName = "0.8.2-hometask"

        ndk {
            // Match the onn box's arm64 chip; armeabi-v7a kept for older Android TV devices.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
}
