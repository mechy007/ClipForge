package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionCutPlannerTest {
    @Test fun causePrecedesFaceReaction() {
        val cuts = ReactionCutPlanner.plan(30_000, 10_000, 13_000, true)
        assertEquals(listOf(ShortView.SPLIT, ShortView.GAMEPLAY_FULL, ShortView.FACECAM_FULL, ShortView.SPLIT), cuts.map { it.view })
        assertTrue(cuts.all { it.endMs - it.startMs >= 800 })
        assertEquals(30_000L, cuts.last().endMs)
    }
    @Test fun noFacecamNeverUsesFaceView() {
        val cuts = ReactionCutPlanner.plan(30_000, 10_000, 13_000, false)
        assertTrue(cuts.none { it.view == ShortView.FACECAM_FULL })
    }
}
