# Fully on-device AI architecture

ClipForge already performs video analysis locally. This change makes the speech backend explicit and replaceable without introducing a cloud dependency.

## Pipeline
1. Android MediaCodec / FFmpeg extracts mono 16 kHz PCM into app-private cache.
2. `OfflineSpeechBackends.default` selects the bundled Whisper Tiny multilingual model.
3. The backend loads one native session and transcribes bounded 30-second audio chunks.
4. Local audio-energy analysis, scene-change detection and `HighlightDetector` score and refine clip candidates.
5. Media3 / FFmpeg render clips and captions on-device.
6. Temporary PCM is removed after transcription, including failures.

## Dependency review
- Existing: whisper.cpp native ARM64 runtime, bundled and SHA-256-verified Whisper Tiny model, Media3, FFmpeg, ML Kit face detection.
- Added runtime dependencies: **none**.
- Remote LLM, cloud transcription, remote highlight APIs: **none**.
- YouTube URL import and live-chat metadata require network access; this is separate from the offline AI analysis pipeline.
- The model is downloaded during CI/build and packaged in the APK, not downloaded by the app during inference.

## Model selection
The production backend remains Whisper Tiny to preserve multilingual recognition and timestamped segments. Moonshine is **not** yet integrated: adding its Android runtime, verified model assets, timestamp mapping and device performance tests must be a separate change. Do not advertise Moonshine as available until those tests pass.

## Validation
Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` in CI. Test on an ARM64 device with airplane mode enabled using a locally imported video; verify captions, highlights, face-aware export, and cleanup.
