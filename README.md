# ClipForge

ClipForge is an Android-first, local-first video clipping app. It imports local videos, previews and trims them, transcribes speech offline with whisper.cpp, ranks transcript-based highlight candidates, and exports selected moments as MP4 clips.

## Current capabilities
- Import and preview a local video using Android's document picker.
- Import supported direct HTTPS video-file links (MP4, WebM, MOV, MKV) with MIME validation, a 2 GB limit and partial-download cleanup.
- Save analyzed projects and ranked highlights in a local SQLite library; reopen saved project metadata and highlights.
- Download the multilingual Whisper tiny model once; verify its size and SHA-256 before accepting it.
- Decode a video's audio track locally to mono 16 kHz float PCM.
- Transcribe audio in bounded 30-second chunks and retain timestamped transcript segments.
- Detect candidate 30-second highlights using offline transcript signals (speech density, hook words, punctuation and vocabulary variety).
- Rank and de-duplicate overlapping candidates, show a short transcript preview, and let the user adjust clip start/end points.
- Export each selected or edited clip as a vertical 9:16 MP4 using Media3 Transformer (center crop; face-aware tracking is a later milestone).
- Export timestamp-aligned SRT subtitle sidecars for individual clips from the local transcript.
- Monitor public YouTube Live chat through the official Data API, detect activity spikes, and persist live sessions and timestamp markers in SQLite so they survive screen and app restarts.
- Stream the creator’s own camera/microphone feed to an RTMPS endpoint while recording a local MP4 simultaneously; import the completed recording directly into Video Studio.
- Capture the Android device screen and supported internal game/media audio through Android MediaProjection, stream it over RTMPS from a foreground service, and record a local MP4 simultaneously. A completed recording opens directly in Video Studio.
- Keep selected video, decoded audio, transcription and highlight scoring on-device; no AI API or remote video processing backend is used.

Highlight scores are heuristic estimates of content signals. They do not predict views, engagement or virality. Results depend on speech quality and language.

## Build
- Android / Kotlin / Jetpack Compose
- JDK 17, Android NDK 27.2.12479018, CMake 3.22.1
- whisper.cpp v1.9.5, CPU-only ARM64 runtime
- Minimum Android SDK 26

## Privacy and storage
The Whisper model is downloaded from its published model repository and stored in app-private storage. Temporary decoded PCM is written to the app cache and deleted after transcription. The app does not upload the selected video or its audio.

## Current limitations
- The project library currently persists project metadata and highlight records; full editing-session restoration and export-history UI are still being developed.
- Direct-link import supports direct HTTPS media files only, not YouTube or social-platform pages. The YouTube Live integration reads metadata/chat only; it does not download or record YouTube playback. Creator Live Studio captures a new camera/microphone feed that the user owns. The camera studio requires a manually supplied RTMPS server URL and stream key and runs while its activity stays open. Screen/game capture requires Android 10+, user-approved MediaProjection, Android audio permission for playback capture, notification permission, and a manually supplied RTMPS server URL/key. It captures supported internal game/media audio, not microphone commentary. Internal audio depends on the source app allowing playback capture; protected apps may block it. Set screen orientation before starting. Neither studio creates broadcasts through YouTube OAuth.
- Durable background processing, animated/burned-in caption rendering, aspect-ratio rendering and face-aware reframing are not yet complete. SRT sidecar export is available separately.

## Next milestones
- Animated caption styles and subtitle export.
- Aspect-ratio-aware 9:16, 16:9 and 1:1 rendering.
- Subject-aware reframing and face tracking.
- Batch export, export progress, cancellation and recovery.
- Durable background YouTube Live chat monitoring and broadcast-to-recording offset calibration. Marker/session persistence and foreground screen capture are implemented.
- Device performance, thermal and battery profiling.

## Licensing
ClipForge application code is MIT licensed. See THIRD_PARTY_NOTICES.md for dependency and model notices.
