package com.ghosthand.app.vision

import org.junit.Assert.*
import org.junit.Test

class ScreenCoordinateTransformTest {
    @Test
    fun downscaledCoverDisplayMapsUsingIndependentExactScales() {
        val display = ActiveDisplaySnapshot(
            displayId = 0, width = 904, height = 2316,
            rotation = 0, densityDpi = 420
        )
        val transform = ScreenCoordinateTransform.fullDisplay(
            frameWidth = 281, frameHeight = 720, display = display
        )

        assertTrue(transform.isCompatible(display, 281, 720))
        val center = transform.frameToScreen(FramePoint(140.5f, 360f))!!
        assertEquals(452f, center.x, 0.01f)
        assertEquals(1158f, center.y, 0.01f)

        val nearBottomRight = transform.frameToScreen(FramePoint(280.5f, 719.5f))!!
        assertTrue(nearBottomRight.x < 904f)
        assertTrue(nearBottomRight.y < 2316f)
    }

    @Test
    fun innerDisplayAndCoverDisplayUseTheirOwnMetrics() {
        val cover = ActiveDisplaySnapshot(0, 904, 2316, 0, 420)
        val inner = ActiveDisplaySnapshot(0, 1812, 2176, 0, 420)

        val coverTransform = ScreenCoordinateTransform.fullDisplay(281, 720, cover)
        val innerTransform = ScreenCoordinateTransform.fullDisplay(599, 720, inner)

        assertTrue(coverTransform.isCompatible(cover))
        assertTrue(innerTransform.isCompatible(inner))
        assertFalse(coverTransform.isCompatible(inner))
        assertFalse(innerTransform.isCompatible(cover))
    }

    @Test
    fun rotationChangeIsRejectedEvenWhenDimensionsCouldLookSimilar() {
        val captured = ActiveDisplaySnapshot(0, 904, 2316, 0, 420)
        val rotated = ActiveDisplaySnapshot(0, 2316, 904, 1, 420)
        val transform = ScreenCoordinateTransform.fullDisplay(281, 720, captured)

        assertFalse(transform.isCompatible(rotated))
    }

    @Test
    fun displayIdentityAndResolutionChangesAreRejected() {
        val captured = ActiveDisplaySnapshot(0, 1080, 2400, 0, 420)
        val transform = ScreenCoordinateTransform.fullDisplay(324, 720, captured)

        assertFalse(transform.isCompatible(captured.copy(displayId = 2)))
        assertFalse(transform.isCompatible(captured.copy(width = 1078)))
        assertFalse(transform.isCompatible(captured.copy(height = 2398)))
        assertFalse(transform.isCompatible(captured, actualFrameWidth = 323, actualFrameHeight = 720))
    }

    @Test
    fun knownCropHasCorrectTranslationButIsNotEligibleForSmartPlay() {
        val display = ActiveDisplaySnapshot(0, 1000, 2000, 0, 420)
        val crop = ScreenCoordinateTransform.cropped(
            frameWidth = 500,
            frameHeight = 1000,
            display = display,
            left = 100f,
            top = 200f,
            width = 500f,
            height = 1000f
        )
        val center = crop.frameToScreen(FramePoint(250f, 500f))!!

        assertEquals(350f, center.x, 0.001f)
        assertEquals(700f, center.y, 0.001f)
        assertFalse(crop.isCompatible(display))
    }

    @Test
    fun pointsOutsideFrameAreRejectedRatherThanClamped() {
        val display = ActiveDisplaySnapshot(0, 904, 2316, 0, 420)
        val transform = ScreenCoordinateTransform.fullDisplay(281, 720, display)

        assertNull(transform.frameToScreen(FramePoint(-1f, 10f)))
        assertNull(transform.frameToScreen(FramePoint(281f, 10f)))
        assertNull(transform.frameToScreen(FramePoint(10f, 720f)))
        assertNull(transform.frameToScreen(FramePoint(Float.NaN, 10f)))
        assertNull(transform.frameToScreen(FramePoint(10f, Float.POSITIVE_INFINITY)))
    }

    @Test
    fun displayCornersAndCenterMapToCorrespondingTouchCoordinates() {
        val display = ActiveDisplaySnapshot(0, 1440, 3200, 0, 560)
        val transform = ScreenCoordinateTransform.fullDisplay(324, 720, display)

        assertEquals(ScreenPoint(0f, 0f), transform.frameToScreen(FramePoint(0f, 0f)))
        assertEquals(
            ScreenPoint(720f, 1600f),
            transform.frameToScreen(FramePoint(162f, 360f))
        )
        assertEquals(
            ScreenPoint(1435.5555f, 3195.5557f),
            transform.frameToScreen(FramePoint(323f, 719f))
        )
    }
}
