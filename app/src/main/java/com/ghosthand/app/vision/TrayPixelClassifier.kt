package com.ghosthand.app.vision

import kotlin.math.abs

/**
 * Pure RGB rules for telling tray tile faces from the tray backdrop.
 * Kept free of android.graphics so it can be unit-tested on the JVM.
 */
object TrayPixelClassifier {

    /**
     * True for a colourful tile pixel. Tiles are found by RGB distance from the
     * sampled backdrop (dark red tiles are darker than the blue backdrop, so a
     * brightness threshold would lose them), but Woodoku Blast draws a drop
     * shadow under every tray piece that is the backdrop colour darkened to
     * about 70% (measured on a Fold 7: shadow 46,50,137 on backdrop 66,71,191).
     * That shadow passes the distance test, and counting it inflated a 1x4 bar
     * into a "5x2, 7 cells" piece that fit nowhere. Shadows are excluded.
     */
    fun isTile(r: Int, g: Int, b: Int, br: Int, bg: Int, bb: Int): Boolean {
        val distance = abs(r - br) + abs(g - bg) + abs(b - bb)
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        if (distance < 42 || chroma < 22) return false
        return !isBackdropShadow(r, g, b, br, bg, bb)
    }

    /**
     * A shadow keeps the backdrop's hue and only scales its brightness, so all
     * three channel ratios are nearly equal and below 1.
     */
    fun isBackdropShadow(r: Int, g: Int, b: Int, br: Int, bg: Int, bb: Int): Boolean {
        if (br < 12 || bg < 12 || bb < 12) return false
        val kr = r / br.toFloat()
        val kg = g / bg.toFloat()
        val kb = b / bb.toFloat()
        val mean = (kr + kg + kb) / 3f
        val spread = maxOf(kr, kg, kb) - minOf(kr, kg, kb)
        return mean in 0.35f..0.92f && spread <= 0.12f
    }
}
