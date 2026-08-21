package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAreaSelectionPolicyTest {
    @Test
    fun `touching edges are allowed but intersecting areas are rejected`() {
        val ocrArea = ScreenArea(x = 100, y = 500, width = 800, height = 250)

        assertFalse(
            ScreenAreaSelectionPolicy.overlaps(
                ocrArea,
                ScreenArea(x = 100, y = 300, width = 800, height = 200),
            ),
        )
        assertTrue(
            ScreenAreaSelectionPolicy.overlaps(
                ocrArea,
                ScreenArea(x = 100, y = 450, width = 800, height = 200),
            ),
        )
    }

    @Test
    fun `default panel stays on screen and outside bottom dialogue area`() {
        val ocrArea = ScreenArea(x = 200, y = 700, width = 1_600, height = 300)
        val panel = ScreenAreaSelectionPolicy.defaultTranslationPanel(
            ocrArea = ocrArea,
            displayWidth = 2_000,
            displayHeight = 1_080,
            minimumWidth = 400,
            minimumHeight = 120,
            margin = 24,
        )

        assertTrue(
            ScreenAreaSelectionPolicy.isInsideDisplay(
                area = panel,
                displayWidth = 2_000,
                displayHeight = 1_080,
            ),
        )
        assertFalse(ScreenAreaSelectionPolicy.overlaps(ocrArea, panel))
    }
}
