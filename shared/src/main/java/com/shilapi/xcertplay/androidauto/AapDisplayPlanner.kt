package com.shilapi.xcertplay.androidauto

import kotlin.math.roundToInt

/**
 * Chooses the stream size, margins and density that make Android Auto fill a head-unit screen.
 *
 * The phone renders at one of a few fixed resolutions. When the screen has another aspect ratio,
 * the head unit declares margins: the phone keeps its UI out of them, and the margins are cropped
 * away on the head unit so the visible part maps one-to-one onto the display.
 */
object AapDisplayPlanner {
    /** Ascending, so the first tier that is wide enough is the cheapest sufficient one. */
    private val TIERS = listOf(AapVideoResolution.R800X480, AapVideoResolution.R1280X720, AapVideoResolution.R1920X1080)

    private const val MIN_DENSITY = 100
    private const val MAX_DENSITY = 400

    fun plan(displayWidthPx: Int, displayHeightPx: Int, displayDensityDpi: Int, framesPerSecond: Int): AapVideoConfig {
        require(displayWidthPx > 0 && displayHeightPx > 0) { "The display must have a size" }
        val aspect = displayWidthPx.toDouble() / displayHeightPx
        val resolution = TIERS.firstOrNull { visibleSize(it, aspect).first >= displayWidthPx } ?: TIERS.last()
        val (visibleWidth, visibleHeight) = visibleSize(resolution, aspect)
        // Density follows the stream's pixels per inch, not the display's, so the UI keeps its physical size.
        val density = (displayDensityDpi * visibleWidth.toDouble() / displayWidthPx).roundToInt()
            .coerceIn(MIN_DENSITY, MAX_DENSITY)
        return AapVideoConfig(
            resolution = resolution,
            framesPerSecond = if (framesPerSecond >= 60) 60 else 30,
            widthMargin = resolution.width - visibleWidth,
            heightMargin = resolution.height - visibleHeight,
            density = density,
        )
    }

    /** The largest region of [resolution] that has the display's [aspect] ratio. */
    internal fun visibleSize(resolution: AapVideoResolution, aspect: Double): Pair<Int, Int> {
        val streamAspect = resolution.width.toDouble() / resolution.height
        return if (aspect >= streamAspect) {
            resolution.width to (resolution.width / aspect).roundToInt().coerceIn(1, resolution.height)
        } else {
            (resolution.height * aspect).roundToInt().coerceIn(1, resolution.width) to resolution.height
        }
    }
}
