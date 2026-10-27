plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  id("kotlin-kapt")
}

android {
    namespace = "com.mediaconverter.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mediaconverter.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.1.6"

        ndk {
            // Intentional distribution policy: ship only to 64-bit ARM devices.
            // The debug build type below adds x86_64 so hosted CI emulators can run
            // the native conversion stack; release stays arm64-v8a only.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            ndk {
                // GitHub-hosted runners cannot boot arm64 emulators (the Apple-silicon macOS
                // runners are VMs without nested virtualization), so CI runs x86_64 emulators
                // on Linux with KVM. Both native dependencies ship x86_64 libs; ffmpeg-kit-full
                // 8.1.7 ships no armeabi-v7a or x86, so this is the only extra ABI possible.
                // Build-type filters are unioned with defaultConfig: debug APKs carry
                // arm64-v8a + x86_64, release APKs are unaffected.
                abiFilters += "x86_64"
            }
        }
        release {
            // Distribution stays ARM64 only; CI verifies the release APK's lib/ directories.
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      buildConfig = true
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      jniLibs {
        useLegacyPackaging = true
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.animation)

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.org.json)

  // Instrumented tests
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.work.testing)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Room
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  kapt(libs.androidx.room.compiler)

  // WorkManager
  implementation(libs.androidx.work.runtime.ktx)

  // yt-dlp Android wrapper
  implementation(libs.youtubedl.android.library)
  implementation(libs.ffmpeg.kit.full)
  implementation(libs.smart.exception.java)

  // Kotlinx Serialization
  implementation(libs.kotlinx.serialization.json)

  // DataStore
  implementation(libs.androidx.datastore.preferences)
}
