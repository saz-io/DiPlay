package com.shilapi.xcertplay.androidauto

import org.junit.Assert.assertEquals
import org.junit.Test

class AapDisplayPlannerTest {
    @Test fun anUltraWideScreenUsesFullHdWithVerticalMargins() {
        val plan = AapDisplayPlanner.plan(1920, 720, 240, 30)
        assertEquals(AapVideoResolution.R1920X1080, plan.resolution)
        assertEquals(0, plan.widthMargin)
        assertEquals(360, plan.heightMargin)
        assertEquals(240, plan.density)
        assertEquals(30, plan.framesPerSecond)
    }

    @Test fun aSixteenByNineScreenNeedsNoMargins() {
        val plan = AapDisplayPlanner.plan(1280, 720, 160, 60)
        assertEquals(AapVideoResolution.R1280X720, plan.resolution)
        assertEquals(0, plan.widthMargin)
        assertEquals(0, plan.heightMargin)
        assertEquals(60, plan.framesPerSecond)
    }

    @Test fun theSmallestSufficientTierIsChosen() {
        assertEquals(AapVideoResolution.R800X480, AapDisplayPlanner.plan(800, 480, 160, 30).resolution)
        assertEquals(AapVideoResolution.R800X480, AapDisplayPlanner.plan(640, 384, 160, 30).resolution)
    }

    @Test fun aNarrowerScreenGetsHorizontalMarginsAndAScaledDensity() {
        val plan = AapDisplayPlanner.plan(1024, 600, 160, 30)
        assertEquals(AapVideoResolution.R1280X720, plan.resolution)
        assertEquals(1280 - 1229, plan.widthMargin)
        assertEquals(0, plan.heightMargin)
        assertEquals(192, plan.density)
    }

    @Test fun aScreenBeyondFullHdKeepsTheLargestTier() {
        val plan = AapDisplayPlanner.plan(2560, 1080, 160, 30)
        assertEquals(AapVideoResolution.R1920X1080, plan.resolution)
        assertEquals(270, plan.heightMargin)
        assertEquals(120, plan.density)
    }

    @Test fun visibleAreaAlwaysKeepsTheDisplayAspectRatio() {
        for ((w, h) in listOf(1920 to 720, 1280 to 720, 1024 to 600, 800 to 480, 2160 to 1080, 1280 to 800)) {
            val plan = AapDisplayPlanner.plan(w, h, 200, 30)
            val visible = plan.visibleWidth.toDouble() / plan.visibleHeight
            assertEquals("aspect for ${w}x$h", w.toDouble() / h, visible, 0.01)
        }
    }

    @Test fun densityIsClamped() {
        assertEquals(100, AapDisplayPlanner.plan(1280, 720, 20, 30).density)
        assertEquals(400, AapDisplayPlanner.plan(1280, 720, 900, 30).density)
    }
}
