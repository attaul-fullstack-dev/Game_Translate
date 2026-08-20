package com.example

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

internal data class OcrSnapshot(
    val text: String,
    val blockCount: Int,
    val lineCount: Int,
)

internal class OcrManager {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): OcrSnapshot {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()
        var lines = 0
        for (block in result.textBlocks) {
            lines += block.lines.size
        }
        return OcrSnapshot(
            text = result.text,
            blockCount = result.textBlocks.size,
            lineCount = lines,
        )
    }

    fun close() {
        recognizer.close()
    }
}
