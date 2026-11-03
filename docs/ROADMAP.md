# ClipForge roadmap

## Milestone 0 — Foundation
- [x] Android project skeleton
- [x] MIT license and third-party inventory
- [x] Compose dashboard and video picker/player foundation
- [ ] CI build verification on GitHub Actions

## Milestone 1 — Video workflow
- [ ] Reliable video import and metadata extraction
- [ ] Timeline and in/out-point selection
- [ ] Media3 Transformer export
- [ ] Export progress, cancellation, and error recovery

## Milestone 2 — Offline AI
- [ ] Integrate whisper.cpp through Android NDK/JNI
- [ ] Model download/import with checksums and license metadata
- [ ] Chunked transcription with timestamps
- [ ] Transcript-based candidate highlight ranking

## Milestone 3 — Smart clips
- [ ] Candidate review and manual adjustment
- [ ] Captions with editable text and styling
- [ ] 9:16 / 16:9 / 1:1 output presets
- [ ] Optional MediaPipe face-aware crop

## Milestone 4 — Hardening
- [ ] Device/RAM/thermal profiling
- [ ] Accessibility and localization
- [ ] Automated tests and release workflow
- [ ] Optional authorized source integrations after policy/terms review

## MVP acceptance criteria
- A user can select a local video without broad storage permissions.
- The app can preview the selected video.
- A later milestone must export a valid playable clip on-device.
- No paid AI API, cloud account, or remote backend is required.
