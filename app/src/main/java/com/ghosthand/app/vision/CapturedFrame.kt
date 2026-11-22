package com.ghosthand.app.vision

import android.graphics.Bitmap

/** The frame and its immutable capture-to-touch calibration belong together. */
data class CapturedFrame(
    val bitmap: Bitmap,
    val transform: ScreenCoordinateTransform,
    val capturedAtMillis: Long
)
