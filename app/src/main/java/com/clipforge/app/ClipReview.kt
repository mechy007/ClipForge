package com.clipforge.app

/** Human review state kept separate from AI ranking so review never changes the model score. */
enum class ClipReviewDecision { PENDING, APPROVED, REJECTED }

data class ReviewedClip(
    val candidate: ClipCandidate,
    val decision: ClipReviewDecision = ClipReviewDecision.PENDING,
    val reviewerNote: String = ""
) {
    fun withTrim(startMs: Long, endMs: Long, sourceDurationMs: Long): ReviewedClip {
        require(sourceDurationMs > 0L) { "Source duration must be positive." }
        require(startMs >= 0L && endMs > startMs && endMs <= sourceDurationMs) {
            "Choose a valid clip range within the source video."
        }
        return copy(candidate = candidate.copy(startMs = startMs, endMs = endMs))
    }

    fun approve() = copy(decision = ClipReviewDecision.APPROVED)
    fun reject(note: String = "") = copy(decision = ClipReviewDecision.REJECTED, reviewerNote = note.take(500))
}

/** Review queue operations used by the Highlights screen and batch exporter. */
object ClipReviewQueue {
    fun approved(items: List<ReviewedClip>): List<ClipCandidate> =
        items.filter { it.decision == ClipReviewDecision.APPROVED }.map { it.candidate }

    fun pending(items: List<ReviewedClip>): List<ReviewedClip> =
        items.filter { it.decision == ClipReviewDecision.PENDING }

    fun update(items: List<ReviewedClip>, original: ClipCandidate, transform: (ReviewedClip) -> ReviewedClip): List<ReviewedClip> =
        items.map { if (it.candidate == original) transform(it) else it }
}
