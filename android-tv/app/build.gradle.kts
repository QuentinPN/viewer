plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.quentinpn.viewer.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.quentinpn.viewer.tv"
        minSdk = 28
        targetSdk = 37
        versionCode = 5
        versionName = "1.0.3"
        // Only arm64-v8a libmpv is fetched (ADR 0038)
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        // Its own app next to the release (ADR 0102): own data, session and Emby device, so
        // tests run on another account and never replace the owner's install
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Viewer Debug"
        }
        release {
            manifestPlaceholders["appLabel"] = "Viewer"
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

    buildFeatures { compose = true }

    // A few lines of C that keep libmpv's verbose log out of logcat (ADR 0052)
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    packaging {
        // The prebuilt mpv-android libraries are already stripped
        jniLibs.keepDebugSymbols += "**/*.so"
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
