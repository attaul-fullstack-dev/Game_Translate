package com.example

internal data class OcrBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class OcrTextRegion(
    val text: String,
    val bounds: OcrBounds,
)

internal data class OcrSnapshot(
    val text: String,
    val blockCount: Int,
    val lineCount: Int,
    val regions: List<OcrTextRegion>,
)
