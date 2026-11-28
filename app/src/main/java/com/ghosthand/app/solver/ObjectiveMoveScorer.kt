package com.ghosthand.app.solver

import com.ghosthand.app.vision.BoardObjectType
import com.ghosthand.app.vision.BoardReader
import com.ghosthand.app.vision.ObjectiveType
import com.ghosthand.app.vision.ShapeTopology

/** A counter explicitly read from the level objective UI. */
data class ObjectiveGoal(
    val type: ObjectiveType,
    val current: Int,
    val target: Int
) {
    init {
        require(target > 0) { "Objective target must be positive" }
        require(current in 0..target) { "Objective progress must be within 0..target" }
    }

    val remaining: Int get() = target - current
}

/**
 * Predicted objective effects for one hypothetical piece placement.
 *
 * Balloon contacts are reported but never translated into guessed balloon
 * counts. This evaluator is intentionally not connected to live move choice
 * until the board reader can provide validated object identities.
 */
data class ObjectiveMoveEvaluation(
    val clearedRows: List<Int>,
    val clearedColumns: List<Int>,
    val markedTilesCollected: Int,
    val applesCollected: Int,
    val appleFallDistance: Int,
    val balloonBlocksTouched: Int,
    val resultingBoard: Array<Array<BoardObjectType>>,
    val scoreBonus: Long
)

/**
 * Evaluates objective impact using explicitly typed cells.
 *
 * HEART cells are collected by a completed row/column. APPLE cells survive
 * line clearing and fall through newly empty cells, collecting only when they
 * reach the bottom. BALLOON cells are retained and reported as touched, but
 * their release count is not inferred. Other object types receive no guessed
 * objective bonus.
 *
 * The live solver may use this model only when Agent supplies a complete
 * board whose occupied cells all have supported, confident object labels.
 */
object ObjectiveMoveScorer {

    fun evaluate(
        board: Array<Array<BoardObjectType>>,
        piece: BoardReader.Piece,
        x: Int,
        y: Int,
        goals: List<ObjectiveGoal>
    ): ObjectiveMoveEvaluation {
        require(board.isNotEmpty() && board.all { it.isNotEmpty() }) {
            "Board must be a non-empty grid"
        }
        val rows = board.first().size
        require(board.all { it.size == rows }) { "Board columns must have equal heights" }
        val cols = board.size
        require(goals.map { it.type }.distinct().size == goals.size) {
            "Only one counter per objective type is allowed"
        }
        require(ShapeTopology.isValidShape(piece.cells, piece.w, piece.h)) {
            "Piece shape is malformed"
        }
        require(piece.w <= cols && piece.h <= rows && x >= 0 && y >= 0 &&
            x + piece.w <= cols && y + piece.h <= rows
        ) { "Piece placement is outside the board" }

        val next = Array(cols) { board[it].clone() }
        piece.cells.forEach { (cellX, cellY) ->
            val targetX = x + cellX
            val targetY = y + cellY
            require(next[targetX][targetY] == BoardObjectType.EMPTY) {
                "Piece overlaps an occupied cell at ($targetX,$targetY)"
            }
            next[targetX][targetY] = BoardObjectType.ORDINARY_BLOCK
        }

        val fullRows = (0 until rows).filter { row ->
            (0 until cols).all { column -> next[column][row] != BoardObjectType.EMPTY }
        }
        val fullColumns = (0 until cols).filter { column ->
            (0 until rows).all { row -> next[column][row] != BoardObjectType.EMPTY }
        }
        if (fullRows.isEmpty() && fullColumns.isEmpty()) {
            return ObjectiveMoveEvaluation(
                emptyList(), emptyList(), 0, 0, 0, 0, next, 0L
            )
        }

        val rowsToClear = fullRows.toSet()
        val columnsToClear = fullColumns.toSet()
        var markedCollected = 0
        var balloonBlocksTouched = 0

        for (column in 0 until cols) {
            for (row in 0 until rows) {
                if (row !in rowsToClear && column !in columnsToClear) continue
                when (next[column][row]) {
                    BoardObjectType.HEART -> {
                        markedCollected++
                        next[column][row] = BoardObjectType.EMPTY
                    }
                    BoardObjectType.BALLOON -> balloonBlocksTouched++
                    BoardObjectType.APPLE -> Unit
                    else -> next[column][row] = BoardObjectType.EMPTY
                }
            }
        }

        var applesCollected = 0
        var appleFallDistance = 0
        // Process bottom-most apples first so stacked apples do not collide
        // with a lower apple that has not yet moved.
        for (column in 0 until cols) {
            for (row in rows - 1 downTo 0) {
                if (next[column][row] != BoardObjectType.APPLE) continue
                next[column][row] = BoardObjectType.EMPTY
                var landingRow = row
                while (landingRow + 1 < rows &&
                    next[column][landingRow + 1] == BoardObjectType.EMPTY
                ) {
                    landingRow++
                }
                val distance = landingRow - row
                appleFallDistance += distance
                if (landingRow == rows - 1 && distance > 0) {
                    applesCollected++
                } else {
                    next[column][landingRow] = BoardObjectType.APPLE
                }
            }
        }

        val goalByType = goals.associateBy { it.type }
        val heartGoal = goalByType[ObjectiveType.HEART]
        val appleGoal = goalByType[ObjectiveType.APPLE]
        val effectiveHearts = minOf(markedCollected, heartGoal?.remaining ?: 0)
        val effectiveApples = minOf(applesCollected, appleGoal?.remaining ?: 0)
        val effectiveAppleFall = minOf(appleFallDistance, appleGoal?.remaining ?: 0)

        // These weights are isolated from BlockSolver's heuristic. They need
        // tuning against known screenshots/level outcomes before integration.
        val scoreBonus =
            effectiveHearts * HEART_PROGRESS_WEIGHT.toLong() +
                effectiveApples * APPLE_COLLECTION_WEIGHT.toLong() +
                effectiveAppleFall * APPLE_FALL_WEIGHT.toLong()

        return ObjectiveMoveEvaluation(
            clearedRows = fullRows,
            clearedColumns = fullColumns,
            markedTilesCollected = markedCollected,
            applesCollected = applesCollected,
            appleFallDistance = appleFallDistance,
            balloonBlocksTouched = balloonBlocksTouched,
            resultingBoard = next,
            scoreBonus = scoreBonus
        )
    }

    private const val HEART_PROGRESS_WEIGHT = 1_000
    private const val APPLE_COLLECTION_WEIGHT = 1_200
    private const val APPLE_FALL_WEIGHT = 100
}
