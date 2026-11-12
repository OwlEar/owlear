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
        versionCode = 9
        versionName = "0.5.2"
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
        // Optional fixed test key (private dev repo only), so each "OwlEar Test" build
        // installs over the last. Uses the standard test-key password and alias.
        System.getenv("OWLEAR_TEST_KEYSTORE_PATH")?.let { testKey ->
            getByName("debug") {
                storeFile = file(testKey)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        // Test builds (debug) install beside the real app as "OwlEar Test" instead of
        // clashing with it: they're signed with a throwaway key, and Android refuses to
        // update an app with a different key. Mechanic version: a loaner car with its own
        // keys, parked next to yours, so you never have to give up your own to try it.
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
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
