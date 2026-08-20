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

    @Test
    fun `multiple unchanged OCR blocks remain cached independently`() {
        val cache = TranslationCache()
        cache.put("PLAY", "Mainkan")
        cache.put("HELP & OPTIONS", "Bantuan & Opsi")

        assertEquals("Mainkan", cache.get("play"))
        assertEquals("Bantuan & Opsi", cache.get("help & options"))
    }

    @Test
    fun `least recently used translation is evicted at capacity`() {
        val cache = TranslationCache(maxEntries = 2)
        cache.put("PLAY", "Mainkan")
        cache.put("EPISODES", "Episode")
        cache.get("PLAY")
        cache.put("OPTIONS", "Opsi")

        assertEquals("Mainkan", cache.get("PLAY"))
        assertNull(cache.get("EPISODES"))
        assertEquals("Opsi", cache.get("OPTIONS"))
    }
}
