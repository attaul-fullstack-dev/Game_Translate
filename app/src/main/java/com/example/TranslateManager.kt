package com.example

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.tasks.await

class TranslateManager {
    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.INDONESIAN)
        .build()

    private val translator: Translator = Translation.getClient(options)
    private var lastText = ""

    suspend fun downloadModelsIfNeeded() {
        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()
        translator.downloadModelIfNeeded(conditions).await()
    }
    
    suspend fun isModelDownloaded(): Boolean {
        val modelManager = RemoteModelManager.getInstance()
        val enModel = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
        val idModel = TranslateRemoteModel.Builder(TranslateLanguage.INDONESIAN).build()
        return modelManager.isModelDownloaded(enModel).await() && modelManager.isModelDownloaded(idModel).await()
    }

    suspend fun translate(text: String): String? {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return null

        // OCR rarely produces byte-identical text across frames (anti-aliasing,
        // micro-animations), so an exact match check never fires. Treat the text as
        // unchanged when it is >80% similar to the previous frame.
        if (isSimilar(trimmedText, lastText)) return null

        lastText = trimmedText
        return try {
            translator.translate(trimmedText).await()
        } catch (e: Exception) {
            DebugStore.logError(e)
            throw e
        }
    }

    private fun isSimilar(a: String, b: String): Boolean {
        if (b.isEmpty()) return false
        val longer = if (a.length > b.length) a else b
        val shorter = if (a.length > b.length) b else a
        val longerLength = longer.length
        if (longerLength == 0) return true
        val distance = levenshtein(longer, shorter)
        return (longerLength - distance).toFloat() / longerLength > 0.8f
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    fun resetLastText() {
        lastText = ""
    }

    fun close() {
        translator.close()
    }
}
