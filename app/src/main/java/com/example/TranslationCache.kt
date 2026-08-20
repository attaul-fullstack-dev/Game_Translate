package com.example

import java.util.Locale

/** Keeps a successful translation only for the same normalized OCR text. */
internal class TranslationCache {
    private var sourceText = ""
    private var translatedText: String? = null

    @Synchronized
    fun get(source: String): String? {
        val cached = translatedText ?: return null
        return if (normalize(source) == sourceText) cached else null
    }

    @Synchronized
    fun put(source: String, translation: String) {
        sourceText = normalize(source)
        translatedText = translation
    }

    @Synchronized
    fun clear() {
        sourceText = ""
        translatedText = null
    }

    private fun normalize(text: String): String =
        text.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
