package com.example

import java.util.Locale

/** Small LRU cache so unchanged OCR blocks are never translated repeatedly. */
internal class TranslationCache(
    private val maxEntries: Int = 64,
) {
    private val translations = object : LinkedHashMap<String, String>(
        maxEntries,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>?,
        ): Boolean = size > maxEntries
    }

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun get(source: String): String? = translations[normalize(source)]

    @Synchronized
    fun put(source: String, translation: String) {
        translations[normalize(source)] = translation
    }

    @Synchronized
    fun clear() {
        translations.clear()
    }

    private fun normalize(text: String): String =
        text.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
