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
    fun darkerPiecesDoNotCauseTheLighterEmptySurfaceToLookOccupied() {
        val background = RgbColor(88, 96, 124)
        val darkBlock = RgbColor(38, 18, 31)
        val samples = Array(8) { x ->
            Array(8) { y ->
                CellColorSamples(
                    center = if (x < 3 && y < 4) darkBlock else background,
                    corners = List(8) { background }
                )
            }
        }

        val result = BoardOccupancyClassifier.classify(samples)

        assertFalse(result.cells[7][7].occupied)
        assertTrue(result.cells[0][0].occupied)
        assertEquals(0, result.cells[7][7].backgroundDistance)
    }

    @Test
    fun crowdedBoardStillReadsAsConfident() {
        // About two thirds full, like a late Woodoku Blast level: only ~37%
        // of perimeter samples are background, but every empty cell is clean.
        val background = RgbColor(24, 33, 57)
        // Mixed block colours, as on a real level, so no single block
        // colour outnumbers the empty surface.
        val blocks = listOf(
            RgbColor(230, 130, 40), RgbColor(220, 110, 160),
            RgbColor(90, 180, 80), RgbColor(170, 120, 90)
        )
        val samples = Array(8) { x ->
            Array(8) { y ->
                val occupied = (x * 8 + y) % 8 < 5 // 40 occupied, 24 empty
                val color = if (occupied) blocks[(x + y) % blocks.size] else background
                CellColorSamples(center = color, corners = List(8) { color })
            }
        }

        val result = BoardOccupancyClassifier.classify(samples)

        assertEquals(background, result.background)
        assertTrue(result.backgroundSupport > 0.9f)
    }

    @Test
    fun emptyCellsWithForeignEdgesLowerConfidence() {
        val background = RgbColor(24, 33, 57)
        val orange = RgbColor(230, 130, 40)
        val samples = Array(8) { x ->
            Array(8) { y ->
                val occupied = x < 3
                CellColorSamples(
                    center = if (occupied) orange else background,
                    // Empty-looking centres but most edges are block colour:
                    // the grid is misaligned with the real cells.
                    corners = List(8) { i -> if (occupied || i < 3) background else orange }
                )
            }
        }

        val result = BoardOccupancyClassifier.classify(samples)

        assertTrue(result.backgroundSupport < 0.5f)
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
