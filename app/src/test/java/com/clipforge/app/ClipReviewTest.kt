package com.clipforge.app

import org.junit.Assert.*
import org.junit.Test

class ClipReviewTest {
    private val clip = ClipCandidate(1_000L, 8_000L, "Moment", "hello", 80)

    @Test fun approvalQueueOnlyReturnsApprovedClips() {
        val items = listOf(ReviewedClip(clip).approve(), ReviewedClip(clip.copy(startMs = 9_000L)))
        assertEquals(listOf(clip), ClipReviewQueue.approved(items))
        assertEquals(1, ClipReviewQueue.pending(items).size)
    }

    @Test fun trimRejectsInvalidRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            ReviewedClip(clip).withTrim(5_000L, 4_000L, 10_000L)
        }
    }

    @Test fun trimKeepsCandidateMetadata() {
        val edited = ReviewedClip(clip).withTrim(2_000L, 7_000L, 10_000L)
        assertEquals(2_000L, edited.candidate.startMs)
        assertEquals(80, edited.candidate.score)
        assertEquals("Moment", edited.candidate.title)
    }
}
