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

    // Woodoku Blast builds can use either an 8x8 or 9x9 board. Prefer the
    // framed-square detector, which scores the visible cell seams for both
    // candidates; use explicit grid-line detection as a fallback.
    fun calibrate(bmp: Bitmap): Geo? =
        (calibrateFramedSquare(bmp) ?: calibrateGridLines(bmp))
            ?.takeIf { it.cols in 8..9 && it.rows == it.cols }

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
     * frame. Find that frame, then test both 8x8 and 9x9 cell lattices against
     * the actual internal seams instead of assuming a fixed board size.
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

        // The Fold's cover and inner displays have very different aspect
        // ratios. Search most of the usable vertical span instead of assuming
        // a conventional phone's board position.
        val topLo = (h * 0.05f).toInt().coerceAtLeast(3)
        val topHi = (h * 0.70f).toInt().coerceAtMost(h - 3)
        val bottomLo = (h * 0.30f).toInt().coerceAtLeast(3)
        val bottomHi = (h * 0.96f).toInt().coerceAtMost(h - 3)
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

            // The Fold's inner display can place a square board around
            // 74% of display width. Search beyond the old 12% edge margins.
            val leftEnd = (w * 0.30f).toInt().coerceAtLeast(3)
            val rightStart = (w * 0.70f).toInt().coerceAtMost(w - 3)
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

        // A square board is constrained by both display width and height.
        // This remains sensible on the narrow cover screen and the wider
        // unfolded screen instead of assuming the board spans 96% of width.
        val targetSide = minOf(w * 0.96f, h * 0.82f)
        var bestTop = -1
        var bestBottom = -1
        var bestRails: Rails? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for ((top, topStrength) in topCandidates) {
            if (topStrength < 12f) continue
            for ((bottom, bottomStrength) in bottomCandidates) {
                if (bottomStrength < 10f) continue
                val height = (bottom - top).toFloat()
                if (height < targetSide * 0.70f || height > targetSide * 1.20f) continue

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
        // the cell lattice. Test both supported sizes using the estimated
        // interior pitch, then prefer the candidate whose internal seams line
        // up most strongly with the screenshot.
        data class GridCandidate(
            val divisions: Int,
            val originX: Int,
            val originY: Int,
            val cell: Int,
            val alignment: Float
        )

        val candidates = (8..9).mapNotNull { divisions ->
            val roughCell = (boardWidth + boardHeight) / (2f * divisions)
            if (roughCell < 8f) return@mapNotNull null

            // The inner lattice begins slightly inside the decorative rail.
            val insetX = (roughCell * 0.125f).roundToInt().coerceAtLeast(2)
            val insetY = (roughCell * 0.125f).roundToInt().coerceAtLeast(2)
            val playableWidth = boardWidth - 2f * insetX
            val playableHeight = boardHeight - 2f * insetY
            if (playableWidth <= 0f || playableHeight <= 0f) return@mapNotNull null

            val cell = ((playableWidth + playableHeight) / (2f * divisions)).roundToInt()
            if (cell < 8) return@mapNotNull null

            val ox = (left + insetX).coerceIn(0, w - 1)
            val oy = (bestTop + insetY).coerceIn(0, h - 1)
            if (ox + divisions * cell > w + cell * 0.08f ||
                oy + divisions * cell > h + cell * 0.08f
            ) return@mapNotNull null

            val alignment = GridAlignmentScorer.score(
                divisions, divisions, ox, oy, cell, ::lum
            )
            GridCandidate(divisions, ox, oy, cell, alignment)
        }.sortedByDescending { it.alignment }

        val best = candidates.firstOrNull() ?: return null
        val runnerUp = candidates.getOrNull(1)
        // Weak or nearly tied candidates are ambiguous. A false board size
        // is more dangerous than pausing until a clearer frame arrives.
        if (best.alignment < 4f ||
            (runnerUp != null && best.alignment - runnerUp.alignment < 1.5f)
        ) return null

        return Geo(
            best.originX,
            best.originY,
            best.cell,
            cols = best.divisions,
            rows = best.divisions
        )
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
