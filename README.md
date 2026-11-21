# ClipForge

ClipForge is an Android-first, local-first video clipping app. It imports local videos, previews and trims them, transcribes speech offline with whisper.cpp, ranks speech and visual highlight candidates, and exports selected moments as MP4 clips.

## Current capabilities
- Import and preview a local video using Android's document picker, with a responsive preview canvas, playback controls, seeking, and provider-supplied display names.
- Import supported direct HTTPS video-file links (MP4, WebM, MOV, MKV) with MIME validation, a 2 GB limit and partial-download cleanup.
- Recognise YouTube watch, Shorts, Live and short links pasted into the direct-link field; route them into the Live tab with the URL prefilled and explain the supported workflow instead of showing a confusing media-MIME error.
- Save analyzed projects and ranked highlights in a local SQLite library; reopen saved project metadata and highlights.
- Bundle the multilingual Whisper tiny model inside the APK; install it into app-private storage on first launch and verify its size and SHA-256. No in-app model download is required.
- Load the native Whisper model once per video transcription session and reuse the context across 30-second chunks, then release it when transcription finishes.
- Decode a video's audio track locally to mono 16 kHz float PCM.
- Transcribe audio in bounded 30-second chunks and retain timestamped transcript segments.
- Choose automatic language detection or explicitly select English, Malayalam, Hindi, Tamil, Telugu, Kannada, Bengali, Urdu or Arabic for on-device transcription.
- Detect candidate 30-second highlights using offline transcript signals (speech density, hook words, punctuation and vocabulary variety).
- Rank and de-duplicate overlapping candidates, show a short transcript preview, and let the user adjust clip start/end points.
- Select individual detected highlights or all highlights, then export them individually or sequentially as a batch into a chosen Android folder. Each batch output is verified before it is marked complete; reopening a batch rechecks saved clips and reuses their document URIs. A source with a readable file revision supports retries across app restarts.
- Export selected clips as vertical 9:16 Shorts, landscape 16:9 YouTube videos, or square 1:1 MP4s using Media3 Transformer. The chosen format applies to both single and batch exports, and the Studio preview uses the same crop-to-fill behavior.
- Choose Standard or Streamer + Gameplay for 9:16 output. ClipForge proposes a facecam region from sampled frames, lets you set and adjust it, and uses the same 1080 × 1920 renderer for review and export.
- Create captioned MP4s with Clean, Bold, Boxed or Karaoke styling. Karaoke uses Whisper token timings when they cover the recorded speech and uses phrase captions when alignment is unavailable. SRT subtitle sidecars are also available.
- For supported recordings, **Analyze during download** overlaps offline analysis with downloading complete sections. ClipForge checks the timeline before joining those sections for review and falls back to downloading the complete recording if the sections cannot be joined. Live and unseekable sources can require the standard import path.
- Export the complete timestamped transcript as a readable UTF-8 TXT file for notes, editing workflows and repurposing.
- Monitor public YouTube Live chat through the official Data API, detect activity spikes, and persist live sessions and timestamp markers in SQLite so they survive screen and app restarts.
- When capture is started from an existing monitored session, save the local recording start time and automatically convert broadcast-relative chat markers to the recording timeline. For automatic alignment, start YouTube Live chat monitoring first, then start the camera or screen capture.
- Stream the creator’s own camera/microphone feed to an RTMPS endpoint while recording a local MP4 simultaneously; import the completed recording directly into Video Studio.
- Capture the Android device screen and supported internal game/media audio through Android MediaProjection, stream it over RTMPS from a foreground service, and record a local MP4 simultaneously. A completed recording opens directly in Video Studio.
- Keep selected video, decoded audio, transcription and highlight scoring on-device; no AI API or remote video processing backend is used.

Highlight scores are heuristic estimates of content signals. They do not predict views, engagement or virality. Results depend on speech quality and language.

## Creator review

- **Edit timing** offers Fast Hook, Full Context, and Reaction First. The presets use offline timing signals and preserve the recording's sequence. Preview or adjust a preset before saving it.
- Approve or reject highlights to tune future rankings on this device. The ranking uses recent feedback and the length and speech characteristics of each clip.
- In 9:16 split-screen, reposition the facecam or gameplay crop on a source frame. **Follow streamer’s face** uses detections inside the facecam area. **Lock facecam crop** switches back to the selected rectangle. **Follow gameplay motion** uses concentrated movement outside the facecam region and keeps the crop inside the selected gameplay area.
- **Caption dictionary** stores one whole-word correction per line, for example `maincraft=Minecraft`. Enable Roman Hindi for approximate Latin-script Hindi spelling. Words retain their timestamps, including in exported karaoke captions.
- **Publish pack** copies editable title and creator credit and saves a chosen source frame as JPEG.
- **Analyze during download** can show previews from completed, analyzed sections. Trim or approve a preview to bring it into the completed project when those sections form the final recording.


## Reference projects

ClipForge studies three MIT-licensed open-source projects for useful clipping techniques. Android-compatible adaptations and licensing boundaries are documented in [Reference Repository Integration](docs/REFERENCE_INTEGRATION.md).

## Build
- Android / Kotlin / Jetpack Compose
- JDK 17, Android NDK 27.2.12479018, CMake 3.22.1
- whisper.cpp v1.9.5, CPU-only ARM64 runtime
- Minimum Android SDK 26

## Privacy and storage
The Whisper model is fetched and SHA-256-verified during the APK build, packaged as an uncompressed app asset, then copied locally into app-private storage on first launch. The app performs no model network download at runtime. Temporary decoded PCM is written to the app cache and deleted after transcription. The app does not upload the selected video or its audio.

## Current limitations
- The project library currently persists project metadata and highlight records; full editing-session restoration and export-history UI are still being developed. The user can reopen a project and select the same export settings and folder to resume a batch after process termination when a source revision is available.
- In Studio, paste a recorded YouTube video URL, select a maximum resolution, and choose **Download and clip**. ClipForge saves the source in app storage and opens the trim editor. Direct HTTPS video files are also supported. The Live tab monitors live chat and records timestamp markers. Creator Live Studio captures a new camera/microphone feed that the user owns. The camera studio requires a manually supplied RTMPS server URL and stream key and runs while its activity stays open. Screen/game capture requires Android 10+, user-approved MediaProjection, Android audio permission for playback capture, notification permission, and a manually supplied RTMPS server URL/key. It captures supported internal game/media audio, not microphone commentary. Internal audio depends on the source app allowing playback capture; protected apps may block it. Set screen orientation before starting. Neither studio creates broadcasts through YouTube OAuth.
- Background analysis and recovery across a range of document providers, plus alignment quality across languages, still need validation.

## Next milestones
- Validate captions, face detection and face-aware framing on representative recordings.
- Explicit export cancellation controls and physical-device recovery tests.
- Durable background YouTube Live chat monitoring and multi-recording/session selection. Session persistence, recording-start alignment and foreground screen capture are implemented.
- Device performance, thermal and battery profiling.

## Licensing
ClipForge application code is MIT licensed. See THIRD_PARTY_NOTICES.md for dependency and model notices.


- Existing offline Whisper uses bundled whisper.cpp. Android FFmpeg fallback now handles audio that MediaCodec cannot decode and feeds the same Whisper PCM pipeline. See `docs/ANDROID_FFMPEG_INTEGRATION.md` for compatibility and licensing details.
