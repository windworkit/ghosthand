package com.ghosthand.app.solver

import com.ghosthand.app.vision.BoardReader
import org.junit.Assert.*
import org.junit.Test

class BlockSolverTest {
    private fun emptyBoard() = Array(8) { BooleanArray(8) }

    private fun piece(
        cells: List<Pair<Int, Int>>,
        width: Int,
        height: Int,
        anchorX: Int = 0,
        anchorY: Int = 0
    ) = BoardReader.Piece(
        name = "test",
        cells = cells,
        w = width,
        h = height,
        grabX = 10f,
        grabY = 10f,
        anchorCellX = anchorX,
        anchorCellY = anchorY
    )

    @Test
    fun legalPlacementFitsAllOccupiedOffsets() {
        val solver = BlockSolver(8, 8)
        val board = emptyBoard()
        val lShape = piece(listOf(0 to 0, 0 to 1, 1 to 1), 2, 2)

        assertTrue(solver.isLegalPlacement(board, lShape, 0, 0))
        assertTrue(solver.isLegalPlacement(board, lShape, 6, 6))
        assertFalse(solver.isLegalPlacement(board, lShape, 7, 7))
    }

    @Test
    fun rejectsOutOfBoundsAndOccupiedCellCollisions() {
        val solver = BlockSolver(8, 8)
        val board = emptyBoard()
        val domino = piece(listOf(0 to 0, 1 to 0), 2, 1)
        board[3][2] = true

        assertFalse(solver.isLegalPlacement(board, domino, 7, 0))
        assertFalse(solver.isLegalPlacement(board, domino, 2, 2))
        assertFalse(solver.isLegalPlacement(board, domino, -1, 0))
    }

    @Test
    fun rejectsMalformedGridAndPieceShapes() {
        val solver = BlockSolver(8, 8)
        val malformedGrid = Array(7) { BooleanArray(8) }
        val malformedPiece = piece(listOf(0 to 0, 0 to 0), 1, 1)

        assertFalse(solver.isLegalPlacement(malformedGrid, piece(listOf(0 to 0), 1, 1), 0, 0))
        assertFalse(solver.isLegalPlacement(emptyBoard(), malformedPiece, 0, 0))
    }

    @Test
    fun clearsSimultaneouslyCompletedRowAndColumn() {
        val solver = BlockSolver(8, 8)
        val board = emptyBoard()
        for (x in 1 until 8) board[x][7] = true
        for (y in 0 until 7) board[0][y] = true
        board[1][6] = true
        val single = piece(listOf(0 to 0), 1, 1)

        val result = solver.resultingGrid(board, single, 0, 7)!!

        for (x in 0 until 8) assertFalse(result[x][7])
        for (y in 0 until 8) assertFalse(result[0][y])
        assertTrue("unrelated occupied cell must remain", result[1][6])
    }



    @Test
    fun largeThreeByThreeSquareNeverFitsAcrossAnOccupiedTopLeftCell() {
        val solver = BlockSolver(8, 8)
        val square3 = piece(
            (0..2).flatMap { y -> (0..2).map { x -> x to y } },
            3,
            3
        )
        val board = emptyBoard()
        board[1][1] = true

        assertFalse("one occupied cell blocks the entire 3x3 drop", solver.isLegalPlacement(board, square3, 0, 0))
        val move = solver.solve(board, listOf(square3))!!.first()
        assertFalse("solver must not return the blocked top-left placement", move.x == 0 && move.y == 0)
    }

    @Test
    fun equalScorePlacementsKeepFirstCandidateInsteadOfRepeatingLastScannedCell() {
        val solver = BlockSolver(8, 8)
        val single = piece(listOf(0 to 0), 1, 1)

        val move = solver.solve(emptyBoard(), listOf(single))!!.first()

        // All columns are equivalent on an empty board. Stable strict-greater
        // selection must retain the first equal-scoring candidate (x=0),
        // rather than repeatedly overwriting it with the last column (x=7).
        assertEquals(0, move.x)
        assertEquals(7, move.y)
    }

    @Test
    fun supportsMultipleConsecutiveLegalMovesFromUpdatedBoard() {
        val solver = BlockSolver(8, 8)
        val first = piece(listOf(0 to 0, 1 to 0), 2, 1)
        val second = piece(listOf(0 to 0, 0 to 1), 1, 2)
        val firstBoard = solver.resultingGrid(emptyBoard(), first, 0, 0)!!

        assertTrue(solver.isLegalPlacement(firstBoard, second, 2, 0))
        val secondBoard = solver.resultingGrid(firstBoard, second, 2, 0)!!
        assertTrue(secondBoard[0][0])
        assertTrue(secondBoard[1][0])
        assertTrue(secondBoard[2][0])
        assertTrue(secondBoard[2][1])
    }
}
