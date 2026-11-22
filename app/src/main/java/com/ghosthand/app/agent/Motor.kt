package com.ghosthand.app.agent

import com.ghosthand.app.vision.FramePoint
import com.ghosthand.app.vision.ScreenCoordinateTransform

interface Motor {
    /** Frame-pixel tap; implementation maps and validates through the frame transform. */
    suspend fun tap(point: FramePoint, transform: ScreenCoordinateTransform): Boolean

    /** Frame-pixel drag; the implementation must map and validate both endpoints. */
    suspend fun drag(
        start: FramePoint,
        end: FramePoint,
        transform: ScreenCoordinateTransform,
        ms: Long
    ): Boolean

    suspend fun idle(ms: Long)

    fun isFrameAligned(
        transform: ScreenCoordinateTransform,
        frameWidth: Int,
        frameHeight: Int
    ): Boolean = false
}
