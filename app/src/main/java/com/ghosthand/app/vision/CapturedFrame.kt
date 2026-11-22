package com.ghosthand.app.vision

import android.graphics.Bitmap

/** Rectangle in continuous captured-frame pixel coordinates. */
data class FrameRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0f && top >= 0f && right > left && bottom > top
}

/** The frame and its immutable capture-to-touch calibration belong together. */
data class CapturedFrame(
    val bitmap: Bitmap,
    val transform: ScreenCoordinateTransform,
    val capturedAtMillis: Long
)
