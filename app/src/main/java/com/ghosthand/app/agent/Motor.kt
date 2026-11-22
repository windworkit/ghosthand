package com.ghosthand.app.agent

interface Motor {
    suspend fun tap(x: Float, y: Float)
    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long)
    suspend fun idle(ms: Long)
}
