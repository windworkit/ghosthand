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
    fun calibrate(bmp: Bitmap): Geo? {
        val candidate = calibrateFramedSquare(bmp) ?: calibrateGridLines(bmp)
        return candidate?.takeIf {
            it.cols == 8 && it.rows == 8 && isValid(it, bmp.width, bmp.height)
        }
    }

    /** Strictly validate the playable lattice; no edge clamping or 8% overflow. */
    fun isValid(geo: Geo, frameWidth: Int, frameHeight: Int): Boolean {
        if (frameWidth <= 0 || frameHeight <= 0 ||
            geo.cols != 8 || geo.rows != 8 || geo.cell < 6 ||
            geo.ox < 0 || geo.oy < 0
        ) return false

        val right = geo.ox.toLong() + geo.cols.toLong() * geo.cell
        val bottom = geo.oy.toLong() + geo.rows.toLong() * geo.cell
        if (right > frameWidth || bottom > frameHeight) return false

        // Explicitly check every cell center and its complete cell bounds.
        for (row in 0 until geo.rows) for (col in 0 until geo.cols) {
            val left = geo.ox + col * geo.cell
            val top = geo.oy + row * geo.cell
            val cx = left + geo.cell * 0.5f
            val cy = top + geo.cell * 0.5f
            if (left < 0 || top < 0 ||
                left + geo.cell > frameWidth || top + geo.cell > frameHeight ||
                cx !in 0f..frameWidth.toFloat() || cy !in 0f..frameHeight.toFloat()
            ) return false
        }
        return true
    }

    /** Center of a playable cell in captured-frame pixel coordinates. */
    fun cellCenter(geo: Geo, col: Int, row: Int): Pair<Float, Float>? {
        if (col !in 0 until geo.cols || row !in 0 until geo.rows || geo.cell <= 0) return null
        return (geo.ox + (col + 0.5f) * geo.cell) to
            (geo.oy + (row + 0.5f) * geo.cell)
    }

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

        // Reduce each thick tile seam to one candidate. Without non-maximum
        // suppression, a bright internal row can occupy most of the candidate
        // list and hide the actual top/bottom frame.
        fun strongestSeparated(
            start: Int,
            end: Int,
            scoreAt: (Int) -> Float,
            minDistance: Int,
            limit: Int
        ): List<Pair<Int, Float>> {
            if (end <= start) return emptyList()
            val ranked = (start until end)
                .map { it to scoreAt(it) }
                .sortedByDescending { it.second }
            val selected = mutableListOf<Pair<Int, Float>>()
            for (candidate in ranked) {
                if (selected.none { abs(it.first - candidate.first) < minDistance }) {
                    selected.add(candidate)
                    if (selected.size >= limit) break
                }
            }
            return selected
        }

        val topLo = (h * 0.22f).toInt().coerceAtLeast(3)
        val topHi = (h * 0.45f).toInt().coerceAtMost(h - 3)
        val bottomLo = (h * 0.58f).toInt().coerceAtLeast(3)
        val bottomHi = (h * 0.78f).toInt().coerceAtMost(h - 3)
        if (topHi <= topLo || bottomHi <= bottomLo) return null

        val separation = (w * 0.015f).roundToInt().coerceIn(6, 14)
        val topCandidates = strongestSeparated(
            topLo, topHi, ::horizontalEdge, separation, 12
        )
        val bottomCandidates = strongestSeparated(
            bottomLo, bottomHi, ::horizontalEdge, separation, 12
        )
        if (topCandidates.isEmpty() || bottomCandidates.isEmpty()) return null

        data class Rails(
            val left: Int,
            val right: Int,
            val leftStrength: Float,
            val rightStrength: Float,
            val score: Float
        )

        // Verify every horizontal-edge pair against the two vertical rails.
        // The old detector chose the strongest horizontal pair first. In this
        // game, a bright internal tile row plus the tray edge can look stronger
        // than the real board frame, producing a rectangle shifted off-board.
        fun findVerticalRails(top: Int, bottom: Int): Rails? {
            val yStart = (top + (bottom - top) * 0.06f).roundToInt()
            val yEnd = (bottom - (bottom - top) * 0.06f).roundToInt()
            if (yEnd <= yStart + 4) return null

            fun verticalEdge(x: Int): Float {
                var total = 0f
                var count = 0
                for (y in yStart until yEnd step 2) {
                    total += abs(lum(x - 2, y) - lum(x + 2, y)).toFloat()
                    count++
                }
                return total / count.coerceAtLeast(1)
            }

            val leftEnd = (w * 0.12f).toInt().coerceAtLeast(3)
            val rightStart = (w * 0.88f).toInt().coerceAtMost(w - 3)
            val leftCandidates = (2 until leftEnd)
                .map { it to verticalEdge(it) }
                .sortedByDescending { it.second }
                .take(12)
            val rightCandidates = (rightStart until w - 2)
                .map { it to verticalEdge(it) }
                .sortedByDescending { it.second }
                .take(12)

            var best: Rails? = null
            val targetHeight = (bottom - top).toFloat()
            for ((lx, ls) in leftCandidates) for ((rx, rs) in rightCandidates) {
                val span = (rx - lx).toFloat()
                if (span < targetHeight * 0.72f || span > targetHeight * 1.25f) continue
                if (ls < 8f || rs < 8f) continue
                val score = ls + rs - abs(span - targetHeight) * 0.12f
                if (best == null || score > best.score) {
                    best = Rails(lx, rx, ls, rs, score)
                }
            }
            return best
        }

        val targetSide = w * 0.96f
        var bestTop = -1
        var bestBottom = -1
        var bestRails: Rails? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for ((top, topStrength) in topCandidates) {
            if (topStrength < 12f) continue
            for ((bottom, bottomStrength) in bottomCandidates) {
                if (bottomStrength < 10f) continue
                val height = (bottom - top).toFloat()
                if (height < w * 0.70f || height > w * 1.18f) continue

                val rails = findVerticalRails(top, bottom) ?: continue
                val width = (rails.right - rails.left).toFloat()
                val horizontalScore =
                    topStrength + bottomStrength - abs(height - targetSide) * 0.10f
                // Explicitly prefer a square perimeter. This rejects a
                // plausible-looking internal row/tray-edge pairing when its
                // side rails do not enclose the same-sized board.
                val squarePenalty = abs(width - height) * 0.25f
                val score = horizontalScore + rails.score - squarePenalty
                if (score > bestScore) {
                    bestScore = score
                    bestTop = top
                    bestBottom = bottom
                    bestRails = rails
                }
            }
        }

        val rails = bestRails ?: return null
        if (bestTop < 0 || bestBottom <= bestTop || bestScore < 35f) return null
        val left = rails.left
        val right = rails.right
        val boardWidth = (right - left).toFloat()
        val boardHeight = (bestBottom - bestTop).toFloat()
        val aspect = boardWidth / boardHeight
        if (aspect !in 0.78f..1.22f) return null

        // The bright frame surrounds the playable cells; it is not itself
        // the cell lattice. Woodoku leaves a visible dark gutter between the
        // outer rail and the first/last cell. Using the full frame span as
        // 8*cell makes the pitch too large and accumulates target error toward
        // the far edge (enough to drop a piece into the neighboring cell).
        val roughCell = ((boardWidth + boardHeight) / 16f)
        if (roughCell < 8f) return null

        // Estimate the inset independently on both axes. On this board theme
        // the inner lattice begins about one eighth of a cell inside the rail.
        val insetX = (roughCell * 0.125f).roundToInt().coerceAtLeast(2)
        val insetY = (roughCell * 0.125f).roundToInt().coerceAtLeast(2)
        val playableWidth = boardWidth - 2f * insetX
        val playableHeight = boardHeight - 2f * insetY
        if (playableWidth <= 0f || playableHeight <= 0f) return null

        // Derive the pitch from the playable rectangle, not the decorative
        // frame. Averaging both axes keeps the board square after capture
        // scaling and small JPEG/edge-detection variations.
        val cell = ((playableWidth + playableHeight) / 16f).roundToInt()
        if (cell < 8) return null

        // Do not clamp a detector result to the bitmap. That would disguise
        // an invalid lattice and create drop centers beyond the playable frame.
        val ox = left + insetX
        val oy = bestTop + insetY
        if (ox < 0 || oy < 0 || ox + 8 * cell > w || oy + 8 * cell > h) return null

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
