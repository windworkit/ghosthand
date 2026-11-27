package com.ghosthand.app.vision

import kotlin.math.roundToInt

/**
 * A cell can be known to be occupied without its special gameplay meaning
 * being known. Keep that distinction explicit so a future classifier cannot
 * silently turn flowers, objectives, or obstacles into ordinary blocks.
 */
enum class BoardObjectType {
    EMPTY,
    ORDINARY_BLOCK,
    FLOWER,
    BUTTERFLY,
    APPLE,
    BALLOON,
    BOMB,
    FIREWORK,
    GEM,
    CRATE,
    UNCLASSIFIED_OCCUPIED
}

data class BoardCellObservation(
    val column: Int,
    val row: Int,
    val occupied: Boolean,
    val objectType: BoardObjectType,
    val luminance: Int,
    val backgroundDistance: Int
)

/** Gameplay objectives that GhostHand may encounter in Woodoku Blast. */
enum class ObjectiveType {
    FLOWER,
    BUTTERFLY,
    APPLE,
    BALLOON,
    BOMB,
    FIREWORK,
    GEM,
    CRATE,
    SCORE,
    UNKNOWN
}

/**
 * OCR-derived evidence only. A null count means the icon/counter was not
 * reliably read; it must not be guessed from nearby numbers.
 */
data class ObjectiveCounterObservation(
    val type: ObjectiveType,
    val current: Int?,
    val target: Int?,
    val screenX: Float,
    val screenY: Float,
    val sourceText: String
)

data class ObjectiveSnapshot(
    val counters: List<ObjectiveCounterObservation>,
    val unclassifiedNumericTokens: List<String>,
    val capturedAtWidth: Int,
    val capturedAtHeight: Int
) {
    val hasRecognizedObjectives: Boolean get() = counters.isNotEmpty()
}

/**
 * Reads only explicit objective words and explicit x/y counter text.
 * Icon-only counters intentionally remain unclassified until real Fold 7
 * gameplay frames are available to build and validate visual templates.
 */
class ObjectiveReader {

    private val aliases = mapOf(
        ObjectiveType.FLOWER to setOf("flower", "flowers"),
        ObjectiveType.BUTTERFLY to setOf("butterfly", "butterflies"),
        ObjectiveType.APPLE to setOf("apple", "apples"),
        ObjectiveType.BALLOON to setOf("balloon", "balloons"),
        ObjectiveType.BOMB to setOf("bomb", "bombs"),
        ObjectiveType.FIREWORK to setOf("firework", "fireworks"),
        ObjectiveType.GEM to setOf("gem", "gems"),
        ObjectiveType.CRATE to setOf("crate", "crates", "box", "boxes"),
        ObjectiveType.SCORE to setOf("score", "points")
    )

    fun read(words: List<OcrWord>, width: Int, height: Int): ObjectiveSnapshot {
        val counters = words.mapNotNull { word ->
            val normalized = word.text.lowercase().trim().trim(',', '.', ':', ';', '!')
            val type = aliases.entries.firstOrNull { (_, values) ->
                normalized in values
            }?.key ?: return@mapNotNull null
            val ratio = RATIO.find(word.text)?.let { match ->
                match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
            }
            ObjectiveCounterObservation(
                type = type,
                current = ratio?.first,
                target = ratio?.second,
                screenX = word.x,
                screenY = word.y,
                sourceText = word.text
            )
        }.distinctBy { it.type to (it.screenY * 100).roundToInt() }

        val numeric = words.mapNotNull { word ->
            word.text.takeIf { it.matches(NUMBER) }
        }
        return ObjectiveSnapshot(counters, numeric, width, height)
    }

    private companion object {
        val RATIO = Regex("""(\d+)\s*[/|:]\s*(\d+)""")
        val NUMBER = Regex("""\d+""")
    }
}
