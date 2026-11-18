package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightDetectorTest {
    @Test fun allStrategiesHaveNormalizedWeights() {
        HighlightDetector.strategies.values.forEach { w ->
            assertEquals(1f, w.density+w.hooks+w.punctuation+w.vocabulary+w.context+w.novelty+w.clarity+w.opening, 0.002f)
        }
    }
    @Test fun productionStrategyIsRobustBalanced() { assertEquals("V9 robust-balanced", HighlightDetector.productionStrategy) }
    @Test fun detectorRejectsEmptyAndInvalidInput() {
        assertTrue(HighlightDetector.detect(emptyList(), 60_000L).isEmpty())
        val invalid = listOf(TranscriptSegment(-10,100,"invalid"),TranscriptSegment(200,100,"bad"),TranscriptSegment(100,200," "))
        assertTrue(HighlightDetector.detect(invalid,60_000L).isEmpty())
    }
    @Test fun detectorReturnsBoundedNonOverlappingClipsWithPassiveInsights() {
        val transcript = (0..11).map { i ->
            val start=i*5_000L
            TranscriptSegment(start,start+4_500L,"Here is an important discovery because the result changes everything. The best solution explains why this surprising method works!")
        }
        val clips=HighlightDetector.detect(transcript,90_000L)
        assertTrue(clips.size in 1..8)
        assertTrue(clips.all { it.startMs>=0 && it.endMs>it.startMs && it.score in 0..100 && it.reason.contains("Category:") })
        clips.forEachIndexed { i,c -> clips.drop(i+1).forEach { o ->
            assertTrue(HighlightDetector.intersectionOverUnion(c.startMs,c.endMs,o.startMs,o.endMs)<=0.40)
        } }
    }
    @Test fun intervalIouUsesUnion() {
        assertEquals(0.5,HighlightDetector.intersectionOverUnion(0,30_000,10_000,40_000),0.0001)
        assertEquals(0.0,HighlightDetector.intersectionOverUnion(0,10,10,20),0.0)
    }
    @Test fun passiveInsightsClassifyAndSnapBoundariesToSpeech() {
        val rows=listOf(TranscriptSegment(5_000,9_000,"Gaming tips help players win the match."),TranscriptSegment(10_000,15_000,"This game level has a boss challenge."))
        val insight=PassiveClipInsights.analyse(rows,rows.joinToString(" "){it.text},4_000,16_000,20_000)
        assertEquals("Gaming",insight.category)
        assertEquals(4_350L,insight.startMs)
        assertEquals(15_650L,insight.endMs)
        assertTrue(insight.suggestedTitle.isNotBlank())
        assertTrue(insight.note.isNotBlank())
    }
    @Test fun fallbackKeepsOriginalWindowForSparseTranscript() {
        val rows=listOf(TranscriptSegment(9_000,9_500,"One short phrase"))
        val insight=PassiveClipInsights.analyse(rows,"One short phrase",0,20_000,20_000)
        assertEquals(0L,insight.startMs)
        assertEquals(20_000L,insight.endMs)
    }
    @Test fun reasonsAreGeneratedFromWeightedSignals() {
        val rows=(0..5).map { i -> TranscriptSegment(i*5_000L,i*5_000L+4_500,"Why this simple solution works is surprising!") }
        val hookWeights=RankingWeights(0f,1f,0f,0f,0f,0f,0f,0f)
        val clips=HighlightDetector.detectWithWeights(rows,30_000,hookWeights)
        assertFalse(clips.isEmpty())
        assertTrue(clips.all { it.reason.contains("hook language") })
    }
    @Test fun overlappingSpeechCoverageDoesNotCreateFalsePause() {
        val rows=listOf(TranscriptSegment(1_000,28_000,"Long continuous speech."),TranscriptSegment(5_000,8_000,"Nested speech."),TranscriptSegment(12_000,14_000,"More nested speech."))
        val insight=PassiveClipInsights.analyse(rows, "This is enough words to keep the transcript context complete and useful.", 0, 30_000, 30_000)
        assertFalse(insight.note.contains("Contains pauses"))
    }
    @Test fun oneLongPauseIsFlagged() {
        val rows=listOf(TranscriptSegment(1_000,5_000,"First segment"),TranscriptSegment(12_000,16_000,"Second segment"))
        val insight=PassiveClipInsights.analyse(rows, "This is enough words to keep the transcript context complete and useful.", 0, 20_000, 20_000)
        assertTrue(insight.note.contains("Contains pauses"))
    }
}
