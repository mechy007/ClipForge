# ClipForge

ClipForge is an Android-first, on-device video clipping app. It imports local videos, previews and trims them, exports MP4 clips, and can transcribe speech offline with whisper.cpp.

## Current capabilities
- Import and preview a video using Android's document picker.
- Choose a trim range and export an MP4 locally.
- Download the multilingual Whisper tiny model on first use; verify its size and SHA-256 before accepting it.
- Decode a video's audio track locally to mono 16 kHz float PCM.
- Transcribe audio in bounded 30-second chunks and display timestamped transcript segments.
- Keep video, audio, model and transcript processing on-device; no AI API or remote processing backend is used.

## Build
- Android / Kotlin / Jetpack Compose
- JDK 17, Android NDK 27.2.12479018, CMake 3.22.1
- whisper.cpp v1.9.5, CPU-only ARM64 runtime
- Minimum Android SDK 26

## Privacy and storage
The Whisper model is downloaded from its published model repository and stored in app-private storage. Temporary decoded PCM is written to the app cache and deleted after transcription. The app does not upload the selected video or its audio.

## Next milestones
- Transcript-based highlight ranking.
- Clip suggestions with editable start/end points.
- Export progress, cancellation and recovery.
- Device performance, thermal and battery profiling.

## Licensing
ClipForge application code is MIT licensed. See THIRD_PARTY_NOTICES.md for dependency and model notices.
