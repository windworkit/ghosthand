package com.ghosthand.app.vision

import kotlin.math.sqrt

/**
 * Conservative color-based board occupancy classifier.
 *
 * Empty Woodoku cells share a dominant background color. Placed blocks are
 * colored tile faces and are separated from that background in RGB space.
 * Cells between the empty/occupied confidence bands are rejected instead of
 * being guessed empty, because one false-empty cell can produce an illegal
 * placement over an existing block.
 *
 * This class is deliberately Android-free so its classification rules can be
 * exercised by deterministic JVM tests.
 */
object BoardOccupancyClassifier {

    data class Rgb(val red: Int, val green: Int, val blue: Int)

    data class Classification(
        val grid: Array<BooleanArray>?,
        val diagnostic: String
    )

    private const val BACKGROUND_CLUSTER_RADIUS = 28.0
    // Be intentionally strict about declaring a cell empty. Woodoku tile faces
    // can be dark/blue and sit near the board's blue background in RGB space.
    // A borderline shade must reject the whole board, never become an empty
    // destination merely because it is closer to the background than expected.
    private const val EMPTY_MAX_DISTANCE = 14.0
    private const val OCCUPIED_MIN_DISTANCE = 42.0

    fun classify(cells: Array<Array<Rgb>>): Classification {
        if (cells.isEmpty() || cells.first().isEmpty()) {
            return Classification(null, "board rejected: no cell color samples")
        }
        val cols = cells.size
        val rows = cells.first().size
        if (cells.any { it.size != rows }) {
            return Classification(null, "board rejected: inconsistent cell sample dimensions")
        }

        val samples = cells.flatMap { it.asList() }
        val radiusSquared = BACKGROUND_CLUSTER_RADIUS * BACKGROUND_CLUSTER_RADIUS

        // Find the most common cell color neighborhood rather than using the
        // single darkest cell. The empty-cell background is normally the
        // dominant visual class even when occupied blocks have several colors.
        var backgroundCluster: List<Rgb> = emptyList()
        for (candidate in samples) {
            val nearby = samples.filter { distanceSquared(it, candidate) <= radiusSquared }
            if (nearby.size > backgroundCluster.size) {
                backgroundCluster = nearby
            }
        }

        val minimumBackgroundCells = maxOf(8, (samples.size * 0.10).toInt())
        if (backgroundCluster.size < minimumBackgroundCells) {
            return Classification(
                null,
                "board rejected: no reliable empty-cell color cluster " +
                    "(${backgroundCluster.size}/${samples.size}, need $minimumBackgroundCells)"
            )
        }

        // Refine the background estimate with per-channel medians so a few
        // nearby colored/noisy samples cannot pull the model toward them.
        val background = Rgb(
            median(backgroundCluster.map { it.red }),
            median(backgroundCluster.map { it.green }),
            median(backgroundCluster.map { it.blue })
        )

        val grid = Array(cols) { BooleanArray(rows) }
        val ambiguous = mutableListOf<String>()
        var emptyCount = 0
        var occupiedCount = 0

        for (x in 0 until cols) for (y in 0 until rows) {
            val distance = sqrt(distanceSquared(cells[x][y], background))
            when {
                distance <= EMPTY_MAX_DISTANCE -> {
                    grid[x][y] = false
                    emptyCount++
                }
                distance >= OCCUPIED_MIN_DISTANCE -> {
                    grid[x][y] = true
                    occupiedCount++
                }
                else -> ambiguous.add("$x,$y:${distance.toInt()}")
            }
        }

        if (ambiguous.isNotEmpty()) {
            val first = ambiguous.take(8).joinToString(";")
            return Classification(
                null,
                "board rejected: ${ambiguous.size} ambiguous cells [$first]; " +
                    "background=${background.red},${background.green},${background.blue}"
            )
        }

        if (emptyCount < minimumBackgroundCells) {
            return Classification(
                null,
                "board rejected: too few confidently empty cells " +
                    "($emptyCount, need $minimumBackgroundCells)"
            )
        }

        return Classification(
            grid,
            "board occupancy verified: empty=$emptyCount occupied=$occupiedCount " +
                "background=${background.red},${background.green},${background.blue}"
        )
    }

    private fun distanceSquared(a: Rgb, b: Rgb): Double {
        val red = (a.red - b.red).toDouble()
        val green = (a.green - b.green).toDouble()
        val blue = (a.blue - b.blue).toDouble()
        return red * red + green * green + blue * blue
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
