package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(0.1f, mapped.sourceBounds.left, 0.0001f)
        assertEquals(0.1f, mapped.sourceBounds.top, 0.0001f)
        assertEquals(0.2f, mapped.sourceBounds.width, 0.0001f)
        assertEquals(0.2f, mapped.sourceBounds.height, 0.0001f)
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

        assertEquals(0f, mapped.sourceBounds.left, 0.0001f)
        assertEquals(0f, mapped.sourceBounds.top, 0.0001f)
        assertEquals(1f, mapped.sourceBounds.width, 0.0001f)
        assertEquals(1f, mapped.sourceBounds.height, 0.0001f)
    }

    @Test
    fun `translated boxes stay centered over their source text`() {
        val regions = listOf(
            overlayRegion(
                text = "Bermain",
                sourceBounds = NormalizedBounds(0.2f, 0.2f, 0.45f, 0.3f),
            ),
            overlayRegion(
                text = "Bantuan dan Opsi",
                sourceBounds = NormalizedBounds(0.2f, 0.5f, 0.55f, 0.6f),
            ),
        )

        val placed = OverlayPlacementEngine.place(
            regions = regions,
            minimumWidthFraction = 0.2f,
            minimumHeightFraction = 0.1f,
        )
        placed.zip(regions).forEach { (result, original) ->
            assertTrue(result.displayBounds.left >= 0f)
            assertTrue(result.displayBounds.top >= 0f)
            assertTrue(result.displayBounds.right <= 1f)
            assertTrue(result.displayBounds.bottom <= 1f)
            assertTrue(
                result.displayBounds.contains(
                    original.sourceBounds.centerX,
                    original.sourceBounds.centerY,
                ),
            )
            assertTrue(
                result.displayBounds.intersectionArea(original.sourceBounds) > 0f,
            )
        }
    }

    private fun overlayRegion(
        text: String,
        sourceBounds: NormalizedBounds,
    ) = OverlayTextRegion(
        text = text,
        sourceBounds = sourceBounds,
    )
}
