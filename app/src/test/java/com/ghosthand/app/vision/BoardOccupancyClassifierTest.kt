package com.ghosthand.app.vision

import com.ghosthand.app.solver.BlockSolver
import org.junit.Assert.*
import org.junit.Test

class BoardOccupancyClassifierTest {

    private fun board(): Array<Array<BoardOccupancyClassifier.Rgb>> =
        Array(8) { x ->
            Array(8) { y ->
                // Small natural cell-to-cell color variation around the shared
                // dark-blue empty-cell background.
                BoardOccupancyClassifier.Rgb(
                    50 + (x % 3) * 2,
                    56 + (y % 3) * 2,
                    118 + ((x + y) % 3) * 2
                )
            }
        }

    @Test
    fun findsOccupiedBlocksOfDifferentColorsIncludingDarkRed() {
        val cells = board()
        cells[2][3] = BoardOccupancyClassifier.Rgb(112, 20, 30) // dark red
        cells[4][1] = BoardOccupancyClassifier.Rgb(40, 150, 240) // blue
        cells[5][1] = BoardOccupancyClassifier.Rgb(234, 50, 143) // pink
        cells[6][1] = BoardOccupancyClassifier.Rgb(255, 110, 25) // orange
        cells[6][2] = BoardOccupancyClassifier.Rgb(255, 255, 85) // special

        // The dark-red block is darker than empty cells, which is precisely
        // why a darkest-cell-derived luminance threshold could erase it.
        val emptyLuma = (50 + 56 + 118) / 3
        val darkRedLuma = (112 + 20 + 30) / 3
        assertTrue(darkRedLuma < emptyLuma)

        val result = BoardOccupancyClassifier.classify(cells)
        val grid = result.grid ?: throw AssertionError(result.diagnostic)

        assertTrue(grid[2][3])
        assertTrue(grid[4][1])
        assertTrue(grid[5][1])
        assertTrue(grid[6][1])
        assertTrue(grid[6][2])
        assertFalse(grid[0][0])
        assertFalse(grid[7][7])
        assertTrue(result.diagnostic.contains("occupied=5"))
    }

    @Test
    fun solverCannotPlaceOntoAColoredCellRecognizedByVision() {
        val cells = board()
        cells[3][4] = BoardOccupancyClassifier.Rgb(112, 20, 30)
        val classification = BoardOccupancyClassifier.classify(cells)
        val grid = classification.grid ?: throw AssertionError(classification.diagnostic)
        val single = BoardReader.Piece(
            name = "single",
            cells = listOf(0 to 0),
            w = 1,
            h = 1,
            grabX = 10f,
            grabY = 10f
        )

        assertFalse(BlockSolver(8, 8).isLegalPlacement(grid, single, 3, 4))
        assertTrue(BlockSolver(8, 8).isLegalPlacement(grid, single, 3, 3))
    }

    @Test
    fun rejectsCellsWhoseColorIsBetweenEmptyAndOccupiedConfidenceBands() {
        val cells = board()
        cells[3][4] = BoardOccupancyClassifier.Rgb(90, 57, 120)

        val result = BoardOccupancyClassifier.classify(cells)

        assertNull("uncertain occupancy must never be treated as empty", result.grid)
        assertTrue(result.diagnostic.contains("ambiguous cells"))
        assertTrue(result.diagnostic.contains("3,4"))
    }

    @Test
    fun rejectsBoardWithoutEnoughConsistentEmptyCellBackgroundSamples() {
        val cells = Array(8) { x ->
            Array(8) { y ->
                BoardOccupancyClassifier.Rgb(
                    x * 31,
                    y * 31,
                    ((x + y) % 8) * 31
                )
            }
        }

        val result = BoardOccupancyClassifier.classify(cells)

        assertNull(result.grid)
        assertTrue(result.diagnostic.contains("no reliable empty-cell color cluster"))
    }

    @Test
    fun classifiesAnEntirelyEmptyBoardAsEmpty() {
        val result = BoardOccupancyClassifier.classify(board())
        val grid = result.grid ?: throw AssertionError(result.diagnostic)

        assertTrue(grid.all { column -> column.none { it } })
        assertTrue(result.diagnostic.contains("empty=64 occupied=0"))
    }
}
