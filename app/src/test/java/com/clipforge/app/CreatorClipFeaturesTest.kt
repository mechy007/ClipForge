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

    @Test fun trackedFacecamKeepsSeparateGameplayCrop() {
        val input = java.io.File("source.mp4")
        val output = java.io.File("output.mp4")
        val face = StreamRegion(0f, 0f, .28f, .36f)
        val game = StreamRegion(.40f, .20f, .85f, .80f)
        val tracking = listOf(
            CropFocus(0L, .12f, .18f, face.width, face.height),
            CropFocus(1_000L, .15f, .20f, face.width, face.height)
        )
        val arguments = StreamerGameplayRenderer.arguments(input, output, 0L, 2_000L, face, 1920, 1080, game, tracking)
        val filter = arguments[arguments.indexOf("-filter_complex") + 1]
        assertTrue(filter.contains("[cam]crop=w="))
        assertTrue(filter.contains("if(lt(t,"))
        assertTrue(filter.contains("[game]crop="))
        assertTrue(filter.contains("[top][bottom]vstack"))
    }

    @Test fun localReviewsBiasMatchingClipsOnly() {
        val prefs = CreatorClipPreferences(List(5) { ClipPreferenceExample(true, 27_000L, true) })
        assertTrue(prefs.adjustment(clip) > 0)
        assertEquals(0, prefs.adjustment(clip.copy(hook = "")))
        assertEquals(0, CreatorClipPreferences().adjustment(clip))
    }
}
