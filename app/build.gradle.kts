plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.wakebrief"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wakebrief"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Real phones plus the x86_64 emulator; 32-bit x86 would only add ~37 MB.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // Compress the voice engine's native libraries: a far smaller APK to download.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // On-device AI voices: sherpa-onnx (Apache-2.0), the .aar from its GitHub release v1.13.8.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // The .aar's classes are written in Kotlin and need its runtime.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    // Unpacks the voice packs (.tar.bz2) while they download.
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Material 3 time picker (MaterialTimePicker), the views counterpart of Compose's TimePicker.
    implementation("com.google.android.material:material:1.14.0")
}
