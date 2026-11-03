# Third-party notices

This file tracks third-party software and model assets used by ClipForge.

| Component | Use | License | Version / details |
|---|---|---|---|
| AndroidX / Jetpack Compose | Android UI and platform integration | Apache-2.0 | Dependencies; retain notices |
| AndroidX Media3 | Playback and media transformation | Apache-2.0 | 1.5.1 |
| whisper.cpp | Offline speech-to-text runtime | MIT | Pinned to v1.9.5; fetched at native build time |
| Whisper tiny multilingual model | Downloaded on first AI setup | MIT | 77,691,713 bytes; SHA-256: be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21 |

Model source: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin

The model is downloaded to app-private storage after the user chooses to enable offline AI. It is not bundled in the APK or uploaded to a server. The app verifies the expected byte length and SHA-256 before accepting the file.

Whisper.cpp source: https://github.com/ggml-org/whisper.cpp/tree/v1.9.5
