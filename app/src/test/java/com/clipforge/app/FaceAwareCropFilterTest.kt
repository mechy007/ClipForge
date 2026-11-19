package com.clipforge.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAwareCropFilterTest {
    @Test fun cropFilterUsesTimedFaceCoordinates() {
        val path = listOf(
            CropFocus(0L, .25f, .5f, .3164f, 1f),
            CropFocus(2_000L, .75f, .5f, .3164f, 1f)
        )
        val filter = FaceCropFilterBuilder.build(path, 0L, 2_000L)
        assertTrue(filter.startsWith("crop=w='max(2,trunc(iw*0.316400/2)*2)':h='max(2,trunc(ih*1.000000/2)*2)'"))
        assertFalse(filter.contains("eval="))
        assertTrue(filter.contains("if(lt(t,2.000000)"))
        assertTrue(filter.contains("0.250000"))
        assertTrue(filter.contains("0.750000"))
    }
}
