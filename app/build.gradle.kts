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
        // targetSdk < 29 keeps exec() allowed on app-writable storage, which is
        // what lets runtime-installed tools (git, python, node...) run.
        targetSdk = 28
        versionCode = 6
        versionName = "0.2.3"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        val nvimVersionFile = file("src/main/assets/runtime/androvim-version")
        val nvimVersion = if (nvimVersionFile.exists()) nvimVersionFile.readText().trim() else "dev"
        buildConfigField("String", "NVIM_VERSION", "\"$nvimVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // targetSdk 28 is intentional: it keeps exec() allowed on app-writable
        // storage so runtime-installed tools work. Not distributed via Play.
        disable += "ExpiredTargetSdkVersion"
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
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("org.tukaani:xz:1.9")
}
