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
        if (trimmedText == lastText) return null // return null to indicate no change

        lastText = trimmedText
        return try {
            translator.translate(trimmedText).await()
        } catch (e: Exception) {
            null
        }
    }

    fun resetLastText() {
        lastText = ""
    }

    fun close() {
        translator.close()
    }
}
