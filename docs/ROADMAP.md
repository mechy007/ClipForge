# ClipForge roadmap

## Foundation
- [x] Android project skeleton and MIT license
- [x] Compose dashboard and local video picker/player
- [x] Start/end trim controls and Media3 MP4 export implementation
- [x] Physical-device smoke test reported by project owner
- [x] GitHub Actions debug build verification

## Offline AI
- [x] Pin whisper.cpp v1.9.5 and add Android NDK/JNI runtime boundary
- [x] Add first-run model download with SHA-256 verification and progress UI
- [ ] Decode selected video audio to mono 16 kHz PCM
- [ ] Connect video audio to Whisper and show timestamped transcript
- [x] Transcript-based highlight ranking (heuristic baseline)
- [ ] Context-aware highlight ranking validation on real videos

## Smart clips
- [x] Candidate review and manual adjustment
- [x] Save analyzed project metadata and highlights locally
- [x] Direct HTTPS video-file import (supported MIME types only)
- [ ] Full project editing-session restoration
- [ ] Editable captions
- [ ] 9:16 / 16:9 / 1:1 presets
- [ ] Optional MediaPipe face-aware crop

## Hardening
- [ ] Download resume/cancel and model storage management
- [ ] Export progress, cancellation and recovery
- [ ] RAM, battery and thermal profiling
- [ ] Accessibility and automated tests
- [ ] Optional authorized source adapters after terms review

## MVP acceptance
- Import and preview a local video without broad storage permissions.
- Select an in/out range and export a playable MP4 to a user-selected location.
- Transcribe selected video audio and suggest highlights fully on-device.
- No paid AI API or remote backend is required.
