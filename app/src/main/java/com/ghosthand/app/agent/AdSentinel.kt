package com.ghosthand.app.agent

import com.ghosthand.app.vision.Ocr
import com.ghosthand.app.vision.CapturedFrame
import com.ghosthand.app.vision.FramePoint
import com.ghosthand.app.vision.ScreenCoordinateTransform
import com.ghosthand.app.vision.ScreenState

/**
 * Ad handling must be conservative. Promotional text is never a game control:
 * do not tap install, download, play-now, rewards, or guessed screen corners.
 * Only explicit skip/close controls supplied by the classifier may be used.
 */
class AdSentinel(private val ocr: Ocr, private val motor: Motor) {

    suspend fun runAd(bmp: android.graphics.Bitmap, ad: ScreenState.Ad, transform: ScreenCoordinateTransform): Boolean {
        val words = ocr.words(bmp)

        val explicitSkip = words.firstOrNull { word ->
            word.text.lowercase().trim().contains("skip") &&
                word.y in 0.20f..0.96f
        }
        if (explicitSkip != null) {
            if (!motor.tap(FramePoint(explicitSkip.x * bmp.width, explicitSkip.y * bmp.height), transform)) return false
            motor.idle(900)
            return false
        }

        // A close glyph is safe only in the conventional top-right corner.
        // Ignore an "X" elsewhere; it may be game art or part of an ad CTA.
        val close = ad.closeAt?.takeIf { (x, y) ->
            x >= 0.78f && y <= 0.24f
        }
        if (close != null) {
            if (!motor.tap(FramePoint(close.first * bmp.width, close.second * bmp.height), transform)) return false
            motor.idle(800)
            return false
        }

        // No safe dismissal control was recognized. Waiting is preferable to
        // accidentally opening an advertised game or purchasing/installing it.
        motor.idle(1400)
        return false
    }
}
