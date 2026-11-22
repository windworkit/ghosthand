package com.ghosthand.app.vision

import android.graphics.Bitmap

object GameGeometry {

    data class Geo(
        val ox: Int, val oy: Int,
        val cell: Int,
        val cols: Int, val rows: Int
    )

    fun calibrate(bmp: Bitmap): Geo? {
        val w = bmp.width; val h = bmp.height
        val y0 = (h * PixelMath.SEARCH_TOP).toInt()
        val y1 = (h * PixelMath.SEARCH_BOTTOM).toInt()
        val x0 = (w * 0.06f).toInt(); val x1 = (w * 0.94f).toInt()
        if (x1 - x0 < 40 || y1 - y0 < 40) return null

        val lum = Array((y1 - y0) / 2) { IntArray((x1 - x0) / 2) }
        for (j in lum.indices) for (i in lum[0].indices) {
            val p = bmp.getPixel(x0 + i * 2, y0 + j * 2)
            lum[j][i] = (android.graphics.Color.red(p) + android.graphics.Color.green(p)
                    + android.graphics.Color.blue(p)) / 3
        }

        val colStr = IntArray(lum[0].size) { i ->
            var s = 0
            for (j in 1 until lum.size) s += kotlin.math.abs(lum[j][i] - lum[j - 1][i])
            s
        }
        val rowStr = IntArray(lum.size) { j ->
            var s = 0
            for (i in 1 until lum[0].size) s += kotlin.math.abs(lum[j][i] - lum[j][i - 1])
            s
        }

        val xLines = evenlySpacedPeaks(colStr, minLines = 7, minSpacing = (lum[0].size / 13).coerceAtLeast(3))
            ?.map { it * 2 + x0 } ?: return null
        val yLines = evenlySpacedPeaks(rowStr, minLines = 7, minSpacing = (lum.size / 13).coerceAtLeast(3))
            ?.map { it * 2 + y0 } ?: return null

        val cw = (xLines.last() - xLines.first()) / (xLines.size - 1)
        val ch = (yLines.last() - yLines.first()) / (yLines.size - 1)
        if (cw < 6 || ch < 6 || xLines.size > 40) return null
        if (kotlin.math.abs(cw - ch) > kotlin.math.max(cw, ch) * 0.35f) return null

        return Geo(xLines.first() - 1, yLines.first() - 1, (cw + ch) / 2,
            xLines.size - 1, yLines.size - 1)
    }

    private fun evenlySpacedPeaks(str: IntArray, minLines: Int, minSpacing: Int): List<Int>? {
        if (minSpacing < 3 || str.isEmpty()) return null
        val thr = str.average() * 1.6
        val idx = str.indices.sortedByDescending { str[it] }
        val picked = mutableListOf<Int>()
        for (i in idx) {
            if (str[i] < thr) break
            if (picked.any { kotlin.math.abs(it - i) < minSpacing }) continue
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
