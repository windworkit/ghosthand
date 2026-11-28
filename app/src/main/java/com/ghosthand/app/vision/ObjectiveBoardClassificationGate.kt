package com.ghosthand.app.vision

/**
 * Converts pixel-level board observations into the limited typed-board
 * representation accepted by ObjectiveMoveScorer.
 *
 * This gate requires both a supported semantic label and independent
 * confidence. Occupancy confidence alone is not enough to decide that a
 * coloured cell is an ordinary block, heart, or apple.
 */
data class ObjectiveBoardClassification(
    val board: Array<Array<BoardObjectType>>?,
    val rejectionReason: String?
) {
    val isReady: Boolean get() = board != null
}

object ObjectiveBoardClassificationGate {

    private val supportedOccupiedTypes =
        com.ghosthand.app.solver.ObjectiveMoveScorer.LIVE_SUPPORTED_TYPES - BoardObjectType.EMPTY

    fun classify(
        observations: Array<Array<BoardCellObservation>>,
        minimumObjectConfidence: Float = DEFAULT_MINIMUM_OBJECT_CONFIDENCE
    ): ObjectiveBoardClassification {
        if (!minimumObjectConfidence.isFinite() ||
            minimumObjectConfidence !in 0f..1f
        ) {
            return ObjectiveBoardClassification(null, "invalid classification threshold")
        }
        if (observations.isEmpty() || observations.any { it.isEmpty() }) {
            return ObjectiveBoardClassification(null, "empty observation grid")
        }
        val rows = observations.first().size
        if (observations.any { it.size != rows }) {
            return ObjectiveBoardClassification(null, "non-rectangular observation grid")
        }

        for (column in observations.indices) {
            for (row in 0 until rows) {
                val cell = observations[column][row]
                if (!cell.occupied) {
                    if (cell.objectType != BoardObjectType.EMPTY) {
                        return ObjectiveBoardClassification(
                            null,
                            "empty cell has non-empty semantic label at ($column,$row)"
                        )
                    }
                    continue
                }
                if (cell.objectType !in supportedOccupiedTypes) {
                    return ObjectiveBoardClassification(
                        null,
                        "unsupported or unclassified occupied cell at ($column,$row)"
                    )
                }
                if (cell.objectConfidence < minimumObjectConfidence) {
                    return ObjectiveBoardClassification(
                        null,
                        "low object confidence at ($column,$row)"
                    )
                }
            }
        }

        val board = Array(observations.size) { x ->
            Array(rows) { y -> observations[x][y].objectType }
        }
        return ObjectiveBoardClassification(board, null)
    }

    const val DEFAULT_MINIMUM_OBJECT_CONFIDENCE = 0.92f
}
