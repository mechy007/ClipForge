# Open-source integration audit — Android-native rebuild

**Status:** research baseline, 2026-11-18  
**Target:** existing `mechy007/ClipForge` Android application  
**Working branch:** `rebuild/android-native-ai-pipeline`

## Decision

ClipForge remains a native Android application (Kotlin, Jetpack Compose, Media3, whisper.cpp). Do not replace it wholesale with a desktop/server application. Reuse upstream projects as engineering references and selectively port algorithms or small platform-neutral modules. Keep upstream attribution and the exact upstream commit/license recorded for every imported component.

A GitHub fork is a separate repository, not a directory inside ClipForge. The connected GitHub actions currently available in this workflow support repository reads and commits/PRs but do not expose a create-fork operation. Therefore no upstream fork has been created, and this audit does not claim otherwise. The candidate repositories remain upstream-linked references until a fork operation is available.

## Current ClipForge baseline

- Public MIT-licensed repository; default branch `main`.
- Kotlin / Jetpack Compose Android app; minSdk 26, compileSdk 35, targetSdk 35, JDK 17, ARM64 NDK build.
- Existing native whisper.cpp v1.9.5 transcription path, local tiny multilingual model manager, Media3 playback/Transformer export, local SQLite project metadata/highlight persistence, document-picker import, direct HTTPS video-file import.
- Main branch inspected at `78ee04dac585532f5a42b71eafe6d99c73f0be62`.
- Existing feature implementation is not proof of full physical-device validation; Android hardware tests remain necessary.

## Candidate audit

### 1. Anil-matcha/AI-Youtube-Shorts-Generator
- Upstream: https://github.com/Anil-matcha/AI-Youtube-Shorts-Generator
- License inspected: MIT, copyright Anil Chandra Naidu Matcha (2026).
- Useful areas: transcript-to-highlight selection, prompt/LLM scoring, clip deduplication and crop strategy.
- Runtime fit: Python project using faster-whisper, OpenCV, optional LLM SDKs and yt-dlp. Not directly embeddable in a native Android app.
- Decision: **high-priority algorithm/reference source; selective port only**. Keep the existing on-device Whisper engine rather than shipping a second Python transcription stack.

### 2. artbyjazi/autoclip
- Upstream: https://github.com/artbyjazi/autoclip
- License inspected: MIT, copyright Jad Ghazi (2026).
- README describes a complete ingest → prepare → transcribe → highlight → reframe → captions → export workflow, with resumable artifacts, word-level timing and speaker-aware crop paths.
- Runtime fit: Python 3.11/3.12, ffmpeg with libass/libx264, Node UI, faster-whisper, MediaPipe/OpenCV; optional WhisperX/PyTorch diarization is heavy. Not a direct Android dependency.
- Decision: **highest-priority architecture and algorithm reference** for stage contracts, resumability, crop smoothing, caption timing and test design. Port platform-neutral logic only after inspecting exact source files and dependency/model terms.

### 3. modelscope/FunClip
- Upstream: https://github.com/modelscope/FunClip
- License inspected: MIT, copyright Alibaba (2023).
- Useful areas: multi-segment transcript selection, timestamped subtitles, speaker-label workflows and LLM-assisted selection.
- Runtime fit: Python, FunASR/Paraformer and Gradio; model stack is primarily server/desktop oriented and has a Chinese-first history. Not a sensible default runtime for low-memory Android.
- Decision: **reference only** for transcript selection UX and diarization concepts; do not import its Python service/model stack.

### 4. sdsoporte/openshorts
- Upstream: https://github.com/sdsoporte/openshorts
- Repository metadata currently reports archived.
- README advertises Docker/self-hosted workflow and 8GB+ RAM requirements, with external AI/service integrations.
- Decision: **do not adopt as a base**. Archived status and server-oriented resource assumptions conflict with ClipForge's Android/offline target.

### 5. FujiwaraChoki/supoclip
- Upstream: https://github.com/FujiwaraChoki/supoclip
- Public repository metadata inspected; a complete licence/source audit is still required before reuse.
- Decision: **not approved for code import yet**.

## Integration architecture

Keep one native pipeline with replaceable stages:

1. Import: Android document picker + validated direct HTTPS media-file importer.
2. Probe/decode: Media3 platform codecs; bounded PCM extraction.
3. Transcribe: current whisper.cpp ARM64 engine, chunked and cancellable.
4. Analyze: pluggable highlight scorer over timestamped transcript segments; deterministic local baseline first, optional remote LLM over transcript text only.
5. Reframe: Android-compatible face detection/tracking with a motion fallback; smooth crop keyframes and reset at scene cuts.
6. Captions: word/segment-timed caption model with editable text and style presets.
7. Render: Media3 Transformer; hardware codec where available, bounded fallback and export progress. Evaluate the Media3 Effect module for video overlays, filters and caption burn-in rather than adding a second renderer: https://developer.android.com/media/implement/editing-app.
8. Projects: SQLite metadata + durable work state; no large video/audio blobs in the database.

## Rules before importing any upstream code

- Inspect exact source file, commit SHA, license, dependencies and model/data terms.
- MIT/Apache/BSD code may be considered with required notices; GPL/AGPL code is not to be copied into the current MIT app without a deliberate licensing decision.
- No dependency on Python, Docker, CUDA, desktop ffmpeg binaries or 8GB+ RAM in the Android runtime.
- Avoid duplicate Whisper engines and duplicate media renderers.
- Keep imports, analysis and rendering cancellable; process long media in bounded chunks.
- Add unit tests for algorithms and Android instrumentation/device tests for media behavior.
- Record source URL, commit, licence, files adapted, modifications and validation in `THIRD_PARTY_NOTICES.md`.

## Immediate implementation priorities

1. Resolve the stale open PR #8 against current main before considering any merge; its base SHA predates the current main and GitHub reports it as not mergeable. Its CI status response currently contains no status checks. Do not merge it blindly.
2. Add a proper stage-oriented pipeline contract and durable job state.
3. Add a deterministic, testable highlight-ranking layer that consumes existing Whisper timestamps.
4. Implement caption data/export and aspect-ratio presets using Media3.
5. Add Android-compatible face-aware reframing using bundled ML Kit Face Detection as the offline-first candidate (16.1.7; approximately 6.9 MB APK size increase), sparse frame sampling and motion fallback. Keep model-ready checks and gracefully disable face tracking if initialization fails. Google documents face tracking support and bundled vs Play-services-delivered model trade-offs: https://developers.google.com/ml-kit/vision/face-detection/android.
6. Add a real-video evaluation set and device profiling (RAM, thermal, battery, export time).

## Known limitations of this audit

This is a compatibility and licensing triage, not a full line-by-line security audit of every candidate. No upstream code has been imported yet. The connected GitHub integration does not currently expose a fork-creation action, so actual upstream forks must not be claimed until created through a supported GitHub operation.
