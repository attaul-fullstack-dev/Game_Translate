package com.example

import kotlin.math.roundToInt

internal enum class QuarterTurn {
    CLOCKWISE,
    COUNTER_CLOCKWISE,
}

/** Maps a selected screen rectangle into a capture bitmap's coordinate space. */
internal object ScreenRegionMapper {
    fun map(
        rect: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        quarterTurn: QuarterTurn? = null,
    ): IntArray {
        require(rect.size >= 4) { "rect must contain x, y, width, and height" }
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
        require(targetWidth > 0 && targetHeight > 0) { "target dimensions must be positive" }

        val sourceIsLandscape = sourceWidth > sourceHeight
        val targetIsLandscape = targetWidth > targetHeight
        val needsQuarterTurn = sourceIsLandscape != targetIsLandscape && quarterTurn != null

        val x: Float
        val y: Float
        val width: Float
        val height: Float
        val mappedSourceWidth: Int
        val mappedSourceHeight: Int

        if (needsQuarterTurn) {
            width = rect[3].toFloat()
            height = rect[2].toFloat()
            mappedSourceWidth = sourceHeight
            mappedSourceHeight = sourceWidth
            if (quarterTurn == QuarterTurn.CLOCKWISE) {
                x = (sourceHeight - rect[1] - rect[3]).toFloat()
                y = rect[0].toFloat()
            } else {
                x = rect[1].toFloat()
                y = (sourceWidth - rect[0] - rect[2]).toFloat()
            }
        } else {
            x = rect[0].toFloat()
            y = rect[1].toFloat()
            width = rect[2].toFloat()
            height = rect[3].toFloat()
            mappedSourceWidth = sourceWidth
            mappedSourceHeight = sourceHeight
        }

        val scaleX = targetWidth.toFloat() / mappedSourceWidth
        val scaleY = targetHeight.toFloat() / mappedSourceHeight
        val mappedX = (x * scaleX).roundToInt().coerceIn(0, targetWidth - 1)
        val mappedY = (y * scaleY).roundToInt().coerceIn(0, targetHeight - 1)
        val mappedWidth = (width * scaleX).roundToInt()
            .coerceAtLeast(1)
            .coerceAtMost(targetWidth - mappedX)
        val mappedHeight = (height * scaleY).roundToInt()
            .coerceAtLeast(1)
            .coerceAtMost(targetHeight - mappedY)

        return intArrayOf(mappedX, mappedY, mappedWidth, mappedHeight)
    }
}
