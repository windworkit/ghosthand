package com.ghosthand.app.vision

import org.junit.Assert.*
import org.junit.Test

class TrayMaskAnalyzerTest {
    private val pitch = 20f

    private fun maskFor(cells: List<Pair<Int, Int>>): Array<BooleanArray> {
        val mask = Array(130) { BooleanArray(150) }
        for ((cellX, cellY) in cells) {
            val centerX = 30 + cellX * pitch.toInt()
            val centerY = 30 + cellY * pitch.toInt()
            for (y in centerY - 5 until centerY + 5) {
                for (x in centerX - 5 until centerX + 5) {
                    mask[y][x] = true
                }
            }
        }
        return mask
    }

    @Test
    fun rgbClassifierRecognizesBlueRedPinkOrangeAndSpecialTiles() {
        val woodokuBlueBackground = 0xFF4347C3.toInt()
        val representativeTiles = listOf(
            0xFF2896F0.toInt(), // blue
            0xFF70141E.toInt(), // dark red
            0xFFEA328F.toInt(), // pink
            0xFFFF6E19.toInt(), // orange
            0xFFFFFF55.toInt()  // bright special tile
        )
        representativeTiles.forEach { pixel ->
            assertTrue("expected tile color 0x${pixel.toUInt().toString(16)}",
                TrayMaskAnalyzer.isTilePixel(pixel, woodokuBlueBackground))
        }
        assertFalse(TrayMaskAnalyzer.isTilePixel(
            woodokuBlueBackground, woodokuBlueBackground
        ))
        assertFalse(TrayMaskAnalyzer.isTilePixel(
            0xFF777777.toInt(), woodokuBlueBackground
        ))
    }

    @Test
    fun detectsSingleOccupiedSlotAndSkipsEmptySlots() {
        val single = maskFor(listOf(0 to 0))
        val result = TrayMaskAnalyzer.analyzeSlots(
            listOf(null, single, null), pitch
        )
        assertEquals(1, result.size)
        assertEquals(1, result.single().cells.size)
        assertEquals(1, result.single().width)
        assertEquals(1, result.single().height)
    }

    @Test
    fun detectsTwoOccupiedSlotsWithOneEmpty() {
        val one = maskFor(listOf(0 to 0, 1 to 0))
        val two = maskFor(listOf(0 to 0, 0 to 1, 1 to 1))
        val result = TrayMaskAnalyzer.analyzeSlots(
            listOf(one, null, two), pitch
        )
        assertEquals(2, result.size)
        assertEquals(setOf(2, 3), result.map { it.cells.size }.toSet())
    }

    @Test
    fun detectsAllThreeOccupiedSlots() {
        val masks = listOf(
            maskFor(listOf(0 to 0)),
            maskFor(listOf(0 to 0, 1 to 0)),
            maskFor(listOf(0 to 0, 0 to 1, 1 to 1))
        )
        val result = TrayMaskAnalyzer.analyzeSlots(masks, pitch)
        assertEquals(3, result.size)
        assertEquals(listOf(1, 2, 3), result.map { it.cells.size }.sorted())
    }

    @Test
    fun returnsNoPiecesForAllEmptyOrBlankTray() {
        assertTrue(TrayMaskAnalyzer.analyzeSlots(listOf(null, null, null), pitch).isEmpty())
        val blank = Array(130) { BooleanArray(150) }
        assertNull(TrayMaskAnalyzer.analyze(blank, pitch))
    }

    @Test
    fun reconstructsThreeByThreeAndLargerSupportedShapes() {
        val threeByThree = (0..2).flatMap { y -> (0..2).map { x -> x to y } }
        val largeIrregular = listOf(
            0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0,
            4 to 1, 4 to 2, 3 to 2, 2 to 2, 1 to 2
        )
        val first = TrayMaskAnalyzer.analyze(maskFor(threeByThree), pitch)!!
        val second = TrayMaskAnalyzer.analyze(maskFor(largeIrregular), pitch)!!

        assertEquals(3, first.width)
        assertEquals(3, first.height)
        assertEquals(9, first.cells.size)
        assertEquals(5, second.width)
        assertEquals(3, second.height)
        assertEquals(10, second.cells.size)
        assertTrue(second.anchorCellX in 0 until second.width)
        assertTrue(second.anchorCellY in 0 until second.height)
        assertTrue(second.bounds.isValid)
    }

    @Test
    fun keepsDiagonallyTouchingCellsInOnePieceAndSeparatesNoise() {
        val mask = maskFor(listOf(0 to 0, 1 to 1))
        // Small separate noise should not join the detected tile lattice.
        for (y in 115 until 118) for (x in 135 until 138) mask[y][x] = true
        val result = TrayMaskAnalyzer.analyze(mask, pitch)!!

        assertEquals(2, result.cells.size)
        assertTrue(result.cells.contains(0 to 0))
        assertTrue(result.cells.contains(1 to 1))
    }

    @Test
    fun rejectsMalformedMasksAndUnsupportedDimensions() {
        assertNull(TrayMaskAnalyzer.analyze(arrayOf(BooleanArray(2), BooleanArray(3)), pitch))
        assertNull(TrayMaskAnalyzer.analyze(maskFor(listOf(0 to 0)), 2f))
        val sixWide = (0..5).map { it to 0 }
        assertNull(TrayMaskAnalyzer.analyze(maskFor(sixWide), pitch))
    }
}
