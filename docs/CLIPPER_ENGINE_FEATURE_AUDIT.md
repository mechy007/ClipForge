# Clipper Engine feature audit

ClipForge uses the Windows x64 Clipper Engine archive as a feature and architecture reference only. Its Python runtime, PyInstaller executables, Windows DLLs and Windows FFmpeg binary are not Android-compatible and are not included in the APK.

## Features mapped to ClipForge

| Clipper Engine feature | ClipForge status |
|---|---|
| Live chat activity bursts as highlight signals | Implemented through YouTube Live chat monitoring |
| Live markers aligned to a local recording | Implemented |
| Transcript-based highlight detection and ranking | Implemented |
| Duplicate suppression and human-readable score reasons | Implemented |
| Topic classification and suggested clip titles | Implemented |
| Conservative speech-timestamp boundary refinement | Implemented |
| Offline multilingual speech transcription | Implemented with bundled whisper.cpp |
| Direct media URL import | Implemented for supported HTTPS media MIME types |
| YouTube URL recognition and helpful routing | Implemented; routes to Live chat workflow, not video downloading |
| Stream Scout discovery and stream leaderboard | YouTube live search implemented in Stream Scout; Twitch discovery is next |
| Automatic stream switching and multi-stream scouting | Not implemented |
| Face detection using YuNet and face-aware reframing | Not implemented; Android inference and temporal crop tracking still need implementation and device validation |
| Caption styling and burned-in captions | Not implemented; SRT sidecar export is available |
| Clip approval/rejection and re-rendering workflow | Partial; selection, editing and export exist, but not the full desktop review workflow |
| Durable background job orchestration | Not fully implemented |

## YouTube import behavior

The Studio direct-link importer expects a URL that responds with a supported video content type. A YouTube watch page returns HTML, not a direct MP4/WebM file. ClipForge therefore recognises YouTube links and routes them into the Live tab. The Live feature uses the documented YouTube Data API for public live metadata and chat activity only; it does not resolve playback URLs or download audiovisual content.

For an ordinary video, the user should import a local video file they own or are authorised to process. For a live broadcast, the user can monitor chat and use the resulting timestamps with an authorised local recording.

## Stream Scout discovery

ClipForge's first Stream Scout adapter uses the documented YouTube Data API. It searches active live broadcasts by a user-entered game/topic (with GTA 6 prefilled), then requests live-stream details to display concurrent viewers when the API supplies them. Selecting a result fills the existing live-chat monitor with that broadcast URL. This is discovery and chat monitoring only; it does not extract or download playback.

The default YouTube Data API quota gives the `search.list` method a separate 100-calls-per-day limit. The app therefore exposes an explicit search action rather than polling discovery automatically. See [YouTube Search: list](https://developers.google.com/youtube/data/reference/rest/v3/search/list) and [YouTube Data API quota](https://developers.google.com/youtube/data/determine_quota_cost).

Twitch is not implemented yet. Twitch's documented Get Streams endpoint supports live-stream discovery by category/game and requires a user or app access token. Reading Twitch chat requires an authenticated user access token with chat-read permission; this means public stream discovery and chat monitoring have different authentication requirements. See [Twitch Get Streams](https://dev.twitch.tv/docs/api/reference/streams) and [Twitch IRC authentication](https://dev.twitch.tv/docs/chat/irc).

## Android porting boundaries

The Clipper Engine archive contains a Windows x64 packaged Python application. Its EXE, DLL, Python extensions, runtime and Windows FFmpeg build cannot be used as Android application components. Any future port must be implemented with Android-compatible Kotlin/JNI libraries and separately audited model/dependency licenses.

Potential future engineering work:
- Android face detection and face-aware crop tracking.
- Caption styling and Media3-compatible burned-in caption rendering.
- Stream discovery for supported platforms using their documented APIs.
- Durable foreground/background job recovery with explicit Android lifecycle handling.