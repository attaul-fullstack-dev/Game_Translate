package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCacheTest {
    @Test
    fun `similar OCR frame reuses successful translation`() {
        val cache = TranslationCache()
        cache.put("Open the ancient door", "Buka pintu kuno")

        assertEquals("Buka pintu kuno", cache.get("  open  the ancient door "))
        assertEquals("Buka pintu kuno", cache.get("Open the ancient do0r"))
    }

    @Test
    fun `meaningfully different text is not served from cache`() {
        val cache = TranslationCache()
        cache.put("Open the ancient door", "Buka pintu kuno")

        assertNull(cache.get("Defeat the final boss"))
    }

    @Test
    fun `cache can be reset between manual tests`() {
        val cache = TranslationCache()
        cache.put("Hello", "Halo")
        cache.clear()

        assertNull(cache.get("Hello"))
    }

    @Test
    fun `similarity handles whitespace case and empty input`() {
        assertTrue(TextSimilarity.isSimilar("NEW   GAME", "new game"))
        assertFalse(TextSimilarity.isSimilar("", ""))
        assertFalse(TextSimilarity.isSimilar("Save game", "Load game"))
    }
}
