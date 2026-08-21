package com.example

internal data class ScreenArea(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int
        get() = x + width

    val bottom: Int
        get() = y + height
}

internal object ScreenAreaSelectionPolicy {
    fun overlaps(first: ScreenArea, second: ScreenArea): Boolean =
        first.x < second.right &&
            first.right > second.x &&
            first.y < second.bottom &&
            first.bottom > second.y

    fun isInsideDisplay(
        area: ScreenArea,
        displayWidth: Int,
        displayHeight: Int,
    ): Boolean =
        area.width > 0 &&
            area.height > 0 &&
            area.x >= 0 &&
            area.y >= 0 &&
            area.right <= displayWidth &&
            area.bottom <= displayHeight

    fun defaultTranslationPanel(
        ocrArea: ScreenArea,
        displayWidth: Int,
        displayHeight: Int,
        minimumWidth: Int,
        minimumHeight: Int,
        margin: Int,
    ): ScreenArea {
        val maximumMargin =
            ((minOf(displayWidth, displayHeight) - 1).coerceAtLeast(0) / 2)
        val safeMargin = margin.coerceIn(0, maximumMargin)
        val availableWidth = (displayWidth - safeMargin * 2).coerceAtLeast(1)
        val availableHeight = (displayHeight - safeMargin * 2).coerceAtLeast(1)
        val width = maxOf(ocrArea.width, minimumWidth)
            .coerceAtMost(availableWidth)
        val height = maxOf((displayHeight * 0.18f).toInt(), minimumHeight)
            .coerceAtMost(availableHeight)
        val x = (ocrArea.x + (ocrArea.width - width) / 2)
            .coerceIn(safeMargin, (displayWidth - safeMargin - width).coerceAtLeast(safeMargin))

        val candidates = listOf(
            ScreenArea(
                x = x,
                y = (ocrArea.y - safeMargin - height).coerceAtLeast(safeMargin),
                width = width,
                height = height,
            ),
            ScreenArea(
                x = x,
                y = (ocrArea.bottom + safeMargin)
                    .coerceAtMost((displayHeight - safeMargin - height).coerceAtLeast(safeMargin)),
                width = width,
                height = height,
            ),
            ScreenArea(
                x = x,
                y = safeMargin,
                width = width,
                height = height,
            ),
            ScreenArea(
                x = x,
                y = (displayHeight - safeMargin - height).coerceAtLeast(safeMargin),
                width = width,
                height = height,
            ),
        ).distinct()

        return candidates.firstOrNull { candidate ->
            isInsideDisplay(candidate, displayWidth, displayHeight) &&
                !overlaps(ocrArea, candidate)
        } ?: candidates.first()
    }
}
