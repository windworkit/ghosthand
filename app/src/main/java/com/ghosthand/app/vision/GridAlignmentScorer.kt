package com.ghosthand.app.vision

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Measures whether a proposed square lattice aligns with visible cell seams.
 * The score is average seam-center contrast relative to the two neighboring
 * cell interiors; it does not assume an 8x8 or 9x9 board.
 */
object GridAlignmentScorer {

    fun score(
        columns: Int,
        rows: Int,
        originX: Int,
        originY: Int,
        cellSize: Int,
        luminanceAt: (Int, Int) -> Int
    ): Float {
        require(columns in 8..9 && rows in 8..9)
        require(cellSize >= 6)

        val shoulder = maxOf(2, (cellSize * 0.12f).roundToInt())
        val sampleOffsets = floatArrayOf(0.25f, 0.50f, 0.75f)
        var contrastTotal = 0f
        var sampleCount = 0

        for (boundary in 1 until columns) {
            val x = originX + boundary * cellSize
            for (row in 0 until rows) {
                for (offset in sampleOffsets) {
                    val y = (originY + (row + offset) * cellSize).roundToInt()
                    val center = luminanceAt(x, y)
                    val left = luminanceAt(x - shoulder, y)
                    val right = luminanceAt(x + shoulder, y)
                    contrastTotal += abs((left + right) / 2f - center)
                    sampleCount++
                }
            }
        }

        for (boundary in 1 until rows) {
            val y = originY + boundary * cellSize
            for (column in 0 until columns) {
                for (offset in sampleOffsets) {
                    val x = (originX + (column + offset) * cellSize).roundToInt()
                    val center = luminanceAt(x, y)
                    val above = luminanceAt(x, y - shoulder)
                    val below = luminanceAt(x, y + shoulder)
                    contrastTotal += abs((above + below) / 2f - center)
                    sampleCount++
                }
            }
        }

        return if (sampleCount == 0) 0f else contrastTotal / sampleCount
    }
}
