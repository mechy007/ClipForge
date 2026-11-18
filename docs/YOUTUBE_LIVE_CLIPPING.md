# YouTube Live clipping — architecture and policy boundary

**Target:** ClipForge Android application  
**Initial implementation:** official YouTube Live metadata/chat integration + timestamped highlight markers  
**Video processing:** existing on-device Whisper and Media3 pipeline

## What the first implementation does

1. Accept a YouTube video ID or supported YouTube Live URL.
2. Use the documented YouTube Data API `videos.list` endpoint to resolve the live title, broadcast start time and active chat ID.
3. Read public chat messages through `liveChatMessages.list`, honoring the server-provided polling interval and continuation token.
4. Bucket messages into 10-second windows, compare activity with a rolling 3-minute median baseline, and emit timestamped highlight markers with a cooldown.
5. Associate those markers with an original recording supplied by the creator or another source the user is authorised to process.
6. Reuse ClipForge's existing offline Whisper transcription, highlight ranking, Media3 trim/crop export and SRT generation.

## Recording boundary

The YouTube Data API is not a video-download API. YouTube's developer policies prohibit downloading, importing, backing up, caching or storing copies of YouTube audiovisual content without YouTube's prior written approval. ClipForge therefore must not extract private HLS playback URLs, scrape player internals, bypass protections, or record arbitrary third-party YouTube streams.

For the first release, the video source must be the creator's own original recording or footage they are authorised to process. Live chat produces timestamp markers; the source recording is supplied separately and matched against the broadcast timeline. A future creator-broadcast mode can record the original camera/encoder feed before it is sent to YouTube, subject to the user's rights and the relevant platform requirements.

## API setup

- Enable YouTube Data API v3 in a Google Cloud project.
- Use a restricted API key for public video metadata and public live chat reads.
- Keep the key out of source control. Restrict it to the app/API as appropriate and allow the user to configure their own key.
- Respect quota errors, HTTP 403 permissions errors, `pollingIntervalMillis`, `nextPageToken` and the `offlineAt` signal.
- Public chat access can fail when chat is disabled, unavailable, ended or restricted.

## Detection defaults

- 10-second message buckets.
- Rolling baseline of 18 buckets (3 minutes).
- Require at least 3 baseline buckets.
- Trigger only when a bucket has at least 5 messages and at least 2.5× the median baseline.
- 30-second cooldown to reduce duplicate markers.
- Chat activity is an engagement proxy, not proof that a moment is good. Future scoring can combine chat spikes with audio/transcript signals from the creator's original recording.

## Not yet implemented

- Encrypted API-key persistence is intentionally not included; the current panel keeps the restricted key in memory only.
- Durable background session lifecycle and cancellation; monitoring currently stops when the panel leaves composition.
- SQLite persistence for live sessions and markers.
- Mapping broadcast timestamps to an imported source recording with explicit offset calibration.
- End-to-end device testing, including a real YouTube API key and live broadcast.
- Creator broadcast/encoder integration.
