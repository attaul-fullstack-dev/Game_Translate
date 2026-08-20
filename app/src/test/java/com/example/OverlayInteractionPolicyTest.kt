package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayInteractionPolicyTest {
    @Test
    fun `paused bubble reopens area selection`() {
        assertEquals(
            BubbleTapAction.START_SELECTION,
            OverlayInteractionPolicy.actionFor(OverlayState.PAUSED),
        )
    }

    @Test
    fun `active bubble pauses capture`() {
        assertEquals(
            BubbleTapAction.PAUSE,
            OverlayInteractionPolicy.actionFor(OverlayState.ACTIVE),
        )
    }
}
