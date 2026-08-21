package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureEpochTest {
    @Test
    fun `new epoch invalidates in flight work`() {
        val epochs = CaptureEpoch()
        val first = epochs.next()

        assertTrue(epochs.matches(first))

        val second = epochs.next()

        assertFalse(epochs.matches(first))
        assertTrue(epochs.matches(second))
    }
}
