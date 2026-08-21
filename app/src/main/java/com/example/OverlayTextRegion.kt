package com.example

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

}

internal data class OverlayTextRegion(
    val text: String,
    val sourceBounds: NormalizedBounds,
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
