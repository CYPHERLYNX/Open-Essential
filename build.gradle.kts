import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

// The release signing key lives next to this file and is created locally
// (see INSTALL.md). Its passwords are read from keystore.properties, which
// is never part of the app itself.
val signingProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Optional: build outside a synced folder (OneDrive can lock build files).
(findProperty("csBuildDir") as String? ?: System.getenv("CS_BUILD_DIR")
    ?: if (projectDir.absolutePath.contains("OneDrive", ignoreCase = true))
        File(System.getProperty("java.io.tmpdir"), "capturespace-build").path else null
)?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "com.craft.capturespace"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.craft.capturespace"
        minSdk = 26
        targetSdk = 35
        // Own identity and versioning, independent of the original app's. Raise versionCode for
        // every release so updates install over the previous one and keep its data.
        versionCode = 7
        versionName = "1.5.0"
        // Measures the rules in mechanics.json inside the installed app (see tools/measure-mechanics.ps1).
        testInstrumentationRunner = "com.craft.capturespace.MeasureRules"
        // On-device engine (llama.cpp + mtmd), CPU only, 64-bit ARM phones only. Other processors
        // (such as an x86 emulator) still install the app; it then says the engine isn't available.
        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a")
                arguments("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags("-O3")
            }
        }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
    // Measure the release build itself, signed with the same key.
    testBuildType = "release"

    signingConfigs {
        create("release") {
            if (signingProps.getProperty("storeFile") != null) {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingProps.getProperty("storeFile") != null) {
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(project(":whisper"))
    // PDF text extraction (Apache License 2.0); scanned pages go through the picture path instead.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
}
