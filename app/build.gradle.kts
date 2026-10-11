/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// Release signing is configured only when keystore.properties exists. Every
// release MUST be signed with the same key for in-place self-update to work, so
// guard these files carefully. The canonical home is ~/.immortal-signing/ —
// OUTSIDE the repo, so no git clean/restore/checkout can ever delete it. A copy
// at the repo root (git-ignored) still works and takes precedence if present.
// storeFile is resolved relative to whichever keystore.properties was found.
val keystorePropsFile =
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: File(System.getProperty("user.home"), ".immortal-signing/keystore.properties")
val keystoreProps =
    Properties().apply { if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use(::load) }

android {
  namespace = "com.immortal.launcher"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.immortal.launcher"
    minSdk = 24
    targetSdk = 36
    versionCode = 58
    versionName = "1.64"
    testInstrumentationRunner = "com.immortal.launcher.PortalUxInstrumentation"

    // Controlled A/B candidate; normal builds keep WebView preparation dormant.
    buildConfigField("boolean", "PORTAL_WEBVIEW_BACKGROUND_STARTUP",
        (providers.gradleProperty("portalWebViewBackgroundStartup").orNull == "true").toString())

    // Opt-in Family-only compilation experiment, pinned to one APK; other installs allow rollback.
    val selfProfileEnabled = providers.gradleProperty("portalSelfInstallDexMetadata").orNull == "true"
    val selfProfileTarget = if (selfProfileEnabled)
        providers.gradleProperty("portalSelfInstallDexMetadataApkSha256").orNull.orEmpty() else ""
    if (selfProfileEnabled && !selfProfileTarget.matches(Regex("[a-f0-9]{64}"))) {
      throw GradleException("The self-install profile experiment requires a lowercase SHA-256 APK target")
    }
    buildConfigField("boolean", "PORTAL_SELF_INSTALL_DEX_METADATA", selfProfileEnabled.toString())
    buildConfigField("String", "PORTAL_SELF_INSTALL_DEX_METADATA_APK_SHA256", "\"$selfProfileTarget\"")

    // Filmstrip passed Family Area timing/compatibility gates; false keeps the legacy escape hatch.
    buildConfigField("boolean", "PORTAL_NATIVE_FILMSTRIP",
        ((providers.gradleProperty("portalNativeFilmstrip").orNull ?: "true") == "true").toString())
    // Native Home has not met its timing gate and remains opt-in for controlled experiments.
    buildConfigField("boolean", "PORTAL_NATIVE_HOME_TABS",
        (providers.gradleProperty("portalNativeHomeTabs").orNull == "true").toString())

    // Fixed-control functional probes stay dormant in production variants.
    buildConfigField("boolean", "PORTAL_HOME_UI_DIAGNOSTICS", "false")
    buildConfigField("boolean", "PORTAL_SETTINGS_UI_DIAGNOSTICS", "false")
    buildConfigField("boolean", "PORTAL_SETTINGS_PANE_DIAGNOSTICS", "false")

    // Bounded Appearance rows avoid nested lazy-layout work on Portal hardware.
    buildConfigField("boolean", "PORTAL_SETTINGS_DIRECT_APPEARANCE_ROWS", "true")

    // Portal hardware is ARM only (gen-1/gen-2 Snapdragon, Portal TV Amlogic).
    // Kept as a guard so any future native dependency can't drag x86/x86_64 .so
    // files no Portal can run into the APK.
    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }
  }

  signingConfigs {
    if (keystorePropsFile.exists()) {
      create("release") {
        storeFile = File(keystorePropsFile.parentFile, keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (keystorePropsFile.exists()) {
        signingConfig = signingConfigs.getByName("release")
      } else if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
        // Every Immortal release MUST be signed with the same key for in-place
        // self-update to work. Without keystore.properties the release would be
        // unsigned (or debug-signed), which SILENTLY breaks self-update on devices
        // (signature mismatch). Fail loudly instead of shipping a mis-signed build.
        throw GradleException(
            "Release build requires signing but no keystore.properties was found " +
                "(looked at ${keystorePropsFile.path}). Provide it (see the signing " +
                "comment above) or build a debug variant — refusing to produce an " +
                "unsigned/mis-signed release.")
      }
    }
    debug {
      // Lets a debug build install alongside a provisioned release for testing.
      applicationIdSuffix = ".debug"
    }
    // Measure release rendering without replacing a provisioned release or losing the
    // debug device's configuration. Keep release optimization options for a fair comparison;
    // changing them belongs in a separately measured candidate.
    create("benchmark") {
      initWith(getByName("release"))
      // Benchmark-only test access; Fleet auth, Dev Mode, Window and collector guards remain.
      buildConfigField("boolean", "PORTAL_HOME_UI_DIAGNOSTICS", "true")
      // Functional observation has separate opt-in; never use it for timing comparisons.
      buildConfigField("boolean", "PORTAL_SETTINGS_UI_DIAGNOSTICS",
          (providers.gradleProperty("portalSettingsUiDiagnostics").orNull == "true").toString())
      // Work attribution only; disabled for ordinary and final speed-comparison builds.
      buildConfigField("boolean", "PORTAL_SETTINGS_PANE_DIAGNOSTICS",
          (providers.gradleProperty("portalSettingsPaneDiagnostics").orNull == "true").toString())
      buildConfigField("boolean", "PORTAL_SETTINGS_DIRECT_APPEARANCE_ROWS",
          (providers.gradleProperty("portalSettingsDirectAppearanceRows").getOrElse("true") == "true").toString())
      applicationIdSuffix = ".debug"
      isDebuggable = false
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += "release"
      // Opt-in candidate: compare with the faithful baseline before changing release.
      if (providers.gradleProperty("portalBenchmarkOptimize").orNull == "true") {
        isMinifyEnabled = true
        proguardFiles("benchmark-rules.pro")
      }
    }
    // Release-faithful iteration build. Same applicationId + same signing key + minify off
    // (inherited from release via initWith), so it provisions identically — home role, device
    // admin, and self-update signature checks all behave exactly as on a release build. The ONLY
    // difference is `debuggable = true`, which lets `adb install -r -d` freely replace or downgrade
    // it. That sidesteps the two walls a pure release build hits on a dev device: version-code
    // downgrades (a feature branch is always a lower code) and the device admin blocking uninstall.
    // Use for on-device iteration; validate the true `release` artifact before shipping.
    create("dev") {
      initWith(getByName("release"))
      isDebuggable = true
      matchingFallbacks += "release"
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
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  // Stable background-only startup API; minSdk 24, with supported legacy-provider fallback.
  implementation("androidx.webkit:webkit:1.17.1")
  // Smooth drag-to-reorder + animated reflow for the home-screen app grid (Apache-2.0).
  implementation(libs.reorderable)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // SMB/CIFS client for the "network share" screensaver source (smbj — SMB 2/3, incl. 3.1.1).
  // minify is off, so no R8 keep rules are needed. slf4j-nop silences its logging facade.
  implementation("com.hierynomus:smbj:0.13.0")
  runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

  // QR encoder for the "Set up from your phone" screen (scan the LAN address). Core only — we
  // render the BitMatrix to a Bitmap ourselves, no Android-specific zxing module needed.
  implementation("com.google.zxing:core:3.5.3")

  // ML Kit face detection (bundled model — no Play Services on a provisioned Portal).
  // Feeds FaceFocusStore: one small decode per photo, cached focal points so screensaver
  // crops/pans keep faces in frame ("fast" mode, boxes only — no landmarks/classification).
  implementation("com.google.mlkit:face-detection:16.1.7")

  // Media3 Transformer: on-device, hardware-accelerated video downscale/transcode for the
  // screensaver media cache ([VideoTranscoder]) — turns near-original remote clips into
  // 1200x800 derivatives the Portal stores and replays locally. -effect provides Presentation
  // (aspect-fit resize); -common carries MediaItem etc.
  implementation("androidx.media3:media3-transformer:1.5.1")
  implementation("androidx.media3:media3-effect:1.5.1")
  implementation("androidx.media3:media3-common:1.5.1")

  // Unit tests (pure JVM — no device/emulator). org.json provides a real
  // implementation so JSON-parsing logic can be tested off-device (the android.jar
  // stub used in unit tests otherwise throws "not mocked").
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
  // Pure-JVM mock of Context so the registry's apply/onApplied path is unit-testable without
  // Robolectric (no instrumented android-all for compileSdk 36 / AGP 9 yet). The Context is only
  // passed through to setters, so a mock that's never dereferenced is enough.
  testImplementation("org.mockito:mockito-core:5.11.0")
}
