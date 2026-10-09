package com.shilapi.xcertplay.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapTouchTranslatorTest {
    private val wide = AapVideoConfig(AapVideoResolution.R1920X1080, 30, widthMargin = 0, heightMargin = 360, density = 240)

    @Test fun aMatchingContainerMapsOneToOneAndCropsTheMargins() {
        val translator = AapTouchTranslator(wide).apply { layout(1920, 720) }
        assertEquals(1920, translator.streamWidth)
        assertEquals(1080, translator.streamHeight)
        assertEquals(0, translator.streamLeft)
        assertEquals(-180, translator.streamTop) // the top margin sits above the container
        assertEquals(960 to 540, translator.translate(960f, 360f))
        assertEquals(0 to 180, translator.translate(0f, 0f))
        assertEquals(1919 to 899, translator.translate(1919.5f, 719.5f))
    }

    @Test fun aSmallerContainerScalesTheTouches() {
        val translator = AapTouchTranslator(wide).apply { layout(960, 360) }
        assertEquals(960, translator.streamWidth)
        assertEquals(540, translator.streamHeight)
        assertEquals(960 to 540, translator.translate(480f, 180f))
    }

    @Test fun aTallerContainerLetterboxesAndIgnoresTouchesOnTheBars() {
        val translator = AapTouchTranslator(wide).apply { layout(1920, 1080) }
        // Fit by width: the 1920x720 picture sits in the middle with 180px bars above and below.
        assertNull(translator.translate(100f, 100f))
        assertNull(translator.translate(100f, 1000f))
        assertEquals(960 to 540, translator.translate(960f, 540f))
        assertEquals(-0, translator.streamLeft)
        assertEquals(0, translator.streamTop)
    }

    @Test fun touchesOutsideTheContainerAreIgnored() {
        val translator = AapTouchTranslator(wide).apply { layout(1920, 720) }
        assertNull(translator.translate(-1f, 10f))
        assertNull(translator.translate(1921f, 10f))
        assertNull(translator.translate(10f, 721f))
        assertEquals(1919 to 899, translator.translate(1920f, 719.9f)) // half a pixel of slack at the edge
    }

    @Test fun nothingMapsBeforeALayoutExists() {
        assertNull(AapTouchTranslator(wide).translate(1f, 1f))
        val translator = AapTouchTranslator(wide).apply { layout(0, 0) }
        assertNull(translator.translate(1f, 1f))
    }

    @Test fun horizontalMarginsShiftTheStreamAndKeepTouchesInsideIt() {
        val narrow = AapVideoConfig(AapVideoResolution.R1280X720, 30, widthMargin = 51, heightMargin = 0, density = 190)
        val translator = AapTouchTranslator(narrow).apply { layout(1024, 600) }
        val (x, y) = translator.translate(0f, 0f)!!
        assertEquals(25, x) // half of the margin
        assertEquals(0, y)
        val (rx, ry) = translator.translate(1023f, 599f)!!
        assertTrue(rx in 0 until 1280 && ry in 0 until 720)
        assertTrue(translator.streamLeft < 0)
    }

    @Test fun aTouchInProgressIsClampedToTheEdgeOfThePicture() {
        val translator = AapTouchTranslator(wide).apply { layout(1920, 1080) } // 180px bars top and bottom
        assertNull(translator.translate(100f, 50f))
        assertEquals(100 to 180, translator.translateClamped(100f, 50f)) // top bar -> top row of the picture
        assertEquals(100 to 899, translator.translateClamped(100f, 1070f)) // bottom bar -> bottom row
        assertEquals(1919 to 540, translator.translateClamped(5000f, 540f))
        assertEquals(0 to 180, translator.translateClamped(-5f, -5f))
    }

    @Test fun clampingBeforeALayoutFallsBackToTheOrigin() {
        assertEquals(0 to 180, AapTouchTranslator(wide).translateClamped(10f, 10f))
    }
}
