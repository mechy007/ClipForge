# ClipForge 1.0 implementation notes

## Included in this PR
- Local SQLite project and highlight persistence.
- Direct HTTP(S) video-file import with MIME validation, a 2 GB cap, cancellation checks, and partial-file cleanup.
- Existing whisper.cpp offline transcription remains the speech-to-text provider.
- No external video platform scraping or access-control bypass.

## Integration notes
- Android's document picker remains the preferred local import path.
- Downloaded app-private media is stored outside SQLite; only metadata and URI references are persisted.
- Call saveProject after successful analysis and saveHighlights with resulting candidates.
- Use listProjects and listHighlights to populate project-library screens.
- Direct video URL import accepts direct video responses only. YouTube, social media pages, DRM streams, and authenticated platform content are not downloaded.

## Remaining work
- Wire project-library screens and URL-import dialog into Compose navigation.
- Add durable WorkManager jobs and visible cancellation/retry state.
- Add caption editor, aspect-ratio rendering, batch export, and face-aware reframing.
- Add automated instrumentation tests on physical ARM64 devices.

Do not represent remaining features as complete until their UI paths and device tests are merged.
