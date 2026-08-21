package com.example

import kotlin.math.sqrt

/** Bounds MediaProjection buffers while preserving their aspect ratio. */
internal object CaptureSizeLimiter {
    const val DEFAULT_MAX_PIXELS = 2_500_000L

    fun limit(
        width: Int,
        height: Int,
        maxPixels: Long = DEFAULT_MAX_PIXELS,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "capture dimensions must be positive" }
        require(maxPixels > 0) { "maxPixels must be positive" }

        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return width to height

        val scale = sqrt(maxPixels.toDouble() / pixels.toDouble())
        var limitedWidth = (width * scale).toInt().coerceAtLeast(1)
        var limitedHeight = (height * scale).toInt().coerceAtLeast(1)

        while (limitedWidth.toLong() * limitedHeight.toLong() > maxPixels) {
            if (limitedWidth >= limitedHeight) limitedWidth-- else limitedHeight--
        }
        return limitedWidth to limitedHeight
    }
}
