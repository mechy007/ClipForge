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
- [x] Transcript-based highlight ranking with ten selectable scoring profiles (V9 default)
- [x] Synthetic 100-run comparison report for ten scoring profiles

## Project library and imports
- [x] Private SQLite project, transcript, highlight and export-reference storage
- [x] Reopen saved projects and restore ranked highlights
- [x] Direct HTTPS video-file import with MIME checks, size cap and temporary-file cleanup

## Smart clips
- [x] Candidate review and manual adjustment
- [ ] Editable captions
- [ ] 9:16 / 16:9 / 1:1 presets
- [ ] Optional MediaPipe face-aware crop

## Hardening
- [ ] Download resume/cancel and model storage management
- [ ] Export progress, cancellation and recovery
- [ ] RAM, battery and thermal profiling
- [ ] Accessibility and broader automated tests
- [x] Unit tests for ranking profile normalization, invalid input and overlap suppression
- [ ] Optional authorized source adapters after terms review

## MVP acceptance
- Import and preview a local video without broad storage permissions.
- Select an in/out range and export a playable MP4 to a user-selected location.
- Transcribe selected video audio and suggest highlights fully on-device.
- No paid AI API or remote backend is required.
