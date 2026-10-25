plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "Thanh.TaxiDriver.AALauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "Thanh.TaxiDriver.AALauncher"
        minSdk = 30
        targetSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // This repository has a fresh GitHub Actions run counter. Use a new
        // monotonically higher range for this new AA Taxi Driver app identity.
        // This package ID differs from legacy builds: it installs separately
        // and Android does not migrate private app data automatically.
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

    // Phase 11A: fail build on Android Lint errors (without disabling checks).
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        htmlReport = true
        xmlReport = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.car.app:app:1.4.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    // Media3 1.4.1 is compatible with this project's compileSdk 34.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    testImplementation("junit:junit:4.13.2")
    // Android ships org.json at runtime; local JVM integration tests require the real parser.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
