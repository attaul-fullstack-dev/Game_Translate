package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrStabilityTrackerTest {
    private fun region(
        text: String,
        left: Int = 10,
        top: Int = 20,
    ) = OcrTextRegion(
        text = text,
        bounds = OcrBounds(
            left = left,
            top = top,
            right = left + 100,
            bottom = top + 30,
        ),
    )

    @Test
    fun `new text must be seen twice before replacing overlay`() {
        val tracker = OcrStabilityTracker()
        val play = listOf(region("PLAY"))

        assertEquals(
            OcrUpdateDecision.WAITING_FOR_STABLE_TEXT,
            tracker.observe(play),
        )
        assertEquals(OcrUpdateDecision.CHANGED, tracker.observe(play))
        assertEquals(OcrUpdateDecision.UNCHANGED, tracker.observe(play))
    }

    @Test
    fun `one noisy OCR frame cannot replace accepted text`() {
        val tracker = OcrStabilityTracker()
        val play = listOf(region("PLAY"))
        val noisy = listOf(region("P1AY"))

        tracker.observe(play)
        tracker.observe(play)

        assertEquals(
            OcrUpdateDecision.WAITING_FOR_STABLE_TEXT,
            tracker.observe(noisy),
        )
        assertEquals(OcrUpdateDecision.UNCHANGED, tracker.observe(play))
    }

    @Test
    fun `same text with jittered bounds does not republish content`() {
        val tracker = OcrStabilityTracker()
        tracker.observe(listOf(region("EPISODES", left = 100, top = 100)))
        tracker.observe(listOf(region("EPISODES", left = 100, top = 100)))

        assertEquals(
            OcrUpdateDecision.UNCHANGED,
            tracker.observe(listOf(region("EPISODES", left = 104, top = 97))),
        )
    }

    @Test
    fun `overlay only clears after two blank frames`() {
        val tracker = OcrStabilityTracker()
        val help = listOf(region("HELP & OPTIONS"))
        tracker.observe(help)
        tracker.observe(help)

        assertEquals(
            OcrUpdateDecision.WAITING_FOR_STABLE_TEXT,
            tracker.observe(emptyList()),
        )
        assertEquals(
            OcrUpdateDecision.CLEARED,
            tracker.observe(emptyList()),
        )
    }
}
