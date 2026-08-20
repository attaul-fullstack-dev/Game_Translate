package com.example

import java.util.Locale

/**
 * Keeps a successful translation available while OCR output only changes by noise
 * between adjacent frames.
 */
internal class TranslationCache(
    private val similarityThreshold: Float = 0.8f,
) {
    private var sourceText = ""
    private var translatedText: String? = null

    fun get(source: String): String? {
        val cached = translatedText ?: return null
        return if (TextSimilarity.isSimilar(source, sourceText, similarityThreshold)) cached else null
    }

    fun put(source: String, translation: String) {
        sourceText = source
        translatedText = translation
    }

    fun clear() {
        sourceText = ""
        translatedText = null
    }
}

internal object TextSimilarity {
    fun isSimilar(a: String, b: String, threshold: Float = 0.8f): Boolean {
        require(threshold in 0f..1f) { "threshold must be between 0 and 1" }

        val normalizedA = normalize(a)
        val normalizedB = normalize(b)
        if (normalizedA.isEmpty() || normalizedB.isEmpty()) return false
        if (normalizedA == normalizedB) return true

        val longer = if (normalizedA.length >= normalizedB.length) normalizedA else normalizedB
        val shorter = if (normalizedA.length >= normalizedB.length) normalizedB else normalizedA
        val distance = levenshtein(longer, shorter)
        val similarity = (longer.length - distance).toFloat() / longer.length
        return similarity >= threshold
    }

    private fun normalize(text: String): String =
        text.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

    /** Uses two rows instead of allocating a matrix proportional to both inputs. */
    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[b.length]
    }
}
