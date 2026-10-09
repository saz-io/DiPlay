package com.shilapi.xcertplay.androidauto

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Places the video in a view and maps touches back to the phone's coordinate space.
 *
 * The stream is larger than the visible picture by the declared margins. The view shows only the
 * visible part, scaled to fit and centred, so the margins fall outside the view and are cropped.
 */
class AapTouchTranslator(private val config: AapVideoConfig) {
    private var scale = 1f
    private var boxLeft = 0f
    private var boxTop = 0f
    private var boxWidth = 0f
    private var boxHeight = 0f

    /** Size of the whole stream after scaling, for sizing the video view. */
    var streamWidth = 0
        private set
    var streamHeight = 0
        private set

    /** Where the video view's top-left corner sits in the container; negative when margins are cropped. */
    var streamLeft = 0
        private set
    var streamTop = 0
        private set

    fun layout(containerWidth: Int, containerHeight: Int) {
        if (containerWidth <= 0 || containerHeight <= 0) return
        scale = min(containerWidth.toFloat() / config.visibleWidth, containerHeight.toFloat() / config.visibleHeight)
        boxWidth = config.visibleWidth * scale
        boxHeight = config.visibleHeight * scale
        boxLeft = (containerWidth - boxWidth) / 2f
        boxTop = (containerHeight - boxHeight) / 2f
        streamWidth = (config.resolution.width * scale).roundToInt()
        streamHeight = (config.resolution.height * scale).roundToInt()
        streamLeft = (boxLeft - config.widthMargin / 2f * scale).roundToInt()
        streamTop = (boxTop - config.heightMargin / 2f * scale).roundToInt()
    }

    /**
     * Like [translate], but a point outside the picture is moved to its nearest edge. Used for a
     * touch that is already in progress, which must still end where the phone expects it.
     */
    fun translateClamped(x: Float, y: Float): Pair<Int, Int> {
        if (boxWidth <= 0f || boxHeight <= 0f) return config.widthMargin / 2 to config.heightMargin / 2
        val cx = x.coerceIn(boxLeft, boxLeft + boxWidth - 0.001f)
        val cy = y.coerceIn(boxTop, boxTop + boxHeight - 0.001f)
        return translate(cx, cy) ?: (config.widthMargin / 2 to config.heightMargin / 2)
    }

    /** The touch position in stream pixels, or null when the touch is outside the picture. */
    fun translate(x: Float, y: Float): Pair<Int, Int>? {
        if (boxWidth <= 0f || boxHeight <= 0f) return null
        // Scaling rounds the picture by a fraction of a pixel; touches on the very edge must still count.
        if (x < boxLeft - EDGE_TOLERANCE || y < boxTop - EDGE_TOLERANCE ||
            x >= boxLeft + boxWidth + EDGE_TOLERANCE || y >= boxTop + boxHeight + EDGE_TOLERANCE
        ) return null
        val streamX = config.widthMargin / 2f + (x - boxLeft) / scale
        val streamY = config.heightMargin / 2f + (y - boxTop) / scale
        val minX = config.widthMargin / 2
        val minY = config.heightMargin / 2
        return streamX.toInt().coerceIn(minX, config.resolution.width - 1) to
            streamY.toInt().coerceIn(minY, config.resolution.height - 1)
    }

    private companion object {
        const val EDGE_TOLERANCE = 0.5f
    }
}
