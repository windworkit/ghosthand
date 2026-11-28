package com.ghosthand.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectiveBoardClassificationGateTest {

    private fun cell(
        x: Int,
        y: Int,
        occupied: Boolean,
        type: BoardObjectType,
        confidence: Float = if (occupied) 0.98f else 1f
    ) = BoardCellObservation(
        column = x,
        row = y,
        occupied = occupied,
        objectType = type,
        luminance = 80,
        backgroundDistance = if (occupied) 90 else 0,
        centerColorSignature = 0x123,
        objectConfidence = confidence
    )

    @Test
    fun acceptsOnlySupportedLabelsAboveConfidenceThreshold() {
        val observations = arrayOf(
            arrayOf(
                cell(0, 0, true, BoardObjectType.ORDINARY_BLOCK),
                cell(0, 1, false, BoardObjectType.EMPTY)
            ),
            arrayOf(
                cell(1, 0, true, BoardObjectType.HEART),
                cell(1, 1, true, BoardObjectType.APPLE)
            )
        )

        val result = ObjectiveBoardClassificationGate.classify(observations)

        assertTrue(result.isReady)
        assertNull(result.rejectionReason)
        val typedBoard = result.board ?: throw AssertionError("Expected a classified board")
        assertEquals(BoardObjectType.ORDINARY_BLOCK, typedBoard[0][0])
        assertEquals(BoardObjectType.EMPTY, typedBoard[0][1])
        assertEquals(BoardObjectType.HEART, typedBoard[1][0])
        assertEquals(BoardObjectType.APPLE, typedBoard[1][1])
    }

    @Test
    fun rejectsSupportedLabelBelowConfidenceThreshold() {
        val observations = arrayOf(
            arrayOf(cell(0, 0, true, BoardObjectType.HEART, confidence = 0.91f))
        )

        val result = ObjectiveBoardClassificationGate.classify(observations)

        assertFalse(result.isReady)
        assertNull(result.board)
        assertTrue(result.rejectionReason!!.contains("low object confidence"))
    }

    @Test
    fun rejectsUnclassifiedOccupiedCellEvenWithHighConfidence() {
        val observations = arrayOf(
            arrayOf(
                cell(
                    0, 0, true,
                    BoardObjectType.UNCLASSIFIED_OCCUPIED,
                    confidence = 1f
                )
            )
        )

        val result = ObjectiveBoardClassificationGate.classify(observations)

        assertFalse(result.isReady)
        assertTrue(result.rejectionReason!!.contains("unclassified"))
    }

    @Test
    fun rejectsUnsupportedSpecialTileType() {
        val observations = arrayOf(
            arrayOf(cell(0, 0, true, BoardObjectType.BALLOON))
        )

        val result = ObjectiveBoardClassificationGate.classify(observations)

        assertFalse(result.isReady)
        assertTrue(result.rejectionReason!!.contains("unsupported"))
    }

    @Test
    fun rejectsEmptyCellWithNonEmptySemanticLabel() {
        val observations = arrayOf(
            arrayOf(cell(0, 0, false, BoardObjectType.HEART))
        )

        val result = ObjectiveBoardClassificationGate.classify(observations)

        assertFalse(result.isReady)
        assertTrue(result.rejectionReason!!.contains("empty cell"))
    }

    @Test
    fun rejectsNonRectangularBoard() {
        val observations = arrayOf(
            arrayOf(cell(0, 0, false, BoardObjectType.EMPTY)),
            arrayOf(
                cell(1, 0, false, BoardObjectType.EMPTY),
                cell(1, 1, false, BoardObjectType.EMPTY)
            )
        )

        val result = ObjectiveBoardClassificationGate.classify(observations)

        assertFalse(result.isReady)
        assertTrue(result.rejectionReason!!.contains("non-rectangular"))
    }

    @Test
    fun rejectsInvalidConfidenceThreshold() {
        val observations = arrayOf(
            arrayOf(cell(0, 0, false, BoardObjectType.EMPTY))
        )

        val result = ObjectiveBoardClassificationGate.classify(
            observations,
            minimumObjectConfidence = Float.NaN
        )

        assertFalse(result.isReady)
        assertTrue(result.rejectionReason!!.contains("threshold"))
    }
}
