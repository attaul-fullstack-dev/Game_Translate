package com.example

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrManager {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val result = recognizer.process(image).await()
            DebugStore.detectedBlocks.value = result.textBlocks.size
            var lines = 0
            for (block in result.textBlocks) {
                lines += block.lines.size
            }
            DebugStore.detectedLines.value = lines
            
            result.text
        } catch (e: Exception) {
            DebugStore.logError(e)
            throw e
        }
    }

    fun close() {
        recognizer.close()
    }
}
