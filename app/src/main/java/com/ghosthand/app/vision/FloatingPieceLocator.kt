package com.ghosthand.app.vision

import kotlin.math.abs

/**
 * Finds where the game draws a piece while the finger is still holding it.
 *
 * Guessing the finger-to-piece offset failed on a Fold 7 (a held 1x4 bar was
 * drawn about three rows below its target). Instead, Smart Play holds the
 * piece, captures a frame, and locates it: pixels that match the piece's own
 * colour (sampled from its tray tile) AND changed since the pre-drag frame.
 * Their bounding box, if it is about the size the piece should be at board
 * scale, gives the piece centre. Pure ARGB maths so it runs in JVM tests.
 */
object FloatingPieceLocator {

    data class Located(val centerX: Float, val centerY: Float, val width: Int, val height: Int)

    /**
     * @param before pre-drag frame, ARGB, row-major, [width] x [height]
     * @param after frame captured while the piece is held
     * @param left/top/right/bottom search rectangle (right/bottom exclusive)
     * @param pieceColor the piece's tile colour (ARGB)
     * @param cellPx board cell size in pixels
     */
    fun locate(
        before: IntArray, after: IntArray, width: Int, height: Int,
        left: Int, top: Int, right: Int, bottom: Int,
        pieceColor: Int, cellPx: Float, cellsW: Int, cellsH: Int
    ): Located? {
        if (before.size < width * height || after.size < width * height) return null
        val l = left.coerceIn(0, width); val r = right.coerceIn(l, width)
        val t = top.coerceIn(0, height); val b = bottom.coerceIn(t, height)
        if (r - l < 4 || b - t < 4 || cellPx <= 0f) return null

        // Coarse block grid: a block is "on" when at least half its pixels
        // are piece-coloured AND changed. Scattered gem sparkle never fills a
        // block; the floating piece fills many.
        val blk = maxOf(4, (cellPx / 16f).toInt())
        val gw = (r - l + blk - 1) / blk
        val gh = (b - t + blk - 1) / blk
        val counts = IntArray(gw * gh)
        for (y in t until b) {
            val row = y * width
            val gy = (y - t) / blk * gw
            for (x in l until r) {
                val a = after[row + x]
                if (dist(a, pieceColor) <= COLOR_TOLERANCE && dist(a, before[row + x]) >= CHANGE_THRESHOLD) {
                    counts[gy + (x - l) / blk]++
                }
            }
        }
        var on = BooleanArray(gw * gh) { counts[it] >= blk * blk / 2 }

        // Erode twice: removes thin structures such as the board frame glow
        // ring, which is piece-coloured enough and changes during a drag.
        repeat(ERODE_PASSES) {
            val eroded = BooleanArray(on.size)
            for (y in 1 until gh - 1) for (x in 1 until gw - 1) {
                val i = y * gw + x
                eroded[i] = on[i] && on[i - 1] && on[i + 1] && on[i - gw] && on[i + gw]
            }
            on = eroded
        }

        // Connected patches; seams between tiles split a piece into one patch
        // per tile, so nearby solid patches are merged into one group.
        val comps = components(on, gw, gh)
        val minBlocks = maxOf(4, ((cellPx / blk) * (cellPx / blk) * 0.08f).toInt())
        val solid = comps.filter { it[0] >= minBlocks }.sortedByDescending { it[0] }
        if (solid.isEmpty()) return null
        val group = solid.first().clone()
        val used = BooleanArray(solid.size).also { it[0] = true }
        val gap = kotlin.math.ceil(cellPx * MERGE_GAP_CELLS / blk).toInt()
        var grew = true
        while (grew) {
            grew = false
            for (k in solid.indices) {
                if (used[k]) continue
                val c = solid[k]
                val near = c[1] <= group[2] + gap && c[2] >= group[1] - gap &&
                    c[3] <= group[4] + gap && c[4] >= group[3] - gap
                if (near) {
                    used[k] = true; grew = true
                    group[0] += c[0]
                    group[1] = minOf(group[1], c[1]); group[2] = maxOf(group[2], c[2])
                    group[3] = minOf(group[3], c[3]); group[4] = maxOf(group[4], c[4])
                }
            }
        }

        // Undo the erosion margin, then convert blocks back to pixels.
        val x0 = l + (group[1] - ERODE_PASSES) * blk
        val x1 = l + (group[2] + 1 + ERODE_PASSES) * blk
        val y0 = t + (group[3] - ERODE_PASSES) * blk
        val y1 = t + (group[4] + 1 + ERODE_PASSES) * blk
        val w = x1 - x0
        val h = y1 - y0
        val expectedW = cellsW * cellPx
        val expectedH = cellsH * cellPx
        if (w < expectedW * MIN_SCALE || w > expectedW * MAX_SCALE) return null
        if (h < expectedH * MIN_SCALE || h > expectedH * MAX_SCALE) return null
        return Located((x0 + x1) / 2f, (y0 + y1) / 2f, w, h)
    }

    /** 4-connected components of [on]: each is [blocks, minX, maxX, minY, maxY]. */
    private fun components(on: BooleanArray, gw: Int, gh: Int): List<IntArray> {
        val label = BooleanArray(on.size)
        val out = mutableListOf<IntArray>()
        val queue = ArrayDeque<Int>()
        for (start in on.indices) {
            if (!on[start] || label[start]) continue
            label[start] = true
            queue.addLast(start)
            val c = intArrayOf(0, Int.MAX_VALUE, -1, Int.MAX_VALUE, -1)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val x = i % gw; val y = i / gw
                c[0]++
                c[1] = minOf(c[1], x); c[2] = maxOf(c[2], x)
                c[3] = minOf(c[3], y); c[4] = maxOf(c[4], y)
                if (x > 0 && on[i - 1] && !label[i - 1]) { label[i - 1] = true; queue.addLast(i - 1) }
                if (x < gw - 1 && on[i + 1] && !label[i + 1]) { label[i + 1] = true; queue.addLast(i + 1) }
                if (y > 0 && on[i - gw] && !label[i - gw]) { label[i - gw] = true; queue.addLast(i - gw) }
                if (y < gh - 1 && on[i + gw] && !label[i + gw]) { label[i + gw] = true; queue.addLast(i + gw) }
            }
            out += c
        }
        return out
    }

    /** Median colour of a small square patch, for sampling a tray tile. */
    fun sampleColor(pixels: IntArray, width: Int, height: Int, cx: Int, cy: Int, radius: Int): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in (cy - radius)..(cy + radius)) for (x in (cx - radius)..(cx + radius)) {
            if (x !in 0 until width || y !in 0 until height) continue
            val p = pixels[y * width + x]
            rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF
        }
        if (rs.isEmpty()) return 0
        rs.sort(); gs.sort(); bs.sort()
        val m = rs.size / 2
        return (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
    }

    private fun dist(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))

    private const val COLOR_TOLERANCE = 90
    private const val CHANGE_THRESHOLD = 60
    private const val ERODE_PASSES = 2
    private const val MERGE_GAP_CELLS = 0.6f
    // Bevels/highlights don't match the face colour, so a held piece measures
    // smaller than its true size (1x4 bar on a Fold 7: 488x88 of ~506x126).
    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 1.6f
}
