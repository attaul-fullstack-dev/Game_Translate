package com.example

internal data class OverlayTextRegion(
    val text: String,
    val leftFraction: Float,
    val topFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
)

internal object OverlayTextRegionMapper {
    fun map(
        source: OcrTextRegion,
        translatedText: String,
        imageWidth: Int,
        imageHeight: Int,
    ): OverlayTextRegion {
        require(imageWidth > 0 && imageHeight > 0) {
            "image dimensions must be positive"
        }

        val left = source.bounds.left.coerceIn(0, imageWidth - 1)
        val top = source.bounds.top.coerceIn(0, imageHeight - 1)
        val right = source.bounds.right.coerceIn(left + 1, imageWidth)
        val bottom = source.bounds.bottom.coerceIn(top + 1, imageHeight)

        return OverlayTextRegion(
            text = translatedText.trim(),
            leftFraction = left.toFloat() / imageWidth,
            topFraction = top.toFloat() / imageHeight,
            widthFraction = (right - left).toFloat() / imageWidth,
            heightFraction = (bottom - top).toFloat() / imageHeight,
        )
    }
}
