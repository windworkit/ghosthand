package com.ghosthand.app.vision

sealed interface ScreenState {
    data class Gameplay(val boardVisible: Boolean) : ScreenState
    data class Ad(val skipAt: Pair<Float, Float>?, val closeAt: Pair<Float, Float>?) : ScreenState
    data class Menu(val buttons: List<Pair<String, Pair<Float, Float>>>) : ScreenState
    data object Blank : ScreenState
}

class ScreenClassifier(private val ocr: Ocr) {

    // Share the OCR result with objective recognition; avoid a second ML Kit
    // inference pass over every captured frame.
    var lastWords: List<OcrWord> = emptyList()
        private set

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
        lastWords = words
        val stats = PixelMath.stats(bmp)

        if (stats.distinctColors < 12 && stats.edgeDensity < 0.03f) {
            return ScreenState.Blank
        }

        val gameLike = stats.edgeDensity > 0.16f && stats.distinctColors > 45
        val adHit = words.firstOrNull { word ->
            val text = word.text.lowercase().trim()
            text == "ad" || adWords.any { text.contains(it) }
        }

        if (adHit != null) {
            // A top banner can contain "install", "download", or "play now"
            // while the actual game remains fully playable underneath it.
            // If the board calibrates, classify the game instead of handing
            // banner text to the ad handler.
            val visibleBoard = GameGeometry.calibrate(bmp) != null
            val isTopBanner = adHit.y <= 0.24f && visibleBoard && gameLike
            if (!isTopBanner) {
                val skip = words.firstOrNull {
                    it.text.lowercase().contains("skip")
                }?.let { it.x to it.y }
                val close = words.firstOrNull {
                    it.text.trim().equals("x", true) && it.w < 0.1f
                }?.let { it.x to it.y }
                return ScreenState.Ad(skipAt = skip, closeAt = close)
            }
        }

        if (gameLike) {
            // Exclude the banner strip from menu-word matching so ad copy
            // such as "play now" cannot turn an active board into a menu.
            val gameWords = words.filter { it.y > 0.22f }
            val menuish = gameWords.count { word ->
                menuWords.any { word.text.lowercase().trim() == it }
            }
            return if (menuish >= 2) menuState(gameWords)
            else ScreenState.Gameplay(boardVisible = GameGeometry.calibrate(bmp) != null)
        }

        val close = words.firstOrNull {
            it.text.trim().equals("x", true) && it.w < 0.1f &&
                it.x >= 0.78f && it.y <= 0.24f
        }
        if (close != null) {
            return ScreenState.Ad(skipAt = null, closeAt = close.x to close.y)
        }

        return menuState(words.filter { it.y > 0.18f })
    }

    private fun menuState(words: List<OcrWord>): ScreenState.Menu =
        ScreenState.Menu(
            words.filter { w -> menuWords.any { w.text.lowercase().trim().contains(it) } }
                .map { it.text.lowercase().trim() to (it.x to it.y) }
        )
}
