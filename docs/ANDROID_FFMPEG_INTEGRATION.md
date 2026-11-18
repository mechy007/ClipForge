# Android FFmpeg integration

## Existing Whisper pipeline

ClipForge already ships the multilingual Whisper tiny model in the APK and runs pinned whisper.cpp v1.9.5 through JNI on arm64-v8a. Transcription is fully offline. Do not add a second Whisper runtime or download another model: `VideoTranscriber` and `WhisperEngine` are the existing production path.

## FFmpeg runtime

ClipForge adds the Android-native FFmpegKit-compatible LGPL runtime `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9`. This is not the Windows x64 FFmpeg executable from the Clipper Engine ZIP. The Android artifact supplies arm64-v8a native libraries and is built for Android SDK 35 / 16 KB page alignment according to its maintainer.

Project: https://github.com/ffmpegkit-maintained/ffmpeg
Artifact: https://central.sonatype.com/artifact/dev.ffmpegkit-maintained/ffmpeg-kit-full/8.1.9
License: LGPL-3.0 (non-GPL artifact). Do not switch to a `-gpl` artifact without reviewing the consequences for the whole app.

## Current integration

- Android MediaExtractor + MediaCodec remains the first-choice audio decoder because it uses platform codecs and avoids loading FFmpeg for common files.
- If Android's decoder cannot handle the selected video's audio, `FfmpegAudioFallback` copies the user-selected content URI into app-private cache and invokes FFmpeg with an argument array (not a shell command string).
- FFmpeg selects the first audio stream, downmixes to mono, resamples to 16 kHz, and writes big-endian 32-bit float PCM. Big-endian is intentional: the existing Whisper spool uses `DataOutputStream.writeFloat` and `DataInputStream.readFloat`.
- The fallback output is consumed by the same bundled offline whisper.cpp model. No duplicate Whisper model is downloaded.
- The temporary input is deleted after decoding. The normal transcription pipeline deletes its PCM spool after the session.
- Media3 Transformer remains the primary video preview/export pipeline; FFmpeg is not forced into every export.

## Validation and limitations

- Unit tests verify argument boundaries, paths containing spaces, first-audio-stream selection, and Whisper-compatible PCM format.
- Android CI must verify dependency resolution, APK packaging, native library loading, and tests before merge.
- A successful CI build does not replace real-device testing with representative MP4, MOV, WebM and MKV files.
- The fallback currently performs a synchronous FFmpeg session on an IO dispatcher. A future improvement can expose progress/cancellation callbacks for long fallback decodes.

## License distribution

The app bundles the LGPL-3.0 text, GPL-3.0 text referenced by LGPL section 4, and the FFmpegKit third-party notices under `app/src/main/assets/licenses/`. The upstream corresponding source is linked above. Before distributing APKs, retain these notices and review the LGPL shared-library replacement / corresponding-source obligations for the exact release artifact.