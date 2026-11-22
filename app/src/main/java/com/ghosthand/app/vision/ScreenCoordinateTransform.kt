package com.ghosthand.app.vision

/** Continuous pixel-boundary coordinate in the captured vision frame. */
data class FramePoint(val x: Float, val y: Float)

/** Continuous pixel-boundary coordinate on the active touch display. */
data class ScreenPoint(val x: Float, val y: Float)

/** Immutable logical-pixel snapshot of the active default display. */
data class ActiveDisplaySnapshot(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val densityDpi: Int
)

/**
 * Explicit affine mapping from a vision bitmap to the display receiving
 * AccessibilityService gestures. Crop offsets and scale are first-class data.
 *
 * Smart Play only dispatches for a known full-display capture. Unknown app
 * window crops/letterboxing are rejected instead of being treated as full screen.
 */
data class ScreenCoordinateTransform(
    val frameWidth: Int,
    val frameHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val displayId: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayRotation: Int,
    val captureLeftPx: Float,
    val captureTopPx: Float,
    val captureWidthPx: Float,
    val captureHeightPx: Float,
    val fullDisplayCapture: Boolean
) {
    fun frameToScreen(point: FramePoint): ScreenPoint? {
        if (!isStructurallyMappable()) return null
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        if (point.x < 0f || point.y < 0f ||
            point.x >= frameWidth.toFloat() || point.y >= frameHeight.toFloat()
        ) return null

        val x = captureLeftPx + point.x * (captureWidthPx / frameWidth.toFloat())
        val y = captureTopPx + point.y * (captureHeightPx / frameHeight.toFloat())
        if (!x.isFinite() || !y.isFinite() ||
            x < 0f || y < 0f || x >= displayWidth || y >= displayHeight
        ) return null
        return ScreenPoint(x, y)
    }

    /**
     * Aspect ratio alone is deliberately insufficient. The active display id,
     * complete pixel dimensions, rotation, viewport origin and full-screen
     * capture declaration must all agree.
     */
    fun isCompatible(
        current: ActiveDisplaySnapshot,
        actualFrameWidth: Int = frameWidth,
        actualFrameHeight: Int = frameHeight
    ): Boolean {
        if (!isStructurallyMappable()) return false
        if (actualFrameWidth != frameWidth || actualFrameHeight != frameHeight) return false
        val scaleX = frameWidth.toFloat() / sourceWidth
        val scaleY = frameHeight.toFloat() / sourceHeight
        val scaleMismatch = kotlin.math.abs(scaleX - scaleY) / maxOf(scaleX, scaleY)
        // Bitmap dimensions are rounded to whole pixels after uniform scaling.
        // Permit that rounding, but not an image stretched to the display ratio.
        if (scaleMismatch > 0.005f) return false
        if (!fullDisplayCapture) return false
        if (sourceWidth != displayWidth || sourceHeight != displayHeight) return false
        if (captureLeftPx != 0f || captureTopPx != 0f ||
            captureWidthPx != displayWidth.toFloat() ||
            captureHeightPx != displayHeight.toFloat()
        ) return false
        return current.displayId == displayId &&
            current.width == displayWidth &&
            current.height == displayHeight &&
            current.rotation == displayRotation
    }

    private fun isStructurallyMappable(): Boolean {
        if (frameWidth <= 0 || frameHeight <= 0 ||
            sourceWidth <= 0 || sourceHeight <= 0 ||
            displayWidth <= 0 || displayHeight <= 0 ||
            displayId < 0 || displayRotation !in 0..3
        ) return false
        if (!captureLeftPx.isFinite() || !captureTopPx.isFinite() ||
            !captureWidthPx.isFinite() || !captureHeightPx.isFinite()
        ) return false
        if (captureLeftPx < 0f || captureTopPx < 0f ||
            captureWidthPx <= 0f || captureHeightPx <= 0f
        ) return false
        return captureLeftPx + captureWidthPx <= displayWidth.toFloat() + 0.001f &&
            captureTopPx + captureHeightPx <= displayHeight.toFloat() + 0.001f
    }

    fun diagnostic(): String =
        "frame=${frameWidth}x${frameHeight}; source=${sourceWidth}x${sourceHeight}; " +
            "display#$displayId=${displayWidth}x${displayHeight}@r$displayRotation; " +
            "viewport=($captureLeftPx,$captureTopPx,$captureWidthPx,$captureHeightPx); " +
            "full=$fullDisplayCapture"

    companion object {
        fun fullDisplay(
            frameWidth: Int,
            frameHeight: Int,
            display: ActiveDisplaySnapshot
        ) = ScreenCoordinateTransform(
            frameWidth, frameHeight,
            sourceWidth = display.width,
            sourceHeight = display.height,
            displayId = display.displayId,
            displayWidth = display.width,
            displayHeight = display.height,
            displayRotation = display.rotation,
            captureLeftPx = 0f,
            captureTopPx = 0f,
            captureWidthPx = display.width.toFloat(),
            captureHeightPx = display.height.toFloat(),
            fullDisplayCapture = true
        )

        /** A known crop maps mathematically, but is not eligible for Smart Play. */
        fun cropped(
            frameWidth: Int,
            frameHeight: Int,
            display: ActiveDisplaySnapshot,
            left: Float,
            top: Float,
            width: Float,
            height: Float
        ) = ScreenCoordinateTransform(
            frameWidth, frameHeight,
            sourceWidth = display.width,
            sourceHeight = display.height,
            displayId = display.displayId,
            displayWidth = display.width,
            displayHeight = display.height,
            displayRotation = display.rotation,
            captureLeftPx = left,
            captureTopPx = top,
            captureWidthPx = width,
            captureHeightPx = height,
            fullDisplayCapture = false
        )
    }
}
