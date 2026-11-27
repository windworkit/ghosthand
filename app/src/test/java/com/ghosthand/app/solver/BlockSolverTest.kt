package com.ghosthand.app.solver

import com.ghosthand.app.vision.BoardReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockSolverTest {

    @Test
    fun clearsCompletedRowsAndColumnsSimultaneously() {
        val cols = 8
        val rows = 8
        val grid = Array(cols) { x ->
            BooleanArray(rows) { y ->
                (y == 7 && x < 7) || (x == 7 && y < 7)
            }
        }
        val single = BoardReader.Piece("single", listOf(0 to 0), 1, 1)
        val solver = BlockSolver(cols, rows)

        assertTrue(solver.canFit(grid, single, 7, 7))
        val after = solver.place(grid, single, 7, 7)

        assertTrue(after.all { column -> column.none { it } })
    }

    @Test
    fun irregularPieceMayUseAnEmptyCellInsideItsBoundingBox() {
        val grid = Array(8) { BooleanArray(8) }
        // The top-right cell is intentionally absent from this L shape.
        grid[1][0] = true
        val lPiece = BoardReader.Piece(
            "L",
            listOf(0 to 0, 0 to 1, 1 to 1),
            2,
            2
        )
        val solver = BlockSolver(8, 8)

        assertTrue(solver.canFit(grid, lPiece, 0, 0))
        grid[0][1] = true
        assertFalse(solver.canFit(grid, lPiece, 0, 0))
    }

    @Test
    fun rejectsDisconnectedPieceInsteadOfPlanningWithIt() {
        val grid = Array(8) { BooleanArray(8) }
        val disconnected = BoardReader.Piece(
            "diagonal-only",
            listOf(0 to 0, 1 to 1),
            2,
            2
        )
        val solver = BlockSolver(8, 8)

        assertTrue(solver.solve(grid, listOf(disconnected)) == null)
        assertTrue(solver.lastDiagnostic.contains("malformed"))
    }

    @Test
    fun rejectsMalformedBoardDimensions() {
        val grid = Array(7) { BooleanArray(8) }
        val single = BoardReader.Piece("single", listOf(0 to 0), 1, 1)
        val solver = BlockSolver(8, 8)

        assertTrue(solver.solve(grid, listOf(single)) == null)
        assertTrue(solver.lastDiagnostic.contains("dimensions"))
    }

    @Test
    fun rejectsNegativeAndOutOfBoardPlacements() {
        val grid = Array(8) { BooleanArray(8) }
        val line = BoardReader.Piece(
            "line",
            listOf(0 to 0, 1 to 0, 2 to 0),
            3,
            1
        )
        val solver = BlockSolver(8, 8)

        assertFalse(solver.canFit(grid, line, -1, 0))
        assertFalse(solver.canFit(grid, line, 6, 0))
        assertFalse(solver.canFit(grid, line, 0, -1))
    }
}
