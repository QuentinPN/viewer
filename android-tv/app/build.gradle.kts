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
        versionCode = 10
        versionName = "1.5.0"
        // Only arm64-v8a libmpv is fetched
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        // Its own app next to the release: own data, session and Emby device, so
        // tests run on another account and never replace the owner's install
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Viewer Debug"
            // Test channel of the updater
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"http://git.example.com/QuentinPN/Viewer-releases/raw/branch/main/android-debug/latest.json\"")
        }
        release {
            manifestPlaceholders["appLabel"] = "Viewer"
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"http://git.example.com/QuentinPN/Viewer-releases/raw/branch/main/android/latest.json\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // A few lines of C that keep libmpv's verbose log out of logcat
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
