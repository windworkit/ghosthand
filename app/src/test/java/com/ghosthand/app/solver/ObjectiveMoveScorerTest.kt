package com.ghosthand.app.solver

import com.ghosthand.app.vision.BoardObjectType
import com.ghosthand.app.vision.BoardReader
import com.ghosthand.app.vision.ObjectiveType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectiveMoveScorerTest {

    private fun emptyBoard(cols: Int = 4, rows: Int = 4) =
        Array(cols) { Array(rows) { BoardObjectType.EMPTY } }

    private val single = BoardReader.Piece("single", listOf(0 to 0), 1, 1)

    @Test
    fun scoresMarkedHeartTilesCollectedByCompletedRow() {
        val board = emptyBoard()
        board[1][1] = BoardObjectType.HEART
        board[2][1] = BoardObjectType.ORDINARY_BLOCK
        board[3][1] = BoardObjectType.ORDINARY_BLOCK

        val result = ObjectiveMoveScorer.evaluate(
            board, single, 0, 1,
            listOf(ObjectiveGoal(ObjectiveType.HEART, current = 2, target = 5))
        )

        assertEquals(listOf(1), result.clearedRows)
        assertEquals(1, result.markedTilesCollected)
        assertEquals(0, result.applesCollected)
        assertEquals(1_000L, result.scoreBonus)
    }

    @Test
    fun countsMarkedTileOnlyOnceWhenRowAndColumnClearTogether() {
        val board = emptyBoard()
        for (x in 0 until 4) if (x != 3) board[x][2] = BoardObjectType.ORDINARY_BLOCK
        for (y in 0 until 4) if (y != 2) board[3][y] = BoardObjectType.ORDINARY_BLOCK
        board[1][2] = BoardObjectType.HEART
        board[3][1] = BoardObjectType.HEART

        val result = ObjectiveMoveScorer.evaluate(
            board, single, 2, 2,
            listOf(ObjectiveGoal(ObjectiveType.HEART, current = 0, target = 1))
        )

        assertEquals(listOf(2), result.clearedRows)
        assertEquals(listOf(2), result.clearedColumns)
        assertEquals(2, result.markedTilesCollected)
        assertEquals(1_000L, result.scoreBonus)
    }

    @Test
    fun appleFallsWhenLineClearRemovesItsSupportAndIsCollectedAtBottom() {
        val board = emptyBoard()
        board[1][1] = BoardObjectType.APPLE
        for (column in 1 until 4) board[column][2] = BoardObjectType.ORDINARY_BLOCK

        val result = ObjectiveMoveScorer.evaluate(
            board, single, 0, 2,
            listOf(ObjectiveGoal(ObjectiveType.APPLE, current = 0, target = 3))
        )

        assertEquals(listOf(2), result.clearedRows)
        assertEquals(1, result.applesCollected)
        assertEquals(2, result.appleFallDistance)
        assertEquals(1_400L, result.scoreBonus)
    }

    @Test
    fun appleDoesNotFallThroughAnOccupiedCell() {
        val board = emptyBoard()
        board[1][0] = BoardObjectType.APPLE
        board[1][2] = BoardObjectType.ORDINARY_BLOCK
        for (column in 0 until 3) board[column][3] = BoardObjectType.ORDINARY_BLOCK

        val result = ObjectiveMoveScorer.evaluate(
            board, single, 3, 3,
            listOf(ObjectiveGoal(ObjectiveType.APPLE, current = 0, target = 1))
        )

        assertEquals(listOf(3), result.clearedRows)
        assertEquals(0, result.applesCollected)
        assertEquals(1, result.appleFallDistance)
    }

    @Test
    fun reportsBalloonContactWithoutInventingReleasedBalloonCount() {
        val board = emptyBoard()
        board[1][1] = BoardObjectType.BALLOON
        board[2][1] = BoardObjectType.ORDINARY_BLOCK
        board[3][1] = BoardObjectType.ORDINARY_BLOCK

        val result = ObjectiveMoveScorer.evaluate(board, single, 0, 1, emptyList())

        assertEquals(1, result.balloonBlocksTouched)
        assertEquals(0, result.markedTilesCollected)
        assertEquals(0L, result.scoreBonus)
    }

    @Test
    fun unknownObjectiveTypesDoNotReceiveGuessedProgressBonuses() {
        val board = emptyBoard()
        board[1][1] = BoardObjectType.FLOWER
        board[0][1] = BoardObjectType.ORDINARY_BLOCK
        board[2][1] = BoardObjectType.ORDINARY_BLOCK
        board[3][1] = BoardObjectType.ORDINARY_BLOCK

        val result = ObjectiveMoveScorer.evaluate(
            board, single, 0, 1,
            listOf(ObjectiveGoal(ObjectiveType.FLOWER, current = 0, target = 5))
        )

        assertEquals(0, result.markedTilesCollected)
        assertEquals(0L, result.scoreBonus)
    }

    @Test
    fun rejectsOverlapAndMalformedPlacement() {
        val board = emptyBoard()
        board[0][0] = BoardObjectType.ORDINARY_BLOCK
        var rejectedOverlap = false
        try {
            ObjectiveMoveScorer.evaluate(board, single, 0, 0, emptyList())
        } catch (_: IllegalArgumentException) {
            rejectedOverlap = true
        }
        assertTrue(rejectedOverlap)

        var rejectedBounds = false
        try {
            ObjectiveMoveScorer.evaluate(board, single, -1, 0, emptyList())
        } catch (_: IllegalArgumentException) {
            rejectedBounds = true
        }
        assertTrue(rejectedBounds)
    }
}
