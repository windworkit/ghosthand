package com.ghosthand.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingPieceLocatorTest {

    private val w = 600
    private val h = 500
    private val cell = 64f
    private val board = rgb(29, 37, 83)
    private val purple = rgb(156, 116, 216)
    private val seam = rgb(60, 33, 88)
    private val glow = rgb(170, 150, 225)

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun fill(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        for (y in maxOf(0, y0) until minOf(h, y1)) for (x in maxOf(0, x0) until minOf(w, x1)) px[y * w + x] = c
    }

    /** A held 1x4 bar: tile faces split by dark seams, centred at (cx, cy). */
    private fun heldFrame(cx: Int, cy: Int): IntArray {
        val px = IntArray(w * h) { board }
        val left = cx - 2 * cell.toInt()
        val top = cy - cell.toInt() / 2
        fill(px, left, top, left + 4 * cell.toInt(), top + cell.toInt(), purple)
        for (k in 1..3) {
            val sx = left + k * cell.toInt()
            fill(px, sx - 4, top, sx + 4, top + cell.toInt(), seam)
        }
        // Board frame glow ring (thin, piece-like colour).
        fill(px, 20, 20, 580, 28, glow); fill(px, 20, 472, 580, 480, glow)
        fill(px, 20, 20, 28, 480, glow); fill(px, 572, 20, 580, 480, glow)
        // Gem sparkles: single changed pixels.
        for (i in 0 until 400) px[(i * 7919) % (w * h)] = purple
        return px
    }

    @Test
    fun findsHeldBarCentreDespiteSeamsGlowAndSparkle() {
        val before = IntArray(w * h) { board }
        val located = FloatingPieceLocator.locate(
            before, heldFrame(300, 300), w, h, 0, 0, w, h, purple, cell, 4, 1
        )
        assertNotNull(located)
        assertEquals(300f, located!!.centerX, cell * 0.15f)
        assertEquals(300f, located.centerY, cell * 0.15f)
    }

    @Test
    fun returnsNullWhenNothingIsHeld() {
        val before = IntArray(w * h) { board }
        val after = before.copyOf()
        for (i in 0 until 400) after[(i * 7919) % (w * h)] = purple
        assertNull(
            FloatingPieceLocator.locate(before, after, w, h, 0, 0, w, h, purple, cell, 4, 1)
        )
    }

    @Test
    fun rejectsBlobOfTheWrongSize() {
        // Expecting a 1x1 piece, but the held blob is a 4-wide bar.
        val before = IntArray(w * h) { board }
        assertNull(
            FloatingPieceLocator.locate(before, heldFrame(300, 300), w, h, 0, 0, w, h, purple, cell, 1, 1)
        )
    }
}
