package com.ghosthand.app.agent

import com.ghosthand.app.vision.Ocr
import com.ghosthand.app.vision.ScreenState

class AdSentinel(private val ocr: Ocr, private val motor: Motor) {

    private val rewardWords = listOf("claim", "reward", "continue", "collect", "ok",
        "yes", "double", "2x", "x2", "free", "gift")
    private val spendWords = listOf("buy", "purchase", "shop", "install", "download")

    suspend fun runAd(bmp: android.graphics.Bitmap, ad: ScreenState.Ad): Boolean {
        val words = ocr.words(bmp)

        words.firstOrNull { w -> spendWords.any { w.text.lowercase().trim().contains(it) } }?.let {
            val no = words.firstOrNull { w ->
                listOf("no thanks", "later", "not now", "maybe").any { w.text.lowercase().contains(it) }
            }
            if (no != null) { motor.tap(no.x, no.y); motor.idle(600); return false }
        }

        words.firstOrNull { w -> rewardWords.any { w.text.lowercase().trim().contains(it) } }?.let {
            motor.tap(it.x, it.y); motor.idle(900); return false
        }

        ad.skipAt?.let { motor.tap(it.first, it.second); motor.idle(800); return false }

        ad.closeAt?.let { motor.tap(it.first, it.second); motor.idle(700); return false }
        motor.tap(0.94f, 0.05f)
        motor.idle(1400)
        return false
    }
}
