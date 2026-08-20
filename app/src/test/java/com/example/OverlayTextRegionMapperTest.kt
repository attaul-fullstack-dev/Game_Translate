package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayTextRegionMapperTest {
    @Test
    fun `OCR crop bounds map to normalized overlay coordinates`() {
        val mapped = OverlayTextRegionMapper.map(
            source = OcrTextRegion(
                text = "PLAY",
                bounds = OcrBounds(
                    left = 100,
                    top = 50,
                    right = 300,
                    bottom = 150,
                ),
            ),
            translatedText = "Mainkan",
            imageWidth = 1_000,
            imageHeight = 500,
        )

        assertEquals("Mainkan", mapped.text)
        assertEquals(0.1f, mapped.leftFraction, 0.0001f)
        assertEquals(0.1f, mapped.topFraction, 0.0001f)
        assertEquals(0.2f, mapped.widthFraction, 0.0001f)
        assertEquals(0.2f, mapped.heightFraction, 0.0001f)
    }

    @Test
    fun `bounds are clamped to the captured bitmap`() {
        val mapped = OverlayTextRegionMapper.map(
            source = OcrTextRegion(
                text = "OPTIONS",
                bounds = OcrBounds(
                    left = -10,
                    top = -20,
                    right = 1_200,
                    bottom = 600,
                ),
            ),
            translatedText = "Opsi",
            imageWidth = 1_000,
            imageHeight = 500,
        )

        assertEquals(0f, mapped.leftFraction, 0.0001f)
        assertEquals(0f, mapped.topFraction, 0.0001f)
        assertEquals(1f, mapped.widthFraction, 0.0001f)
        assertEquals(1f, mapped.heightFraction, 0.0001f)
    }
}
