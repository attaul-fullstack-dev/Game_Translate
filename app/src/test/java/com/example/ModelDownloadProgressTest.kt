package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadProgressTest {
    @Test
    fun `model count only reports models that are actually ready`() {
        val partial = ModelDownloadProgress(
            phase = ModelDownloadPhase.DOWNLOADING,
            currentModel = TranslationModel.INDONESIAN,
            readyModels = setOf(TranslationModel.ENGLISH),
        )

        assertEquals(1, partial.completedCount)
        assertEquals(2, partial.totalCount)
        assertFalse(partial.allReady)
    }

    @Test
    fun `all ready requires both translation models`() {
        val ready = ModelDownloadProgress(
            phase = ModelDownloadPhase.READY,
            readyModels = TranslationModel.entries.toSet(),
        )

        assertTrue(ready.allReady)
    }

    @Test
    fun `elapsed time formats live heartbeat without fake percentage`() {
        assertEquals("00:00", formatElapsedTime(-1))
        assertEquals("01:05", formatElapsedTime(65))
        assertEquals("01:01:01", formatElapsedTime(3_661))
    }
}
