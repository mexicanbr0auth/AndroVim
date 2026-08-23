plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androvim"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androvim"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (System.getenv("ANDROVIM_KS_PATH") != null) {
            create("ci") {
                storeFile = file(System.getenv("ANDROVIM_KS_PATH"))
                storePassword = System.getenv("ANDROVIM_KS_PASS")
                keyAlias = System.getenv("ANDROVIM_KS_ALIAS")
                keyPassword = System.getenv("ANDROVIM_KS_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("ANDROVIM_KS_PATH") != null)
                signingConfigs.getByName("ci") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")
}
