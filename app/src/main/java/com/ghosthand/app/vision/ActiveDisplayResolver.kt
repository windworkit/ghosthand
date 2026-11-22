package com.ghosthand.app.vision

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Resolves the logical pixel dimensions used by AccessibilityService gestures.
 * WindowMetrics describes window bounds and can differ from full-display touch
 * coordinates, so it is intentionally not used for this mapping.
 */
object ActiveDisplayResolver {
    fun resolve(context: Context): ActiveDisplaySnapshot? = runCatching {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        if (size.x <= 0 || size.y <= 0) return null

        val displayContext = context.createDisplayContext(display)
        val density = displayContext.resources.configuration.densityDpi
            .takeIf { it > 0 }
            ?: displayContext.resources.displayMetrics.densityDpi
        if (density <= 0) return null

        ActiveDisplaySnapshot(
            displayId = display.displayId,
            width = size.x,
            height = size.y,
            rotation = display.rotation,
            densityDpi = density
        )
    }.getOrNull()
}
