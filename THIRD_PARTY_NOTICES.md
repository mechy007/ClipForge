# Third-party notices

This file tracks third-party software and model assets used by ClipForge.

| Component | Use | License | Version / details |
|---|---|---|---|
| AndroidX / Jetpack Compose | Android UI and platform integration | Apache-2.0 | Dependencies; retain notices |
| AndroidX Media3 | Playback and media transformation | Apache-2.0 | 1.5.1 |
| RootEncoder | Camera/microphone RTMPS streaming and simultaneous MP4 recording | Apache-2.0 | 2.5.5; https://github.com/pedroSG94/RootEncoder |
| whisper.cpp | Offline speech-to-text runtime | MIT | Pinned to v1.9.5; fetched at native build time |
| Whisper tiny multilingual model | Bundled in APK | MIT | 77,691,713 bytes; SHA-256: be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21 |

Model source: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin

The model is fetched and verified during the APK build, bundled in the APK as an uncompressed asset, and copied to app-private storage on first launch. The app does not download the model at runtime. The app verifies the expected byte length and SHA-256 before accepting the file.

Whisper.cpp source: https://github.com/ggml-org/whisper.cpp/tree/v1.9.5
