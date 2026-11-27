package com.ghosthand.app.vision

data class RgbColor(val red: Int, val green: Int, val blue: Int) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
    }

    val luminance: Int get() = (red + green + blue) / 3
}

data class CellColorSamples(
    val center: RgbColor,
    val corners: List<RgbColor>
)

data class ClassifiedCell(
    val occupied: Boolean,
    val luminance: Int,
    val backgroundDistance: Int
)

data class BoardOccupancyResult(
    val cells: Array<Array<ClassifiedCell>>,
    val background: RgbColor,
    val backgroundSupport: Float
)

/**
 * Distinguishes occupied cells from the empty board surface.
 *
 * The background is learned from the perimeters of cells, not from the
 * frequency of their centers. On a crowded level, colored blocks can occupy
 * more centers than empty cells; their common color must not become the
 * background reference.
 */
object BoardOccupancyClassifier {

    fun classify(samples: Array<Array<CellColorSamples>>): BoardOccupancyResult {
        require(samples.isNotEmpty() && samples.all { it.isNotEmpty() }) {
            "Board color samples must be a non-empty rectangular grid"
        }
        val rows = samples.first().size
        require(samples.all { it.size == rows }) {
            "Board color samples must have matching column heights"
        }

        val buckets = HashMap<Int, MutableList<RgbColor>>()
        var perimeterSampleCount = 0
        samples.forEach { column ->
            column.forEach { cell ->
                cell.corners.forEach { color ->
                    val key = bucketKey(color)
                    buckets.getOrPut(key) { mutableListOf() }.add(color)
                    perimeterSampleCount++
                }
            }
        }
        require(perimeterSampleCount > 0) {
            "Every cell must provide at least one perimeter sample"
        }

        val backgroundSamples = buckets.maxByOrNull { it.value.size }!!.value
        val background = RgbColor(
            backgroundSamples.sumOf { it.red } / backgroundSamples.size,
            backgroundSamples.sumOf { it.green } / backgroundSamples.size,
            backgroundSamples.sumOf { it.blue } / backgroundSamples.size
        )
        val support = backgroundSamples.size.toFloat() / perimeterSampleCount

        val luminances = samples.flatMap { column ->
            column.map { it.center.luminance }
        }
        val darkest = luminances.minOrNull() ?: 255
        val brightnessThreshold = maxOf(76, darkest + 28)

        val classified = Array(samples.size) { x ->
            Array(rows) { y ->
                val center = samples[x][y].center
                val distance = kotlin.math.abs(center.red - background.red) +
                    kotlin.math.abs(center.green - background.green) +
                    kotlin.math.abs(center.blue - background.blue)
                ClassifiedCell(
                    occupied = center.luminance > brightnessThreshold || distance >= 48,
                    luminance = center.luminance,
                    backgroundDistance = distance
                )
            }
        }
        return BoardOccupancyResult(classified, background, support)
    }

    private fun bucketKey(color: RgbColor): Int =
        ((color.red shr 4) shl 8) or ((color.green shr 4) shl 4) or (color.blue shr 4)
}
