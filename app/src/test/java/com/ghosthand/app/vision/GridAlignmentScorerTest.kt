package com.ghosthand.app.vision

import org.junit.Assert.assertTrue
import org.junit.Test

class GridAlignmentScorerTest {

    private fun syntheticBoardLuminance(
        x: Int,
        y: Int,
        origin: Int,
        divisions: Int,
        cell: Int
    ): Int {
        val localX = x - origin
        val localY = y - origin
        if (localX < 0 || localY < 0 ||
            localX >= divisions * cell || localY >= divisions * cell
        ) return 4

        val inCellX = localX % cell
        val inCellY = localY % cell
        if (inCellX < 3 || inCellX >= cell - 3 ||
            inCellY < 3 || inCellY >= cell - 3
        ) return 8

        val column = localX / cell
        val row = localY / cell
        return 45 + ((column + row) % 2) * 55
    }

    @Test
    fun nineByNineLatticeScoresAboveEightByEightOnNineCellImage() {
        val origin = 12
        val actualCell = 32
        val scoreNine = GridAlignmentScorer.score(9, 9, origin, origin, actualCell) { x, y ->
            syntheticBoardLuminance(x, y, origin, 9, actualCell)
        }
        val scoreEight = GridAlignmentScorer.score(8, 8, origin, origin, 36) { x, y ->
            syntheticBoardLuminance(x, y, origin, 9, actualCell)
        }

        assertTrue("9x9=$scoreNine should exceed 8x8=$scoreEight", scoreNine > scoreEight + 5f)
    }

    @Test
    fun eightByEightLatticeScoresAboveNineByNineOnEightCellImage() {
        val origin = 12
        val actualCell = 36
        val scoreEight = GridAlignmentScorer.score(8, 8, origin, origin, actualCell) { x, y ->
            syntheticBoardLuminance(x, y, origin, 8, actualCell)
        }
        val scoreNine = GridAlignmentScorer.score(9, 9, origin, origin, 32) { x, y ->
            syntheticBoardLuminance(x, y, origin, 8, actualCell)
        }

        assertTrue("8x8=$scoreEight should exceed 9x9=$scoreNine", scoreEight > scoreNine + 5f)
    }
}
