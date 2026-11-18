# Reference repository integration

ClipForge remains a native Android application. These repositories are engineering references; their desktop/Python applications and dependency stacks are not embedded in the Android APK.

## Selected references

| Repository | License checked | Useful ideas studied | Android adaptation |
|---|---|---|---|
| [Anil-matcha/AI-Youtube-Shorts-Generator](https://github.com/Anil-matcha/AI-Youtube-Shorts-Generator) | MIT | Virality-framework dimensions, hook/title/reason metadata, overlapping long-video chunks, duplicate suppression and crop-to-ratio outputs | Keep scoring local and explicitly heuristic; expose a human-readable reason for each candidate; preserve native Media3 export |
| [artbyjazi/autoclip](https://github.com/artbyjazi/autoclip) | MIT | Overlapping transcript windows, word-index selection, intersection-over-union deduplication, boundary refinement, resumable stage artifacts, caption grouping/styles and speaker-aware reframing | Adopt IoU suppression and transparent scoring explanations now. Word-index selection and boundary refinement require word-level timestamps, which current Whisper JNI output does not expose. Durable jobs, styled/burned-in captions and speaker tracking remain separate Android milestones |
| [modelscope/FunClip](https://github.com/modelscope/FunClip) | MIT | Transcript-first selection, multi-segment clipping, timestamped subtitle output, optional hotwords and speaker labels | Retain local timestamped transcript and SRT workflow. Hotword prompting needs a supported whisper.cpp JNI prompt interface; speaker labels require diarization and are not inferred from plain Whisper text |

## Implemented in this integration

- Replaced shorter-clip overlap ratio suppression with intersection-over-union (IoU) suppression at 0.40, following AutoClip's interval-overlap approach.
- Added a concise explanation derived from the strongest weighted local scoring signals (opening, context, clarity, hook language, vocabulary and repetition).
- Persisted the explanation with each saved highlight and displayed it in the Highlights list.
- Expanded the hook vocabulary used by the offline heuristic. These signals are not view or engagement predictions.

## Deliberately not copied

- No Python, Gradio, Node, desktop FFmpeg binary, Ollama server, PyTorch, FunASR, WhisperX or runtime model downloads are added to Android.
- No MuAPI or other paid/hosted provider is introduced; analysis remains on-device.
- No YouTube/social-page downloader or DRM/cookie circumvention is introduced. Import remains limited to local media and supported direct HTTPS media files.
- No face/speaker tracking is claimed until an Android-compatible implementation is validated against test footage.
- No source code is copied wholesale. The current changes are original Kotlin adaptations of documented techniques.

## Attribution and license notices

The selected repositories' application code declares MIT licensing. If future changes copy substantial source code or assets rather than reimplementing an idea, preserve the exact upstream copyright and license notice for that copied material and audit every bundled model, font and dependency separately. A repository's MIT license does not automatically relicense separately downloaded models or third-party assets.

- AI-Youtube-Shorts-Generator: [upstream LICENSE](https://github.com/Anil-matcha/AI-Youtube-Shorts-Generator/blob/main/LICENSE).
- AutoClip: [upstream LICENSE](https://github.com/artbyjazi/autoclip/blob/main/LICENSE).
- FunClip: [upstream LICENSE](https://github.com/modelscope/FunClip/blob/main/LICENSE).

## Source files reviewed

- AI-Youtube-Shorts-Generator: README, LICENSE, requirements and documented ranking/processing flow.
- AutoClip: README, LICENSE, pipeline/highlights.py, pipeline/captions.py and pipeline end-to-end tests.
- FunClip: README, LICENSE and funclip/launch.py.

The upstream repositories remain the authoritative source locations; this file records the techniques ClipForge evaluated and the adaptations actually implemented.
