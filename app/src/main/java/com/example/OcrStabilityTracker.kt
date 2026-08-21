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
    private var acceptedSignature: String? = null
    private var pendingSignature: String? = null
    private var pendingCount = 0

    init {
        require(requiredConsecutiveObservations > 0) {
            "requiredConsecutiveObservations must be positive"
        }
    }

    fun observe(regions: List<OcrTextRegion>): OcrUpdateDecision {
        val signature = signatureFor(regions)
        if (signature == acceptedSignature) {
            pendingSignature = null
            pendingCount = 0
            return OcrUpdateDecision.UNCHANGED
        }

        if (signature == pendingSignature) {
            pendingCount++
        } else {
            pendingSignature = signature
            pendingCount = 1
        }

        if (pendingCount < requiredConsecutiveObservations) {
            return OcrUpdateDecision.WAITING_FOR_STABLE_TEXT
        }

        acceptedSignature = signature
        pendingSignature = null
        pendingCount = 0
        return if (signature.isEmpty()) {
            OcrUpdateDecision.CLEARED
        } else {
            OcrUpdateDecision.CHANGED
        }
    }

    fun reset() {
        acceptedSignature = null
        pendingSignature = null
        pendingCount = 0
    }

    private fun signatureFor(regions: List<OcrTextRegion>): String =
        regions.joinToString(separator = " ") { it.text }
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
