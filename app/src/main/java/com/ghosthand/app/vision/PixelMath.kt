package com.ghosthand.app.vision

import android.graphics.Bitmap
import android.graphics.Color

object PixelMath {

    class Stats(val meanSat: Float, val edgeDensity: Float, val distinctColors: Int)

    fun stats(bmp: Bitmap): Stats {
        val w = bmp.width; val h = bmp.height
        var satSum = 0f; var satN = 0
        var edges = 0; var edgeN = 0
        val colors = HashSet<Int>()
        var prevRowLum: IntArray? = null
        var y = 2
        while (y < h - 8) {
            val lums = IntArray((w / 8) + 1)
            var x = 2; var i = 0
            while (x < w - 8) {
                val px = bmp.getPixel(x, y)
                val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
                colors.add(((px shr 4) and 0x0F0F0F))
                val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
                if (mx > 0) satSum += (mx - mn).toFloat() / mx; satN++
                lums[i] = (r + g + b) / 3
                if (i > 0 && kotlin.math.abs(lums[i] - lums[i - 1]) > 42) edges++
                edgeN++
                x += 8; i++
            }
            prevRowLum?.let { p ->
                var j = 0
                while (j < lums.size && j < p.size) {
                    if (kotlin.math.abs(lums[j] - p[j]) > 42) edges++
                    j++
                }
            }
            prevRowLum = lums
            y += 8
        }
        return Stats(satSum / satN.coerceAtLeast(1), edges.toFloat() / edgeN.coerceAtLeast(1), colors.size)
    }

    const val SEARCH_TOP = 0.12f
    const val SEARCH_BOTTOM = 0.92f
}
