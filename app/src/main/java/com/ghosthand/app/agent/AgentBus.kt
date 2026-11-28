package com.ghosthand.app.agent

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

object AgentBus {
    val frame = MutableStateFlow<Bitmap?>(null)
    @Volatile var casterActive = false
        private set
    @Volatile private var lastFrameAtElapsedMs: Long = 0L

    fun push(bitmap: Bitmap) {
        frame.value = bitmap
        lastFrameAtElapsedMs = SystemClock.elapsedRealtime()
        casterActive = true
    }

    /** elapsedRealtime() when the current frame arrived. */
    val lastFrameAtMs: Long get() = lastFrameAtElapsedMs

    fun isFrameFresh(maxAgeMs: Long = 1_600L): Boolean {
        val capturedAt = lastFrameAtElapsedMs
        return casterActive &&
            capturedAt > 0L &&
            SystemClock.elapsedRealtime() - capturedAt <= maxAgeMs
    }

    fun clear() {
        frame.value = null
        lastFrameAtElapsedMs = 0L
        casterActive = false
    }
}
