# Build guide

## Toolchain

- JDK 17
- Android SDK platform 35
- Android NDK 28.2.13676358
- CMake 3.22.1
- Included Gradle 8.11.1 wrapper

The root application module uses Kotlin 2.1.0, Android Gradle Plugin 8.7.3 and Jetpack Compose. `settings.gradle.kts` includes the `:whisper` native wrapper module.

## Prepare native dependencies

From the project root in Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File tools\fetch-engines.ps1
```

The supplied script downloads the pinned llama.cpp and whisper.cpp archives from GitHub, verifies their SHA-256 hashes and unpacks the build sources under `third_party/`. These generated dependency trees are excluded from version control. The pins and hashes live in the script.

The native libraries are built for `arm64-v8a`, CPU-only, with ARMv8.2 dot-product and FP16 instructions. Each engine links its own ggml statically with hidden symbols. The app loads speech and text/vision models separately to avoid keeping both in memory together.

## Build

```powershell
.\gradlew.bat assembleDebug
```

Debug APKs are written beneath `build/outputs/apk/debug/`. Configure your SDK through Android Studio, `ANDROID_HOME`, or a local `local.properties`; do not commit your machine's SDK paths.

To run the JVM tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

These are build commands, not a claim that the release has been reproduced on every toolchain or device.

## Sign your own release

Create your own Android signing key and keep a private backup. Configure a local `keystore.properties` file beside `build.gradle.kts` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, then run:

```powershell
.\gradlew.bat assembleRelease
```

Never commit the key, signing properties, passwords or machine-local files. Your build will have your signature; it cannot update an existing installation signed by a different key. Do not remove your only copy of a signing key that you need for future app updates.

## Project layout

- `src/main/java/com/craft/capturespace/`: app, UI, local storage, capture/import and AI pipeline.
- `src/main/cpp/`: llama.cpp / libmtmd JNI bridge.
- `whisper/`: whisper.cpp JNI bridge and Android library module.
- `src/test/`: JVM tests for text processing, search, document handling and link metadata.
- `src/androidTest/`: device instrumentation source.
- `tools/fetch-engines.ps1`: pinned dependency fetcher.
- `NOTICES` and `src/main/assets/NOTICES.txt`: third-party terms.

Model weights are not included in the source repository or APK. The app downloads or imports the three exact files described in [Installation](INSTALL.md).
