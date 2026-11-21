import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.androidx.baselineprofile)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Секреты release-подписи лежат ВНЕ проекта (C:\Users\Xotabl4\Desktop\apk\keys\keystore.properties).
// Нет файла — release собирается debug-ключом, чтобы проект оставался собираемым на другой машине.
val keystorePropsFile = rootProject.file("../keys/keystore.properties")
val keystoreProps =
  Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
  }

android {
  namespace = "org.xotabl4.ytdlpgui"
  compileSdk = 36
  defaultConfig {
    applicationId = "org.xotabl4.ytdlpgui"
    minSdk = 24
    targetSdk = 36
    versionCode = 12
    versionName = "0.5.2"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // youtubedl-android поставляет Python/yt-dlp/ffmpeg только под эти ABI.
    // Телефон (Redmi Note 10 Pro) — arm64-v8a; остальные отбрасываем ради размера APK.
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  signingConfigs {
    if (keystorePropsFile.exists()) {
      create("release") {
        storeFile = file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
        // Подпись v2 обязательна для API 24+; v3 — для смены ключа в будущем.
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    debug {
      // Debug-сборка подписывается тем же ключом, что и release: иначе `connectedDebugAndroidTest`
      // не может поставить тестовый APK поверх установленного release (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
      signingConfig =
        if (keystorePropsFile.exists()) signingConfigs.getByName("release")
        else signingConfigs.getByName("debug")
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Свой keystore, если он есть; иначе — отладочный ключ (только для локальных проверок).
      signingConfig =
        if (keystorePropsFile.exists()) signingConfigs.getByName("release")
        else signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // Python/ffmpeg .so из youtubedl-android должны распаковываться при установке (extractNativeLibs).
    jniLibs {
      useLegacyPackaging = true
    }
  }
}

kotlin {
  // JDK берётся из JBR Android Studio (Java 25); jvmTarget по умолчанию = compileOptions.targetCompatibility (17).
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
  }
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
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // yt-dlp (Python + yt-dlp внутри AAR). ffmpeg едет отдельно — assets/ffmpeg9 (см. tools/fetch_ffmpeg.py)
  implementation(libs.youtubedl.library)
  // xz-декодер для распаковки Termux .deb при обновлении ffmpeg
  implementation(libs.xz)
  // Baseline Profile: установка профиля в релизной сборке
  implementation(libs.androidx.profileinstaller)
  baselineProfile(project(":baselineprofile"))
}
