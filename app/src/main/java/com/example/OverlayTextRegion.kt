package com.example

import kotlin.math.ceil

internal data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = (right - left).coerceAtLeast(0f)

    val height: Float
        get() = (bottom - top).coerceAtLeast(0f)

    val area: Float
        get() = width * height

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f

    fun intersectionArea(other: NormalizedBounds): Float {
        val intersectionWidth =
            (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val intersectionHeight =
            (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        return intersectionWidth * intersectionHeight
    }

    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom

    companion object {
        fun positioned(
            left: Float,
            top: Float,
            width: Float,
            height: Float,
        ): NormalizedBounds {
            val safeWidth = width.coerceIn(0.001f, 1f)
            val safeHeight = height.coerceIn(0.001f, 1f)
            val safeLeft = left.coerceIn(0f, 1f - safeWidth)
            val safeTop = top.coerceIn(0f, 1f - safeHeight)
            return NormalizedBounds(
                left = safeLeft,
                top = safeTop,
                right = safeLeft + safeWidth,
                bottom = safeTop + safeHeight,
            )
        }
    }
}

internal data class OverlayTextRegion(
    val text: String,
    val sourceBounds: NormalizedBounds,
    val displayBounds: NormalizedBounds = sourceBounds,
)

internal object OverlayTextRegionMapper {
    fun boundsFor(
        source: OcrTextRegion,
        imageWidth: Int,
        imageHeight: Int,
    ): NormalizedBounds {
        require(imageWidth > 0 && imageHeight > 0) {
            "image dimensions must be positive"
        }

        val left = source.bounds.left.coerceIn(0, imageWidth - 1)
        val top = source.bounds.top.coerceIn(0, imageHeight - 1)
        val right = source.bounds.right.coerceIn(left + 1, imageWidth)
        val bottom = source.bounds.bottom.coerceIn(top + 1, imageHeight)
        return NormalizedBounds(
            left = left.toFloat() / imageWidth,
            top = top.toFloat() / imageHeight,
            right = right.toFloat() / imageWidth,
            bottom = bottom.toFloat() / imageHeight,
        )
    }

    fun map(
        source: OcrTextRegion,
        translatedText: String,
        imageWidth: Int,
        imageHeight: Int,
    ): OverlayTextRegion {
        val sourceBounds = boundsFor(
            source = source,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )

        return OverlayTextRegion(
            text = translatedText.trim(),
            sourceBounds = sourceBounds,
        )
    }
}

/** Places translated text over the OCR source while keeping the source center. */
internal object OverlayPlacementEngine {
    fun place(
        regions: List<OverlayTextRegion>,
        minimumWidthFraction: Float,
        minimumHeightFraction: Float,
    ): List<OverlayTextRegion> {
        if (regions.isEmpty()) return emptyList()
        val minimumWidth = minimumWidthFraction.coerceIn(0.08f, 0.65f)
        val minimumHeight = minimumHeightFraction.coerceIn(0.04f, 0.35f)

        return regions.map { region ->
            val source = region.sourceBounds
            val estimatedTextWidth =
                (region.text.length.coerceAtMost(42) * 0.014f + 0.06f)
                    .coerceAtMost(0.72f)
            val width = maxOf(
                minimumWidth,
                source.width * 1.15f,
                estimatedTextWidth,
            ).coerceAtMost(0.72f)
            val charactersPerLine =
                (((width - 0.04f).coerceAtLeast(0.08f)) / 0.014f)
                    .toInt()
                    .coerceAtLeast(10)
            val lineCount = ceil(
                region.text.length.toDouble() / charactersPerLine,
            ).toInt().coerceIn(1, 3)
            val singleLineHeight = maxOf(
                minimumHeight,
                source.height * 1.05f,
            )
            val height = (singleLineHeight * lineCount).coerceAtMost(0.42f)

            region.copy(
                displayBounds = NormalizedBounds.positioned(
                    left = source.centerX - width / 2f,
                    top = source.centerY - height / 2f,
                    width = width,
                    height = height,
                ),
            )
        }
    }
}
