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

    @Test fun cropDimensionsRespectLandscapeSourceAspect() {
        val observations = listOf(
            FaceObservation(0L, NormalizedFace(0.4f, 0.2f, 0.6f, 0.5f))
        )
        val focus = FaceTrackingPlanner.plan(
            observations, targetAspectRatio = 9f / 16f, sourceAspectRatio = 16f / 9f
        ).single()
        assertEquals(0.31640625f, focus.width, 0.0001f)
        assertEquals(1f, focus.height, 0.0001f)
    }

    @Test fun lowConfidenceFacesAreIgnored() {
        val path = FaceTrackingPlanner.plan(listOf(
            FaceObservation(0, NormalizedFace(.2f, .2f, .4f, .5f, .1f))
        ))
        assertTrue(path.isEmpty())
    }
}
