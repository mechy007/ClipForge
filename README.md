# ClipForge

ClipForge is an Android-first, local-first video clipping app. It imports local videos, previews and trims them, transcribes speech offline with whisper.cpp, ranks transcript-based highlight candidates, and exports selected moments as MP4 clips.

## Current capabilities
- Import and preview a local video using Android's document picker.
- Download the multilingual Whisper tiny model once; verify its size and SHA-256 before accepting it.
- Decode a video's audio track locally to mono 16 kHz float PCM.
- Transcribe audio in bounded 30-second chunks and retain timestamped transcript segments.
- Detect candidate 30-second highlights using offline transcript signals (speech density, hook words, punctuation and vocabulary variety).
- Rank and de-duplicate overlapping candidates, show a short transcript preview, and let the user adjust clip start/end points.
- Export each selected or edited clip as MP4 using Media3 Transformer.
- Keep selected video, decoded audio, transcription and highlight scoring on-device; no AI API or remote video processing backend is used.

Highlight scores are heuristic estimates of content signals. They do not predict views, engagement or virality. Results depend on speech quality and language.

## Build
- Android / Kotlin / Jetpack Compose
- JDK 17, Android NDK 27.2.12479018, CMake 3.22.1
- whisper.cpp v1.9.5, CPU-only ARM64 runtime
- Minimum Android SDK 26

## Privacy and storage
The Whisper model is downloaded from its published model repository and stored in app-private storage. Temporary decoded PCM is written to the app cache and deleted after transcription. The app does not upload the selected video or its audio.

## Next milestones
- Animated caption styles and subtitle export.
- Aspect-ratio-aware 9:16, 16:9 and 1:1 rendering.
- Subject-aware reframing and face tracking.
- Batch export, export progress, cancellation and recovery.
- Device performance, thermal and battery profiling.

## Licensing
ClipForge application code is MIT licensed. See THIRD_PARTY_NOTICES.md for dependency and model notices.
