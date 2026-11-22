package com.ghosthand.app.vision

import kotlin.math.roundToInt

/**
 * Pure Kotlin tray-mask analysis. Keeping this independent of Bitmap makes the
 * difficult RGB/band/shape rules regression-testable on the JVM.
 */
data class TrayShapeDetection(
    val cells: List<Pair<Int, Int>>,
    val width: Int,
    val height: Int,
    val anchorCellX: Int,
    val anchorCellY: Int,
    val grabX: Float,
    val grabY: Float,
    val bounds: FrameRect
)

object TrayMaskAnalyzer {
    fun isTilePixel(pixel: Int, background: Int): Boolean {
        val red = (pixel ushr 16) and 0xff
        val green = (pixel ushr 8) and 0xff
        val blue = pixel and 0xff
        val bgRed = (background ushr 16) and 0xff
        val bgGreen = (background ushr 8) and 0xff
        val bgBlue = background and 0xff
        val distance = kotlin.math.abs(red - bgRed) +
            kotlin.math.abs(green - bgGreen) +
            kotlin.math.abs(blue - bgBlue)
        val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
        return distance >= 42 && chroma >= 22
    }

    fun analyzeSlots(
        slotMasks: List<Array<BooleanArray>?>,
        pitch: Float
    ): List<TrayShapeDetection> = slotMasks.mapNotNull { mask ->
        mask?.let { analyze(it, pitch) }
    }

    fun analyze(mask: Array<BooleanArray>, pitch: Float): TrayShapeDetection? {
        if (!pitch.isFinite() || pitch < 4f || mask.isEmpty()) return null
        val regionWidth = mask.firstOrNull()?.size ?: return null
        if (regionWidth == 0 || mask.any { it.size != regionWidth }) return null
        val regionHeight = mask.size
        val columnInk = IntArray(regionWidth)
        val rowInk = IntArray(regionHeight)
        for (y in 0 until regionHeight) for (x in 0 until regionWidth) {
            if (mask[y][x]) {
                columnInk[x]++
                rowInk[y]++
            }
        }

        val xBands = projectionBands(columnInk, pitch)
        val yBands = projectionBands(rowInk, pitch)
        if (xBands.isEmpty() || yBands.isEmpty() ||
            xBands.size > 5 || yBands.size > 5
        ) return null

        val detectedCells = mutableSetOf<Pair<Int, Int>>()
        for (row in yBands.indices) for (col in xBands.indices) {
            if (patchDensity(mask, xBands[col], yBands[row], pitch * 0.28f) >= 0.26f) {
                detectedCells.add(col to row)
            }
        }
        val connected = largestConnectedComponent(detectedCells)
        if (connected.isEmpty()) return null

        val minX = connected.minOf { it.first }
        val maxX = connected.maxOf { it.first }
        val minY = connected.minOf { it.second }
        val maxY = connected.maxOf { it.second }
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        if (width !in 1..5 || height !in 1..5 || connected.size > 25) return null

        val normalized = connected
            .map { (x, y) -> (x - minX) to (y - minY) }
            .sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
        val anchor = connected.minWithOrNull(
            compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first }
        ) ?: return null

        return TrayShapeDetection(
            cells = normalized,
            width = width,
            height = height,
            anchorCellX = anchor.first - minX,
            anchorCellY = anchor.second - minY,
            grabX = xBands[anchor.first],
            grabY = yBands[anchor.second],
            bounds = FrameRect(
                left = xBands[minX] - pitch * 0.5f,
                top = yBands[minY] - pitch * 0.5f,
                right = xBands[maxX] + pitch * 0.5f,
                bottom = yBands[maxY] + pitch * 0.5f
            )
        )
    }

    fun projectionBands(profile: IntArray, pitch: Float): List<Float> {
        if (profile.isEmpty() || !pitch.isFinite() || pitch <= 0f) return emptyList()
        val maximum = profile.maxOrNull()?.toFloat() ?: 0f
        if (maximum < 3f) return emptyList()
        val threshold = maxOf(2f, maximum * 0.20f)
        val centers = mutableListOf<Float>()
        var index = 0

        while (index < profile.size) {
            if (profile[index] < threshold) {
                index++
                continue
            }

            val start = index
            while (index < profile.size && profile[index] >= threshold) index++
            val end = index
            val runLength = end - start
            if (runLength < pitch * 0.40f) continue

            val count = (runLength / pitch).roundToInt().coerceIn(1, 5)
            if (count > 1 && runLength >= pitch * 1.45f) {
                for (part in 0 until count) {
                    centers.add(start + runLength * (part + 0.5f) / count)
                }
            } else {
                centers.add((start + end - 1) / 2f)
            }
        }
        return centers
    }

    private fun patchDensity(
        mask: Array<BooleanArray>,
        centerX: Float,
        centerY: Float,
        radius: Float
    ): Float {
        val height = mask.size
        val width = mask.firstOrNull()?.size ?: return 0f
        val left = (centerX - radius).toInt().coerceAtLeast(0)
        val right = (centerX + radius).toInt().coerceAtMost(width - 1)
        val top = (centerY - radius).toInt().coerceAtLeast(0)
        val bottom = (centerY + radius).toInt().coerceAtMost(height - 1)
        if (right < left || bottom < top) return 0f
        var ink = 0
        var total = 0
        for (y in top..bottom) for (x in left..right) {
            if (mask[y][x]) ink++
            total++
        }
        return ink.toFloat() / total.coerceAtLeast(1)
    }

    private fun largestConnectedComponent(
        cells: Set<Pair<Int, Int>>
    ): Set<Pair<Int, Int>> {
        if (cells.isEmpty()) return emptySet()
        val remaining = cells.toMutableSet()
        var largest = emptySet<Pair<Int, Int>>()
        while (remaining.isNotEmpty()) {
            val stack = ArrayDeque<Pair<Int, Int>>()
            val component = mutableSetOf<Pair<Int, Int>>()
            stack.addLast(remaining.first())
            remaining.remove(stack.last())
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                component.add(cell)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val neighbour = (cell.first + dx) to (cell.second + dy)
                    if (remaining.remove(neighbour)) stack.addLast(neighbour)
                }
            }
            if (component.size > largest.size) largest = component
        }
        return largest
    }
}
