package com.example

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

internal class OcrManager {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): OcrSnapshot {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()
        var lines = 0
        for (block in result.textBlocks) {
            lines += block.lines.size
        }
        val regions = result.textBlocks.mapNotNull { block ->
            val text = block.text.trim()
            if (text.isEmpty()) return@mapNotNull null

            val boundingBox = block.boundingBox ?: unionOf(
                block.lines.mapNotNull { it.boundingBox },
            ) ?: return@mapNotNull null
            OcrTextRegion(
                text = text,
                bounds = OcrBounds(
                    left = boundingBox.left,
                    top = boundingBox.top,
                    right = boundingBox.right,
                    bottom = boundingBox.bottom,
                ),
            )
        }.sortedWith(
            compareBy<OcrTextRegion> { it.bounds.top }
                .thenBy { it.bounds.left },
        )
        val positionedRegions = if (regions.isNotEmpty() || result.text.isBlank()) {
            regions
        } else {
            listOf(
                OcrTextRegion(
                    text = result.text.trim(),
                    bounds = OcrBounds(
                        left = 0,
                        top = 0,
                        right = bitmap.width,
                        bottom = bitmap.height,
                    ),
                ),
            )
        }

        return OcrSnapshot(
            text = positionedRegions.joinToString(separator = "\n") { it.text },
            blockCount = result.textBlocks.size,
            lineCount = lines,
            regions = positionedRegions,
        )
    }

    private fun unionOf(rectangles: List<Rect>): Rect? {
        val first = rectangles.firstOrNull() ?: return null
        return Rect(first).also { union ->
            rectangles.drop(1).forEach { rectangle ->
                union.union(rectangle)
            }
        }
    }

    fun close() {
        recognizer.close()
    }
}
