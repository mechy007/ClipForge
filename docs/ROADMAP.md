# ClipForge roadmap

## Foundation
- [x] Android project skeleton and MIT license
- [x] Compose dashboard and local video picker/player
- [x] Start/end trim controls and Media3 MP4 export implementation
- [ ] Verify build and export on physical devices

## Offline AI
- [ ] whisper.cpp Android NDK/JNI integration
- [ ] Model import/download with checksums and license metadata
- [ ] Chunked timestamped transcription
- [ ] Transcript-based highlight ranking

## Smart clips
- [ ] Candidate review and manual adjustment
- [ ] Editable captions
- [ ] 9:16 / 16:9 / 1:1 presets
- [ ] Optional MediaPipe face-aware crop

## Hardening
- [ ] Export progress, cancellation and recovery
- [ ] RAM, battery and thermal profiling
- [ ] Accessibility and automated tests
- [ ] Optional authorized source adapters after terms review

## MVP acceptance
- Import and preview a local video without broad storage permissions.
- Select an in/out range and export a playable MP4 to a user-selected location.
- Add transcription and highlight suggestions fully on-device in later milestones.
- No paid AI API or remote backend is required.
