# Model and engine credits

Open Essential's application code is distributed under the root MIT LICENSE. Third-party components keep their own terms. See the full notices in `NOTICES`, also included in the app as `src/main/assets/NOTICES.txt`.

## Models

| Component | Credit | Source |
| --- | --- | --- |
| Qwen3.5-0.8B | Qwen team, Alibaba Cloud | https://huggingface.co/Qwen/Qwen3.5-0.8B |
| Q4_K_M GGUF and matching vision projector | Unsloth conversion and quantization | https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF |
| Whisper small | OpenAI | https://github.com/openai/whisper |
| ggml Whisper conversion | whisper.cpp contributors | https://huggingface.co/ggerganov/whisper.cpp |

The supplied notices record Apache-2.0 terms for Qwen and MIT terms for Whisper. Model files are downloaded or imported separately, not bundled in the APK or committed to the repository.

## Engines and libraries

- llama.cpp / libmtmd and ggml: https://github.com/ggml-org/llama.cpp
- whisper.cpp and its ggml: https://github.com/ggml-org/whisper.cpp
- AndroidX and Jetpack Compose: application UI and Android integration.
- PDFBox-Android: PDF parsing and text extraction, with upstream Apache PDFBox components.

The original source pins llama.cpp `v1.0.0` and whisper.cpp `v1.9.5`; archive hashes are enforced by `tools/fetch-engines.ps1`. Additional vendored-library notices remain in `NOTICES` and in downloaded upstream sources.

Names are used for attribution, not endorsement. Open Essential is not affiliated with Nothing Technology, Qwen, Unsloth, OpenAI or the ggml authors.
