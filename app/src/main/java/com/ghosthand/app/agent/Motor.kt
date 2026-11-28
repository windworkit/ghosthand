package com.ghosthand.app.agent

interface Motor {
    suspend fun tap(x: Float, y: Float)
    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean
    suspend fun idle(ms: Long)

    /**
     * Press at the start, travel to the end and keep the finger DOWN, so the
     * caller can look at where the game draws the floating piece and correct
     * the aim before letting go. Coordinates are normalized (0..1). Returns
     * false when unsupported or Android cancelled the gesture.
     */
    suspend fun dragAndHold(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean = false

    /** Slide the held finger to a new normalized point without lifting. */
    suspend fun moveHeld(x: Float, y: Float, ms: Long): Boolean = false

    /** Lift the held finger where it is (the drop). */
    suspend fun releaseHeld(): Boolean = false

    /**
     * True only when the captured frame has the same aspect ratio as the
     * physical display that receives AccessibilityService gestures. This
     * catches app-window crops/letterboxing and stale fold-panel captures
     * before a bad coordinate transform can send pieces off the board.
     */
    fun isFrameAligned(frameWidth: Int, frameHeight: Int): Boolean = true

    /** False when capture has stopped or the latest frame is stale. */
    fun isFrameFresh(): Boolean = true
}
