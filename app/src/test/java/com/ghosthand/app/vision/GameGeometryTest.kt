package com.ghosthand.app.vision

import org.junit.Assert.*
import org.junit.Test

class GameGeometryTest {
    @Test
    fun acceptsOnlyFullyContainedEightByEightLattice() {
        val valid = GameGeometry.Geo(20, 100, 40, 8, 8)
        assertTrue(GameGeometry.isValid(valid, frameWidth = 340, frameHeight = 420))
        assertFalse(GameGeometry.isValid(valid, frameWidth = 339, frameHeight = 420))
        assertFalse(GameGeometry.isValid(valid, frameWidth = 340, frameHeight = 419))
    }

    @Test
    fun rejectsNegativeOriginWrongCellCountAndInvalidPitch() {
        assertFalse(GameGeometry.isValid(GameGeometry.Geo(-1, 0, 40, 8, 8), 400, 400))
        assertFalse(GameGeometry.isValid(GameGeometry.Geo(0, 0, 40, 7, 8), 400, 400))
        assertFalse(GameGeometry.isValid(GameGeometry.Geo(0, 0, 40, 8, 9), 400, 400))
        assertFalse(GameGeometry.isValid(GameGeometry.Geo(0, 0, 5, 8, 8), 400, 400))
    }

    @Test
    fun computesCellCentersFromOneValidatedLattice() {
        val geo = GameGeometry.Geo(20, 100, 40, 8, 8)
        assertEquals(120f to 240f, GameGeometry.cellCenter(geo, 2, 3))
        assertNull(GameGeometry.cellCenter(geo, -1, 0))
        assertNull(GameGeometry.cellCenter(geo, 8, 0))
        assertNull(GameGeometry.cellCenter(geo, 0, 8))
    }
}
