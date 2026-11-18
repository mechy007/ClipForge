package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneChangeAnalyzerTest {
    @Test fun identicalFramesHaveNoDifference() {
        assertEquals(0f, SceneChangeAnalyzer.difference(floatArrayOf(.1f, .5f), floatArrayOf(.1f, .5f)), .0001f)
    }

    @Test fun largeFrameChangeIsDetectedByDifferenceMetric() {
        assertEquals(.4f, SceneChangeAnalyzer.difference(floatArrayOf(.1f, .2f), floatArrayOf(.9f, .2f)), .0001f)
    }
}
