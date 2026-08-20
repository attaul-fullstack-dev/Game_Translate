package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCacheTest {
    @Test
    fun `same normalized OCR text reuses successful translation`() {
        val cache = TranslationCache()
        cache.put("Open the ancient door", "Buka pintu kuno")

        assertEquals("Buka pintu kuno", cache.get("  open  the ancient door "))
        assertNull(cache.get("Open the ancient do0r"))
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
    fun `semantic changes never reuse a cached translation`() {
        val cache = TranslationCache()
        cache.put("The door is locked", "Pintunya terkunci")

        assertNull(cache.get("The door is unlocked"))
        assertNull(cache.get("The door is lock3d"))
    }
}
