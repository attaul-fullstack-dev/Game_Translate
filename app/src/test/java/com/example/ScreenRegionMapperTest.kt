package com.example

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ScreenRegionMapperTest {
    @Test
    fun `same orientation scales without rotating`() {
        assertArrayEquals(
            intArrayOf(50, 100, 150, 200),
            ScreenRegionMapper.map(
                rect = intArrayOf(100, 200, 300, 400),
                sourceWidth = 1000,
                sourceHeight = 2000,
                targetWidth = 500,
                targetHeight = 1000,
            ),
        )
    }

    @Test
    fun `portrait selection rotates clockwise into landscape capture`() {
        assertArrayEquals(
            intArrayOf(440, 189, 600, 702),
            ScreenRegionMapper.map(
                rect = intArrayOf(189, 1008, 702, 600),
                sourceWidth = 922,
                sourceHeight = 2048,
                targetWidth = 2048,
                targetHeight = 922,
                quarterTurn = QuarterTurn.CLOCKWISE,
            ),
        )
    }

    @Test
    fun `portrait selection rotates counter clockwise into landscape capture`() {
        assertArrayEquals(
            intArrayOf(1008, 31, 600, 702),
            ScreenRegionMapper.map(
                rect = intArrayOf(189, 1008, 702, 600),
                sourceWidth = 922,
                sourceHeight = 2048,
                targetWidth = 2048,
                targetHeight = 922,
                quarterTurn = QuarterTurn.COUNTER_CLOCKWISE,
            ),
        )
    }
}
