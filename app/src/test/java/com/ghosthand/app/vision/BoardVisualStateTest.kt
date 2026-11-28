package com.ghosthand.app.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardVisualStateTest {

    @Test
    fun visualChangeIsDetectedEvenWhenCellOccupancyDoesNotChange() {
        val before = BoardReader.GridSnapshot(
            occupied = Array(8) { x -> BooleanArray(8) { y -> x == 2 && y == 3 } },
            colorSignatures = Array(8) { x -> IntArray(8) { y -> if (x == 2 && y == 3) 0x245 else 0x124 } },
            backgroundSupport = 0.8f
        )
        val after = BoardReader.GridSnapshot(
            occupied = Array(8) { x -> BooleanArray(8) { y -> x == 2 && y == 3 } },
            colorSignatures = Array(8) { x -> IntArray(8) { y -> if (x == 2 && y == 3) 0x975 else 0x124 } },
            backgroundSupport = 0.8f
        )

        assertFalse(BoardReader().sameVisualState(before, after))
    }

    @Test
    fun trayChangeIsDetectedWhenBoardPixelsAndOccupancyStayTheSame() {
        val before = BoardReader.GridSnapshot(
            occupied = Array(8) { BooleanArray(8) },
            colorSignatures = Array(8) { IntArray(8) { 0x124 } },
            backgroundSupport = 0.8f,
            traySignature = "line-3@20,80|L-4@50,80|square-4@80,80"
        )
        val after = before.copy(
            traySignature = "L-4@50,80|square-4@80,80"
        )

        assertFalse(BoardReader().sameVisualState(before, after))
    }

    @Test
    fun unchangedOccupancyAndColorsAreConsideredSettled() {
        val first = BoardReader.GridSnapshot(
            occupied = Array(8) { BooleanArray(8) },
            colorSignatures = Array(8) { IntArray(8) { 0x124 } },
            backgroundSupport = 0.7f
        )
        val second = BoardReader.GridSnapshot(
            occupied = Array(8) { BooleanArray(8) },
            colorSignatures = Array(8) { IntArray(8) { 0x124 } },
            backgroundSupport = 0.9f
        )

        assertTrue(BoardReader().sameVisualState(first, second))
    }

    @Test
    fun gemSparkleJitterIsStillSettled() {
        // Sparkle/quantization noise shifts a channel by one or two buckets.
        val first = BoardReader.GridSnapshot(
            occupied = Array(8) { x -> BooleanArray(8) { y -> x == 2 && y == 3 } },
            colorSignatures = Array(8) { x -> IntArray(8) { y -> if (x == 2 && y == 3) 0x97D else 0x124 } },
            backgroundSupport = 0.8f
        )
        val second = first.copy(
            colorSignatures = Array(8) { x -> IntArray(8) { y -> if (x == 2 && y == 3) 0xA9E else 0x125 } }
        )

        assertTrue(BoardReader().sameVisualState(first, second))
    }
}
