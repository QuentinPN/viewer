plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.quentinpn.viewer.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.quentinpn.viewer.tv"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Only arm64-v8a libmpv is fetched (ADR 0038)
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal use only: the release build is signed with the debug key
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // The prebuilt mpv-android libraries are already stripped
        jniLibs.keepDebugSymbols += "**/*.so"
    }
}

dependencies {
    testImplementation(libs.junit)
}
