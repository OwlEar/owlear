plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.owlear.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.owlear.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    // Release signing key comes from CI secrets only; it is never committed.
    // Every public APK must be signed with this same key so updates install in place.
    val keystorePath: String? = System.getenv("OWLEAR_KEYSTORE_PATH")
    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("OWLEAR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OWLEAR_KEY_ALIAS")
                keyPassword = System.getenv("OWLEAR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party libraries on purpose: nothing to phone home, nothing to audit but this repo.
