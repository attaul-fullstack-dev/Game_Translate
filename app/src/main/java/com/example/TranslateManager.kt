package com.example

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.tasks.await

internal class TranslateManager {
    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.INDONESIAN)
        .build()

    private val translator: Translator = Translation.getClient(options)
    private val translationCache = TranslationCache()

    private data class ManagedModel(
        val model: TranslationModel,
        val remoteModel: TranslateRemoteModel,
    )

    private fun managedModels(): List<ManagedModel> = listOf(
        ManagedModel(
            model = TranslationModel.ENGLISH,
            remoteModel = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build(),
        ),
        ManagedModel(
            model = TranslationModel.INDONESIAN,
            remoteModel = TranslateRemoteModel.Builder(TranslateLanguage.INDONESIAN).build(),
        ),
    )

    suspend fun prepareModels(
        onProgress: (ModelDownloadProgress) -> Unit = {},
    ) {
        val modelManager = RemoteModelManager.getInstance()
        val conditions = DownloadConditions.Builder().build()
        val models = managedModels()
        val readyModels = mutableSetOf<TranslationModel>()

        fun report(
            phase: ModelDownloadPhase,
            currentModel: TranslationModel? = null,
        ) {
            onProgress(
                ModelDownloadProgress(
                    phase = phase,
                    currentModel = currentModel,
                    readyModels = readyModels.toSet(),
                ),
            )
        }

        report(ModelDownloadPhase.CHECKING)
        models.forEach { managedModel ->
            report(ModelDownloadPhase.CHECKING, managedModel.model)
            if (modelManager.isModelDownloaded(managedModel.remoteModel).await()) {
                readyModels += managedModel.model
            }
        }

        models.filterNot { it.model in readyModels }.forEach { managedModel ->
            report(ModelDownloadPhase.DOWNLOADING, managedModel.model)
            modelManager.download(managedModel.remoteModel, conditions).await()

            report(ModelDownloadPhase.VERIFYING, managedModel.model)
            check(modelManager.isModelDownloaded(managedModel.remoteModel).await()) {
                "Model ${managedModel.model.displayName} belum tersimpan setelah download selesai."
            }
            readyModels += managedModel.model
        }

        report(ModelDownloadPhase.VERIFYING)
        val verifiedModels = models.filter { managedModel ->
            modelManager.isModelDownloaded(managedModel.remoteModel).await()
        }.mapTo(mutableSetOf()) { it.model }

        check(verifiedModels.size == models.size) {
            "Verifikasi model terjemahan belum lengkap."
        }

        readyModels.clear()
        readyModels += verifiedModels
        report(ModelDownloadPhase.READY)
    }

    suspend fun isModelDownloaded(): Boolean {
        val modelManager = RemoteModelManager.getInstance()
        for (managedModel in managedModels()) {
            if (!modelManager.isModelDownloaded(managedModel.remoteModel).await()) {
                return false
            }
        }
        return true
    }

    suspend fun translate(text: String): String? {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return null

        // Only exact normalized OCR text is cached. Similar-looking phrases can
        // have opposite meanings (for example "locked" and "unlocked").
        translationCache.get(trimmedText)?.let { return it }

        return translator.translate(trimmedText).await().also { translated ->
            translationCache.put(trimmedText, translated)
        }
    }

    fun resetLastText() {
        translationCache.clear()
    }

    fun close() {
        translator.close()
    }
}
