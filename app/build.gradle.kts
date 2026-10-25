plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cartaxi.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cartaxi.launcher"
        minSdk = 30
        targetSdk = 33
        // This repository has a fresh GitHub Actions run counter. Use a new
        // monotonically higher range to avoid downgrading legacy CarTaxiLauncher APKs.
        // Updating an installed app still requires the exact same signing certificate.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
        versionCode = 100_000 + run
        versionName = "1.0.$run"
    }

    // Never configure production signing from a checked-in private key.
    val keystorePath = System.getenv("CARTAXI_KEYSTORE_FILE")?.takeIf(String::isNotBlank)
    val keystorePassword = System.getenv("CARTAXI_KEYSTORE_PASSWORD")?.takeIf(String::isNotBlank)
    val signingReady = keystorePath != null && keystorePassword != null

    signingConfigs {
        if (signingReady) {
            create("cartaxiRelease") {
                storeFile = file(keystorePath!!)
                storeType = "pkcs12"
                storePassword = keystorePassword
                keyAlias = "cartaxi"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Standard Android debug keystore; never used for release publishing.
        }
        getByName("release") {
            if (signingReady) {
                signingConfig = signingConfigs.getByName("cartaxiRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.car.app:app:1.4.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    testImplementation("junit:junit:4.13.2")
}
