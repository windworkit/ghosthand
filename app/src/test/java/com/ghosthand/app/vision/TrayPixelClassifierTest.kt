package com.ghosthand.app.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Colours sampled from a real Woodoku Blast tray on a Galaxy Z Fold 7. */
class TrayPixelClassifierTest {

    private val bg = Triple(66, 71, 191)

    private fun tile(r: Int, g: Int, b: Int) =
        TrayPixelClassifier.isTile(r, g, b, bg.first, bg.second, bg.third)

    @Test
    fun pieceDropShadowIsNotATile() {
        assertFalse(tile(46, 50, 137))
    }

    @Test
    fun backdropIsNotATile() {
        assertFalse(tile(69, 72, 201))
    }

    @Test
    fun realTileFacesAreTiles() {
        assertTrue("purple", tile(156, 118, 217))
        assertTrue("orange", tile(236, 127, 44))
        assertTrue("dark red", tile(195, 51, 74))
        assertTrue("apple tile blue", tile(0, 89, 243))
        assertTrue("dark seam inside a piece", tile(60, 33, 88))
    }
}
