package com.ghosthand.app.agent

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow

object AgentBus {
    val frame = MutableStateFlow<Bitmap?>(null)
    @Volatile var casterActive = false

    fun push(bitmap: Bitmap) {
        frame.value = bitmap
    }

    fun clear() {
        frame.value = null
        casterActive = false
    }
}
