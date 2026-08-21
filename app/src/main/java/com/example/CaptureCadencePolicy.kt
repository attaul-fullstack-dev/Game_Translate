package com.example

internal object CaptureCadencePolicy {
    const val INITIAL_SETTLE_MS = 350L
    const val NORMAL_INTERVAL_MS = 250L
    const val STABILITY_RECHECK_MS = 75L

    fun delayAfter(decision: OcrUpdateDecision): Long =
        if (decision == OcrUpdateDecision.WAITING_FOR_STABLE_TEXT) {
            STABILITY_RECHECK_MS
        } else {
            NORMAL_INTERVAL_MS
        }
}
