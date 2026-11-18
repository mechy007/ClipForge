package com.clipforge.app

import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAwareCropFilterTest {
    @Test fun cropFilterUsesTimedFaceCoordinates() {
        val path = listOf(
            CropFocus(0L, .25f, .5f, .3164f, 1f),
            CropFocus(2_000L, .75f, .5f, .3164f, 1f)
        )
        val filter = ClipExporter.buildFaceCropFilter(path, 0L, 2_000L)
        assertTrue(filter.startsWith("crop=w=iw*"))
        assertTrue(filter.contains("eval=frame"))
        assertTrue(filter.contains("if(lt(t,2.000000)"))
        assertTrue(filter.contains("0.250000"))
        assertTrue(filter.contains("0.750000"))
    }
}
