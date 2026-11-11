plugins {
    id("com.android.application")
    // Required so Android Studio's prepareKotlinBuildScriptModel task exists on this
    // module (Kotlin sources without a Kotlin plugin break IDE sync).
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jbr1.carlinkit.hudicons"
    compileSdk = 37

    defaultConfig {
        // Sideload-only companion. Never upload this package to Play — it claims the
        // reserved GM Templates Host cluster-icon authority that Play locks to the first
        // publisher (upstream zeno.carlink / issue #6).
        applicationId = "jbr1.carlinkit.hudicons"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Optional release signing via the same CARLINK_KEYSTORE_* env vars as :app.
    // Local builds without the env still produce an (unsigned) release APK.
    val releaseKeystoreFile: String? = System.getenv("CARLINK_KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (releaseKeystoreFile != null) {
                storeFile = file(releaseKeystoreFile)
                storePassword = System.getenv("CARLINK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CARLINK_KEY_ALIAS")
                keyPassword = System.getenv("CARLINK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
