package com.ghosthand.app.agent

import com.ghosthand.app.vision.CapturedFrame
import kotlinx.coroutines.flow.MutableStateFlow

object AgentBus {
    val frame = MutableStateFlow<CapturedFrame?>(null)
    @Volatile var casterActive = false
    fun push(captured: CapturedFrame) { frame.value = captured }
}
