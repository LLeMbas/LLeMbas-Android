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
        // drift: 0.1.0 -> 100, 1.2.3 -> 10203.
        versionName = "0.1.0"
        versionCode = versionName!!.split(".").map { it.toInt() }.let { (a, b, c) ->
            a * 10000 + b * 100 + c
        }
    }

    signingConfigs {
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
            versionNameSuffix = "-debug"
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
}
