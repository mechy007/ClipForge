package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiHighlightRankerTest {
    private val clips = (0..2).map { ClipCandidate(it * 1000L, it * 1000L + 900L, "Title $it", "Hook $it", 50) }

    @Test fun rankingDeduplicatesAndKeepsMissingCandidates() {
        assertEquals(listOf(clips[2], clips[0], clips[1]), GeminiHighlightRanker.reorder(clips, listOf(2, 2, 0, 99)))
    }

    @Test fun emptyRankingPreservesOfflineOrder() {
        assertEquals(clips, GeminiHighlightRanker.reorder(clips, emptyList()))
    }
}
