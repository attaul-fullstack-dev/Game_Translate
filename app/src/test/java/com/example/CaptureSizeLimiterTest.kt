package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSizeLimiterTest {
    @Test
    fun `keeps an already safe capture unchanged`() {
        assertEquals(2048 to 922, CaptureSizeLimiter.limit(2048, 922))
    }

    @Test
    fun `bounds a 4k capture while preserving aspect ratio`() {
        val (width, height) = CaptureSizeLimiter.limit(3840, 2160)

        assertTrue(width.toLong() * height.toLong() <= CaptureSizeLimiter.DEFAULT_MAX_PIXELS)
        assertEquals(3840.0 / 2160.0, width.toDouble() / height.toDouble(), 0.01)
    }
}
