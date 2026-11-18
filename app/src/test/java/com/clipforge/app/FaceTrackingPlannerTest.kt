package com.clipforge.app

import org.junit.Assert.*
import org.junit.Test

class FaceTrackingPlannerTest {
    @Test fun emptyObservationsProduceNoCropPath() {
        assertTrue(FaceTrackingPlanner.plan(emptyList()).isEmpty())
    }

    @Test fun portraitCropTracksFaceWithinFrameBounds() {
        val observations = listOf(
            FaceObservation(0, NormalizedFace(.2f, .2f, .4f, .5f)),
            FaceObservation(1_000, NormalizedFace(.6f, .2f, .8f, .5f))
        )
        val path = FaceTrackingPlanner.plan(observations)
        assertEquals(2, path.size)
        assertTrue(path.all { it.centerX in 0f..1f && it.centerY in 0f..1f })
        assertTrue(path.last().centerX > path.first().centerX)
    }

    @Test fun lowConfidenceFacesAreIgnored() {
        val path = FaceTrackingPlanner.plan(listOf(
            FaceObservation(0, NormalizedFace(.2f, .2f, .4f, .5f, .1f))
        ))
        assertTrue(path.isEmpty())
    }
}
