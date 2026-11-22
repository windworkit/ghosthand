package com.ghosthand.app.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

object GameGeometry {

    data class Geo(
        val ox: Int, val oy: Int,
        val cell: Int,
        val cols: Int, val rows: Int
    )

    // Prefer the explicit outer frame for Woodoku-style 8x8 boards.
    // The line detector can mistake internal grid seams for the board edges
    // and return a plausible-looking but incorrect 10x6 (or similar) grid.
    fun calibrate(bmp: Bitmap): Geo? =
        calibrateFramedSquare(bmp) ?: calibrateGridLines(bmp)

    /**
     * Original line-based calibration. Kept as the first choice for games that
     * draw explicit grid separators.
     */
    private fun calibrateGridLines(bmp: Bitmap): Geo? {
        val w = bmp.width; val h = bmp.height
        val y0 = (h * PixelMath.SEARCH_TOP).toInt()
        val y1 = (h * PixelMath.SEARCH_BOTTOM).toInt()
        val x0 = (w * 0.06f).toInt(); val x1 = (w * 0.94f).toInt()
        if (x1 - x0 < 40 || y1 - y0 < 40) return null

        val lum = Array((y1 - y0) / 2) { IntArray((x1 - x0) / 2) }
        for (j in lum.indices) for (i in lum[0].indices) {
            val p = bmp.getPixel(x0 + i * 2, y0 + j * 2)
            lum[j][i] = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
        }

        val colStr = IntArray(lum[0].size) { i ->
            var s = 0
            for (j in 1 until lum.size) s += abs(lum[j][i] - lum[j - 1][i])
            s
        }
        val rowStr = IntArray(lum.size) { j ->
            var s = 0
            for (i in 1 until lum[0].size) s += abs(lum[j][i] - lum[j][i - 1])
            s
        }

        val xLines = evenlySpacedPeaks(colStr, minLines = 7, minSpacing = (lum[0].size / 13).coerceAtLeast(3))
            ?.map { it * 2 + x0 } ?: return null
        val yLines = evenlySpacedPeaks(rowStr, minLines = 7, minSpacing = (lum.size / 13).coerceAtLeast(3))
            ?.map { it * 2 + y0 } ?: return null

        val cw = (xLines.last() - xLines.first()) / (xLines.size - 1)
        val ch = (yLines.last() - yLines.first()) / (yLines.size - 1)
        if (cw < 6 || ch < 6 || xLines.size > 40) return null
        if (abs(cw - ch) > maxOf(cw, ch) * 0.35f) return null

        return Geo(xLines.first() - 1, yLines.first() - 1, (cw + ch) / 2,
            xLines.size - 1, yLines.size - 1)
    }

    /**
     * Woodoku-style boards often have no drawn grid lines: empty cells share
     * one dark surface and the only reliable geometry is the luminous outer
     * frame. Find that frame, then divide its square interior into 8 x 8 cells.
     */
    private fun calibrateFramedSquare(bmp: Bitmap): Geo? {
        val w = bmp.width
        val h = bmp.height
        if (w < 180 || h < 300) return null

        fun lum(x: Int, y: Int): Int {
            val p = bmp.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            return (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
        }

        fun horizontalEdge(y: Int): Float {
            var total = 0f
            var count = 0
            val left = (w * 0.04f).toInt()
            val right = (w * 0.96f).toInt()
            for (x in left until right step 2) {
                total += abs(lum(x, y - 2) - lum(x, y + 2)).toFloat()
                count++
            }
            return total / count.coerceAtLeast(1)
        }

        // On portrait Woodoku captures the board sits below the score
        // header and above the tray. Excluding the header and the first
        // internal grid rows prevents them from being mistaken for the frame.
        val topLo = (h * 0.22f).toInt().coerceAtLeast(3)
        val topHi = (h * 0.45f).toInt().coerceAtMost(h - 3)
        val bottomLo = (h * 0.58f).toInt().coerceAtLeast(3)
        val bottomHi = (h * 0.78f).toInt().coerceAtMost(h - 3)
        if (topHi <= topLo || bottomHi <= bottomLo) return null

        val topCandidates = (topLo until topHi)
            .map { it to horizontalEdge(it) }
            .sortedByDescending { it.second }
            .take(24)
        val bottomCandidates = (bottomLo until bottomHi)
            .map { it to horizontalEdge(it) }
            .sortedByDescending { it.second }
            .take(24)

        val targetSide = w * 0.96f
        var top = -1
        var bottom = -1
        var bestPairScore = Float.NEGATIVE_INFINITY
        for ((ty, ts) in topCandidates) for ((by, bs) in bottomCandidates) {
            val span = (by - ty).toFloat()
            if (span < w * 0.70f || span > w * 1.18f) continue
            val score = ts + bs - abs(span - targetSide) * 0.10f
            if (score > bestPairScore) {
                bestPairScore = score
                top = ty
                bottom = by
            }
        }
        if (top < 0 || bottom <= top || bestPairScore < 28f) return null

        fun verticalEdge(x: Int): Float {
            var total = 0f
            var count = 0
            val yStart = (top + (bottom - top) * 0.06f).roundToInt()
            val yEnd = (bottom - (bottom - top) * 0.06f).roundToInt()
            for (y in yStart until yEnd step 2) {
                total += abs(lum(x - 2, y) - lum(x + 2, y)).toFloat()
                count++
            }
            return total / count.coerceAtLeast(1)
        }

        // Restrict the vertical-edge search to the outside rails. If the
        // search begins too far inward, a strong internal column can win.
        val leftEnd = (w * 0.12f).toInt().coerceAtLeast(3)
        val rightStart = (w * 0.88f).toInt().coerceAtMost(w - 3)
        val leftCandidates = (2 until leftEnd)
            .map { it to verticalEdge(it) }
            .sortedByDescending { it.second }
            .take(20)
        val rightCandidates = (rightStart until w - 2)
            .map { it to verticalEdge(it) }
            .sortedByDescending { it.second }
            .take(20)

        var left = -1
        var right = -1
        var bestVerticalScore = Float.NEGATIVE_INFINITY
        val targetHeight = (bottom - top).toFloat()
        for ((lx, ls) in leftCandidates) for ((rx, rs) in rightCandidates) {
            val span = (rx - lx).toFloat()
            if (span < targetHeight * 0.72f || span > targetHeight * 1.25f) continue
            val score = ls + rs - abs(span - targetHeight) * 0.12f
            if (score > bestVerticalScore) {
                bestVerticalScore = score
                left = lx
                right = rx
            }
        }

        if (left < 0 || right <= left || bestVerticalScore < 18f) return null
        val boardWidth = (right - left).toFloat()
        val boardHeight = (bottom - top).toFloat()
        val aspect = boardWidth / boardHeight
        if (aspect !in 0.78f..1.22f) return null

        val cell = ((boardWidth + boardHeight) / 16f).roundToInt()
        if (cell < 8) return null

        // The detected edge is the bright outer frame; step just inside it so
        // cell-center samples land on the actual tile faces.
        val inset = (cell * 0.06f).roundToInt().coerceAtLeast(2)
        val ox = (left + inset).coerceIn(0, w - 1)
        val oy = (top + inset).coerceIn(0, h - 1)
        if (ox + 8 * cell > w + cell * 0.12f || oy + 8 * cell > h + cell * 0.12f) return null

        return Geo(ox, oy, cell, cols = 8, rows = 8)
    }

    private fun evenlySpacedPeaks(str: IntArray, minLines: Int, minSpacing: Int): List<Int>? {
        if (minSpacing < 3 || str.isEmpty()) return null
        val thr = str.average() * 1.6
        val idx = str.indices.sortedByDescending { str[it] }
        val picked = mutableListOf<Int>()
        for (i in idx) {
            if (str[i] < thr) break
            if (picked.any { abs(it - i) < minSpacing }) continue
            picked.add(i)
            if (picked.size >= minLines + 4) break
        }
        if (picked.size < minLines) return null
        picked.sort()
        var best: List<Int>? = null
        var run = mutableListOf(picked[0])
        for (k in 1 until picked.size) {
            val gap = picked[k] - run.last()
            val want = if (run.size > 1) (run.last() - run[run.size - 2]) else gap
            if (run.size < 2 || gap in (want * 0.8f).toInt()..(want * 1.25f).toInt()) run.add(picked[k])
            else { if ((best?.size ?: 0) < run.size) best = run.toList(); run = mutableListOf(picked[k]) }
        }
        if ((best?.size ?: 0) < run.size) best = run.toList()
        return best?.takeIf { it.size >= minLines }
    }
}
