import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release key is never in the repository. CI writes it from secrets into a file named by
// LEMBAS_KEYSTORE; a build without one is unsigned (debug builds use the debug key as always).
val releaseKey: Properties? =
    System.getenv("LEMBAS_KEYSTORE")?.takeIf { it.isNotBlank() }?.let {
        Properties().apply {
            setProperty("file", it)
            setProperty("password", System.getenv("LEMBAS_KEYSTORE_PASSWORD") ?: "")
            setProperty("alias", System.getenv("LEMBAS_KEY_ALIAS") ?: "release")
            setProperty("keyPassword", System.getenv("LEMBAS_KEY_PASSWORD") ?: "")
        }
    }

android {
    namespace = "eu.llembas.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.llembas.android"
        minSdk = 29
        targetSdk = 36
        // The one source of the version. versionCode is derived from it, so the two cannot
        // drift, and a beta sorts below its release: 1.0.0-beta.1 -> 1000001,
        // 1.0.0-beta.2 -> 1000002, 1.0.0 -> 1000099, 1.2.3 -> 1020399.
        versionName = "1.0.0-beta.4"
        versionCode = versionName!!.split("-beta.").let { parts ->
            val (a, b, c) = parts[0].split(".").map { it.toInt() }
            val pre = if (parts.size == 2) parts[1].toInt() else 99
            ((a * 10000 + b * 100 + c) * 100) + pre
        }
    }

    signingConfigs {
        // Debug builds are signed with a *public* test key that lives in the repository, so the
        // APK every CI run uploads installs over the one before it (a runner's own debug key is
        // new each run, and Android refuses an update from a different signer). It signs only
        // the `.debug` application id, never a release, and it is no secret: anyone can sign
        // with it, so install a debug build only from this repository's CI.
        getByName("debug") {
            storeFile = file("test-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        releaseKey?.let { key ->
            create("release") {
                storeFile = file(key.getProperty("file"))
                storePassword = key.getProperty("password")
                keyAlias = key.getProperty("alias")
                keyPassword = key.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            // CI's run number, so a tester can tell two test builds apart in Settings › About.
            versionNameSuffix = "-debug" + (System.getenv("GITHUB_RUN_NUMBER")?.let { ".$it" } ?: "")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // `full` updates itself from llembas.eu; `fdroid` has no updater, because F-Droid updates it.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        // No Google-signed dependency blob in the APK: F-Droid refuses it, and nobody reads it.
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // The screenshot tests (`shots/`) render the screens on the JVM with Robolectric, and
        // Roborazzi writes each to app/build/screenshots/ -- recorded every run, never compared:
        // they are for looking at, and CI uploads them. Nothing of this reaches the APK.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("roborazzi.test.record", "true")
            test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            test.maxHeapSize = "3g"
            // Name each test as it starts, so a run that stops in one says which.
            test.testLogging {
                events("started", "passed", "failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            test.timeout.set(Duration.ofMinutes(20))
        }
    }

    sourceSets {
        // The server's contract, pinned by scripts/fetch-contract.py, read by the unit tests.
        getByName("test").resources.srcDir(rootProject.file("contract"))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.markwon.core)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.strikethrough)
    implementation(libs.unifiedpush.connector)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
