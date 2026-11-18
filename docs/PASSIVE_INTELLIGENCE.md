# Passive intelligence integration

ClipForge performs a lightweight offline enrichment pass for every transcript-derived candidate:

- **Topic classification:** deterministic keyword categories (Gaming, Education, Technology, Business, Lifestyle, Storytime, General).
- **Boundary refinement:** snaps candidate boundaries toward the earliest/latest available speech timestamps with a small safety pad; falls back to the original window when timestamps are sparse.
- **Review guidance:** flags sparse transcript samples and qualifying pauses so the creator knows which cuts deserve manual review.
- **Suggested titles:** uses the opening sentence, with a category fallback.
- **Quality ranking and IoU suppression:** ranking features and scores are computed from the original candidate window. Duplicate suppression then uses the refined clip bounds. The ranking score therefore describes the original analysis window, not a re-scored refined cut.

These are heuristics, not semantic guarantees. Category labels are based on transcript vocabulary and may be wrong, especially for multilingual content. They do not predict virality. No network calls, extra model, background polling, or additional permissions are required.

## Deliberately not claimed as implemented
Speaker diarization, word-level alignment, face tracking, audio-energy silence detection, burned-in animated captions, and process-death-safe export jobs need separate runtime components and device validation. They must not be represented as active features until those components are integrated and tested.
