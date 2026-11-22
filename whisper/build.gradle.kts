// Speech engine: whisper.cpp (pinned, see ../third_party/ENGINES.md) built as its own native
// library, liboe_whisper.so, with its own ggml statically linked and hidden, so it can't clash
// with the ggml inside liboe_llama.so. The Kotlin side is com.craft.capturespace.ai.NativeWhisper.
plugins {
    // No version here: the app's build already brings Android Gradle Plugin 8.7.3 (when the app
    // folder is built on its own), and the project folder's settings give it 8.7.3 otherwise.
    id("com.android.library")
}

android {
    namespace = "com.craft.capturespace.whisper"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a")
                arguments("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
