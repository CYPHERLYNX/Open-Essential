# On-device engines (pinned)

| Engine | Pin | Source archive | SHA-256 of the archive |
|---|---|---|---|
| llama.cpp + libmtmd (text and vision) | tag `v1.0.0` | https://github.com/ggml-org/llama.cpp/archive/refs/tags/v1.0.0.zip (41,056,614 bytes) | `b8d90ec2be080e8834d05e4449793160d68d62518f614be4248d380f3b7c118b` |
| whisper.cpp (speech) | tag `v1.9.5` | https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.zip (10,640,105 bytes) | `7a7a73071939f16bc028ad2f713f16f26e37afb6cc155d0f19e3d65705d2b1d9` |

Native dependencies are downloaded and hash-checked by `tools/fetch-engines.ps1`. See [Build guide](../docs/BUILDING.md).
