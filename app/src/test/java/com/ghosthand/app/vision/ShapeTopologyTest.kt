package com.ghosthand.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeTopologyTest {

    @Test
    fun diagonalCornerContactDoesNotJoinTwoTiles() {
        val cells = setOf(0 to 0, 1 to 1)

        assertEquals(2, ShapeTopology.components(cells).size)
        assertFalse(ShapeTopology.isConnected(cells))
    }

    @Test
    fun edgeConnectedZigZagIsOnePiece() {
        val cells = setOf(
            0 to 0, 1 to 0,
            1 to 1, 2 to 1
        )

        assertEquals(1, ShapeTopology.components(cells).size)
        assertTrue(ShapeTopology.isConnected(cells))
    }

    @Test
    fun rejectsDuplicateAndOutOfBoundsCells() {
        assertFalse(
            ShapeTopology.isValidShape(listOf(0 to 0, 0 to 0), 1, 1)
        )
        assertFalse(
            ShapeTopology.isValidShape(listOf(0 to 0, 2 to 0), 2, 1)
        )
    }
}
