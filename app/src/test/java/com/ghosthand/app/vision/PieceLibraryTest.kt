package com.ghosthand.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceLibraryTest {

    private val library = BoardReader().library

    private fun key(cells: List<Pair<Int, Int>>) = cells.toSet()

    @Test
    fun everyShapeIsValidAndUnique() {
        library.forEach { piece ->
            assertTrue("${piece.name} malformed", ShapeTopology.isValidShape(piece.cells, piece.w, piece.h))
        }
        assertEquals(library.size, library.map { key(it.cells) }.toSet().size)
        assertEquals(library.size, library.map { it.name }.toSet().size)
    }

    @Test
    fun includesVerticalLinesAndAllTRotations() {
        val shapes = library.map { key(it.cells) }.toSet()
        for (length in 2..5) {
            assertTrue("vertical $length-line missing", (0 until length).map { 0 to it }.toSet() in shapes)
            assertTrue("horizontal $length-line missing", (0 until length).map { it to 0 }.toSet() in shapes)
        }
        // Tall T in all four orientations (the old library had a ragged copy).
        assertTrue(setOf(0 to 0, 1 to 0, 2 to 0, 1 to 1, 1 to 2) in shapes)
        assertTrue(setOf(1 to 0, 1 to 1, 0 to 2, 1 to 2, 2 to 2) in shapes)
        assertTrue(setOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 2 to 1) in shapes)
        assertTrue(setOf(2 to 0, 2 to 1, 2 to 2, 1 to 1, 0 to 1) in shapes)
    }
}
