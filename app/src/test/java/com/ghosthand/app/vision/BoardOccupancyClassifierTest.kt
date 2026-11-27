package com.ghosthand.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardOccupancyClassifierTest {

    @Test
    fun emptyCellPerimetersRemainBackgroundWhenMostCentersAreOccupied() {
        val background = RgbColor(24, 33, 57)
        val darkRedBlock = RgbColor(72, 25, 39)

        val samples = Array(8) { x ->
            Array(8) { y ->
                val occupied = (x < 6 && y < 7) // 42 occupied, 22 empty
                CellColorSamples(
                    center = if (occupied) darkRedBlock else background,
                    corners = List(8) { background }
                )
            }
        }

        val result = BoardOccupancyClassifier.classify(samples)

        assertTrue(result.backgroundSupport > 0.99f)
        assertEquals(background, result.background)
        assertTrue(result.cells[0][0].occupied)
        assertTrue(result.cells[5][6].occupied)
        assertFalse(result.cells[6][0].occupied)
        assertFalse(result.cells[7][7].occupied)
    }

    @Test
    fun mixedCornerNoiseDoesNotOutvoteTheRepeatedEmptySurface() {
        val background = RgbColor(20, 29, 52)
        val cornerNoise = RgbColor(170, 60, 80)
        val block = RgbColor(190, 60, 90)

        val samples = Array(8) { x ->
            Array(8) { y ->
                val corners = List(8) { index ->
                    if ((x + y + index) % 5 == 0) cornerNoise else background
                }
                CellColorSamples(
                    center = if (x < 4) block else background,
                    corners = corners
                )
            }
        }

        val result = BoardOccupancyClassifier.classify(samples)

        assertEquals(background, result.background)
        assertTrue(result.cells[0][0].occupied)
        assertFalse(result.cells[7][7].occupied)
    }

    @Test
    fun rejectsInvalidSampleGrids() {
        val color = RgbColor(10, 20, 30)
        val emptyCorners = CellColorSamples(color, emptyList())

        val error = runCatching {
            BoardOccupancyClassifier.classify(arrayOf(arrayOf(emptyCorners)))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
