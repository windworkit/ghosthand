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
    val backgroundDistance: Int,
    val centerColorSignature: Int
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

    fun read(
        words: List<OcrWord>,
        lines: List<OcrWord>,
        width: Int,
        height: Int
    ): ObjectiveSnapshot {
        // Prefer complete OCR lines for matching names and x/y counters, but
        // retain token-level numbers when ML Kit splits the line into elements.
        val counters = lines.mapNotNull { line ->
            val normalized = line.text.lowercase()
            val type = aliases.entries.firstOrNull { (_, values) ->
                values.any { alias -> Regex("""\b${Regex.escape(alias)}\b""").containsMatchIn(normalized) }
            }?.key ?: return@mapNotNull null
            val ratio = RATIO.find(line.text)?.let { match ->
                match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
            }
            ObjectiveCounterObservation(
                type = type,
                current = ratio?.first,
                target = ratio?.second,
                screenX = line.x,
                screenY = line.y,
                sourceText = line.text
            )
        }.distinctBy {
            Triple(
                it.type,
                (it.screenX * 100).roundToInt(),
                (it.screenY * 100).roundToInt()
            )
        }

        val numeric = words.mapNotNull { word ->
            word.text.takeIf {
                it.matches(NUMBER) || RATIO.containsMatchIn(it)
            }
        }
        return ObjectiveSnapshot(counters, numeric, width, height)
    }

    private companion object {
        val RATIO = Regex("""(\d+)\s*[/|:]\s*(\d+)""")
        val NUMBER = Regex("""\d+""")
    }
}
