package com.ghosthand.app.agent

interface Motor {
    suspend fun tap(x: Float, y: Float)
    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean
    suspend fun idle(ms: Long)

    /**
     * True only when the captured frame has the same aspect ratio as the
     * physical display that receives AccessibilityService gestures. This
     * catches app-window crops/letterboxing and stale fold-panel captures
     * before a bad coordinate transform can send pieces off the board.
     */
    fun isFrameAligned(frameWidth: Int, frameHeight: Int): Boolean = true
}
