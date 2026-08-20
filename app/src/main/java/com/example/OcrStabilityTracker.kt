package com.example

import java.util.Locale

internal enum class OcrUpdateDecision {
    WAITING_FOR_STABLE_TEXT,
    CHANGED,
    UNCHANGED,
    CLEARED,
}

/**
 * Filters one-frame OCR noise. A different text snapshot must be observed
 * repeatedly before it is allowed to replace the currently displayed result.
 */
internal class OcrStabilityTracker(
    private val requiredConsecutiveObservations: Int = 2,
) {
    private var acceptedSnapshot: List<OcrTextRegion>? = null
    private var pendingSnapshot: List<OcrTextRegion>? = null
    private var pendingCount = 0

    init {
        require(requiredConsecutiveObservations > 0) {
            "requiredConsecutiveObservations must be positive"
        }
    }

    fun observe(regions: List<OcrTextRegion>): OcrUpdateDecision {
        if (snapshotsEquivalent(regions, acceptedSnapshot)) {
            pendingSnapshot = null
            pendingCount = 0
            return OcrUpdateDecision.UNCHANGED
        }

        if (snapshotsEquivalent(regions, pendingSnapshot)) {
            pendingCount++
        } else {
            pendingSnapshot = regions
            pendingCount = 1
        }

        if (pendingCount < requiredConsecutiveObservations) {
            return OcrUpdateDecision.WAITING_FOR_STABLE_TEXT
        }

        acceptedSnapshot = regions
        pendingSnapshot = null
        pendingCount = 0
        return if (regions.isEmpty()) {
            OcrUpdateDecision.CLEARED
        } else {
            OcrUpdateDecision.CHANGED
        }
    }

    fun reset() {
        acceptedSnapshot = null
        pendingSnapshot = null
        pendingCount = 0
    }

    private fun snapshotsEquivalent(
        first: List<OcrTextRegion>,
        second: List<OcrTextRegion>?,
    ): Boolean {
        if (second == null || first.size != second.size) return false
        return first.zip(second).all { (left, right) ->
            normalizedText(left.text) == normalizedText(right.text) &&
                boundsEquivalent(left.bounds, right.bounds)
        }
    }

    private fun boundsEquivalent(first: OcrBounds, second: OcrBounds): Boolean {
        val firstCenterX = (first.left + first.right) / 2
        val firstCenterY = (first.top + first.bottom) / 2
        val secondCenterX = (second.left + second.right) / 2
        val secondCenterY = (second.top + second.bottom) / 2
        val firstWidth = first.right - first.left
        val firstHeight = first.bottom - first.top
        val secondWidth = second.right - second.left
        val secondHeight = second.bottom - second.top

        return kotlin.math.abs(firstCenterX - secondCenterX) <= POSITION_TOLERANCE_PX &&
            kotlin.math.abs(firstCenterY - secondCenterY) <= POSITION_TOLERANCE_PX &&
            kotlin.math.abs(firstWidth - secondWidth) <= SIZE_TOLERANCE_PX &&
            kotlin.math.abs(firstHeight - secondHeight) <= SIZE_TOLERANCE_PX
    }

    private fun normalizedText(text: String): String = text
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

    private companion object {
        const val POSITION_TOLERANCE_PX = 16
        const val SIZE_TOLERANCE_PX = 16
    }
}
