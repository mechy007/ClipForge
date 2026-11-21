package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorClipFeaturesTest {
    private val clip = ClipCandidate(1_000, 28_000, "Boss win", "We won!", 60)

    @Test fun presetsKeepPayoffAndGiveContextDifferentLengths() {
        val words = listOf(TranscriptSegment(14_000, 16_000, "We won!"))
        val payoff = CreatorClipCuts.payoff(clip, words, emptyList())
        assertEquals(14_000L, payoff)
        val hook = CreatorClipCuts.cut(clip, CreatorCut.FAST_HOOK, 30_000, words, payoff)
        val context = CreatorClipCuts.cut(clip, CreatorCut.FULL_CONTEXT, 30_000, words, payoff)
        val reaction = CreatorClipCuts.cut(clip, CreatorCut.REACTION_FIRST, 30_000, words, payoff)
        assertTrue(context.startMs < hook.startMs && hook.startMs < reaction.startMs)
        assertTrue(listOf(hook, context, reaction).all { it.startMs <= payoff && it.endMs in payoff + 1..30_000L })
    }

    @Test fun correctionsPreserveWhitespacePunctuationAndAlignedTiming() {
        val word = TimedWord(0, 400, "maincraft!")
        val segment = TranscriptSegment(0, 400, "maincraft! maincrafter", listOf(word))
        val corrected = CaptionDictionary.apply("maincraft=Minecraft", listOf(segment)).single()
        assertEquals("Minecraft! maincrafter", corrected.text)
        assertEquals(listOf(word.copy(word = "Minecraft!")), corrected.words)
    }

    @Test fun localReviewsBiasMatchingClipsOnly() {
        val prefs = CreatorClipPreferences(List(5) { ClipPreferenceExample(true, 27_000L, true) })
        assertTrue(prefs.adjustment(clip) > 0)
        assertEquals(0, prefs.adjustment(clip.copy(hook = "")))
        assertEquals(0, CreatorClipPreferences().adjustment(clip))
    }
}
