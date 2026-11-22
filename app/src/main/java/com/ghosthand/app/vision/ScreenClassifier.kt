package com.ghosthand.app.vision

sealed interface ScreenState {
    data class Gameplay(val boardVisible: Boolean) : ScreenState
    data class Ad(val skipAt: Pair<Float, Float>?, val closeAt: Pair<Float, Float>?) : ScreenState
    data class Menu(val buttons: List<Pair<String, Pair<Float, Float>>>) : ScreenState
    data object Blank : ScreenState
}

class ScreenClassifier(private val ocr: Ocr) {

    private val adWords = listOf(
        "skip", "install", "download", "play now", "get the app", "shop now",
        "learn more", "sponsored", "sign up", "register", "order now", "open app"
    )
    private val menuWords = listOf(
        "play", "continue", "resume", "retry", "restart", "ok", "yes", "tap to start",
        "start", "next", "again", "game over", "score"
    )

    suspend fun classify(bmp: android.graphics.Bitmap): ScreenState {
        val words = ocr.words(bmp)

        val adHit = words.firstOrNull { w ->
            val t = w.text.lowercase().trim()
            (t == "ad") || adWords.any { t.contains(it) }
        }
        if (adHit != null) {
            val skip = words.firstOrNull { it.text.lowercase().contains("skip") }
                ?.let { it.x to it.y }
            val close = words.firstOrNull {
                it.text.trim().equals("x", true) && it.w < 0.1f
            }?.let { it.x to it.y }
            return ScreenState.Ad(skipAt = skip, closeAt = close)
        }

        val s = PixelMath.stats(bmp)

        if (s.distinctColors < 12 && s.edgeDensity < 0.03f) return ScreenState.Blank

        if (s.edgeDensity > 0.16f && s.distinctColors > 45) {
            val menuish = words.count { w -> menuWords.any { w.text.lowercase().trim() == it } }
            return if (menuish >= 2) menuState(words) else ScreenState.Gameplay(boardVisible = true)
        }

        val x = words.firstOrNull { it.text.trim().equals("x", true) && it.w < 0.1f }
        if (x != null) return ScreenState.Ad(skipAt = null, closeAt = x.x to x.y)

        return menuState(words)
    }

    private fun menuState(words: List<OcrWord>): ScreenState.Menu =
        ScreenState.Menu(
            words.filter { w -> menuWords.any { w.text.lowercase().trim().contains(it) } }
                .map { it.text.lowercase().trim() to (it.x to it.y) }
        )
}
