package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamerGameplayLayoutTest {
    @Test fun panelsHaveExactlyHalfTheVerticalOutput() {
        assertEquals(1080, StreamerGameplayLayout.OUTPUT_WIDTH)
        assertEquals(1920, StreamerGameplayLayout.OUTPUT_HEIGHT)
        assertEquals(960, StreamerGameplayLayout.PANEL_HEIGHT)
    }

    @Test fun cornerFacecamCropRemainsInsideLandscapeSource() {
        val region = StreamRegion(0.68f, 0f, 1f, 0.38f)
        val crop = StreamerGameplayLayout.facecamCrop(region, 1920, 1080)
        assertTrue(crop.left >= 0f && crop.top >= 0f)
        assertTrue(crop.right <= 1f && crop.bottom <= 1f)
        assertTrue(crop.left <= region.left && crop.right >= region.right)
        assertTrue(crop.top <= region.top && crop.bottom >= region.bottom)
    }

    @Test fun gameplayCropUsesPanelAspectRatio() {
        val crop = StreamerGameplayLayout.gameplayCrop(StreamRegion(0f, 0f, 1f, 1f), 1920, 1080)
        val actual = (crop.width * 1920f) / (crop.height * 1080f)
        assertEquals(1080f / 960f, actual, 0.001f)
    }
}
