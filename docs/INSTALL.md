# Installation and device setup

## Install the app

Download `OpenEssential-release.apk` from the repository's Releases page and open it on your Android phone. If prompted, allow your file manager to install apps from that source. No account or AI API key is needed.

App identity: `com.craft.capturespace`. The supplied release is version **1.5.0**, version code **7**. Updates must have the same package and signing key to install over an existing copy while keeping its data.

## Device requirements

- Android **8.0 / API 26** or later for installation.
- On-device AI: **arm64-v8a**, with ARM dot-product and FP16 instructions. The app checks CPU capabilities before loading its native engines.
- Whole-screen capture: Android **11 / API 30** or later, with the optional accessibility shortcut enabled.
- About **928 MB** for the three model files, plus storage for captures and working files. Runtime memory and response time depend on the device and input.
- The current app pipeline transcribes and analyzes in **English**.

Android version alone does not establish AI compatibility. Use the in-app engine check on the phone you intend to use.

## Set up local AI

Open **Settings → On-device AI**. Download all three model files, or import the exact files from local storage:

| File | Purpose |
| --- | --- |
| `Qwen3.5-0.8B-Q4_K_M.gguf` | Text and vision model |
| `mmproj-F16.gguf` | Matching vision projector |
| `ggml-small-q5_1.bin` | Whisper small speech model |

The model manifest totals **927,589,839 bytes**. Each download/import is checked against its expected byte count and SHA-256. Wi-Fi-only downloads are enabled by default. A user-started download has a progress notification and can be cancelled or resumed.

Run **Check the engine** after setup. The check is specific to that phone and app version. AI results are generated suggestions; review summaries, transcripts, dates and tasks against the original capture before relying on them.

## Capture shortcuts

You can use the share sheet, in-app capture menu, Quick Settings tile, optional notification or floating button.

For the volume-key shortcut, open **Settings → Capture shortcuts** and enable **Open Essential buttons** in Android Accessibility settings. The service handles volume-down presses and takes a screenshot only when you trigger a capture. The active app name may be stored with a capture.

On Android 13+, sideloaded apps may show **Restricted setting** when enabling accessibility. After Android blocks the first attempt, open the app's Android **App info → menu → Allow restricted settings**, confirm on your phone, then return to Accessibility settings. Phone makers may place these controls differently.

The power-button route uses Android's default-assistant setting. Select Open Essential as the assistant where your phone supports that setting, then choose the desired assistant action in the app. It does not remap every manufacturer's power button automatically.

## Documents and links

Supported document parsers: PDF, DOCX, XLSX, PPTX, TXT, Markdown, CSV, HTML and JSON. Convert older DOC/XLS/PPT files to their newer formats before importing.

For PDFs with embedded text, extraction reads up to the first 60 pages. A scanned PDF uses an image of its first page. Very large files are bounded before analysis.

Shared social posts, reels and web pages are saved as link captures. Public preview retrieval is optional and consent-controlled. It can retrieve a page title, description and thumbnail when the source makes them available; it is not a promise to download or transcribe every platform's video. Private or login-only content is not fetched through an authenticated social account.

## Permissions and data

Microphone access is needed for recording. Notifications support recording/download status, shortcuts and reminders. Accessibility enables optional volume-button capture; exact-alarm access supports reminders. Media-location permission can preserve location metadata from a selected original photo. Enable only the features you want.

See [Privacy](PRIVACY.md) before capturing sensitive material or enabling web previews. Removing the app or clearing its data removes locally stored captures and downloaded models; the app does not provide cloud backup.
