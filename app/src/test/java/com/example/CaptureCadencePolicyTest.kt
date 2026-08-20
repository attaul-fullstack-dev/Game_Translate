package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCadencePolicyTest {
    @Test
    fun `pending OCR gets a fast second observation`() {
        assertEquals(
            CaptureCadencePolicy.STABILITY_RECHECK_MS,
            CaptureCadencePolicy.delayAfter(
                OcrUpdateDecision.WAITING_FOR_STABLE_TEXT,
            ),
        )
        assertTrue(CaptureCadencePolicy.STABILITY_RECHECK_MS < 100L)
    }

    @Test
    fun `stable capture loop stays throttled and serial`() {
        assertEquals(
            CaptureCadencePolicy.NORMAL_INTERVAL_MS,
            CaptureCadencePolicy.delayAfter(OcrUpdateDecision.UNCHANGED),
        )
        assertEquals(
            CaptureCadencePolicy.NORMAL_INTERVAL_MS,
            CaptureCadencePolicy.delayAfter(OcrUpdateDecision.CHANGED),
        )
        assertTrue(CaptureCadencePolicy.NORMAL_INTERVAL_MS >= 200L)
    }
}
