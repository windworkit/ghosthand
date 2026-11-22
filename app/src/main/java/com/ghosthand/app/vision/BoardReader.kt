package com.ghosthand.app.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

class BoardReader {

    data class Piece(
        val name: String,
        val cells: List<Pair<Int, Int>>,
        val w: Int,
        val h: Int,
        // Exact center of a visible tray tile used as the drag pickup point.
        val grabX: Float = Float.NaN,
        val grabY: Float = Float.NaN,
        // That pickup tile's coordinate inside the piece's normalized shape.
        val anchorCellX: Int = 0,
        val anchorCellY: Int = 0
    )

    data class Reading(
        val grid: Array<BooleanArray>,
        val pieces: List<Piece>,
        val geo: GameGeometry.Geo,
        val bmpW: Int,
        val bmpH: Int
    )

    private data class Blob(
        val centerX: Float,
        val centerY: Float,
        val width: Int,
        val height: Int,
        val area: Int
    )

    private val library: List<Piece> = listOf(
        "X", "XX", "XXX", "XXXX", "XXXXX",
        "XX/XX", "XXX/XXX/XXX", "XXX/XXX",
        "X./XX", "XX/X.", "XX/.X", ".X/XX",
        "X../X../XXX", "XXX/X../X..", "XXX/..X/..X", "..X/..X/XXX",
        ".X/.X/XX", "XX/X./X.", "X./X./XXX", "XXX/.X/.X",
        "XXX/.X/.X", ".X./XXX/.X.", ".X./XXX", "XXX/.X.",
        ".XX/XX.", "XX./.XX", ".X./XXX/.X."
    ).mapIndexed { i, s -> parseShape("p$i", s) }

    private fun parseShape(name: String, s: String): Piece {
        val rows = s.split("/")
        val cells = mutableListOf<Pair<Int, Int>>()
        rows.forEachIndexed { y, r ->
            r.forEachIndexed { x, c -> if (c == 'X') cells.add(x to y) }
        }
        return Piece(name, cells, rows[0].length, rows.size)
    }

    fun read(bmp: Bitmap, geo: GameGeometry.Geo): Reading? {
        val cellLevels = Array(geo.cols) { FloatArray(geo.rows) }

        for (x in 0 until geo.cols) for (y in 0 until geo.rows) {
            // Sample a small 3x3 patch in the tile face, not its border. Empty
            // Woodoku cells are dark navy; colored blocks and flower tiles are
            // substantially brighter. Saturation alone cannot distinguish them.
            val samples = IntArray(9)
            var index = 0
            for (fy in 0..2) for (fx in 0..2) {
                val px = (geo.ox + (x + 0.34f + fx * 0.16f) * geo.cell)
                    .toInt().coerceIn(0, bmp.width - 1)
                val py = (geo.oy + (y + 0.34f + fy * 0.16f) * geo.cell)
                    .toInt().coerceIn(0, bmp.height - 1)
                samples[index++] = luminance(bmp.getPixel(px, py))
            }
            samples.sort()
            cellLevels[x][y] = samples[samples.size / 2].toFloat()
        }

        val darkestCell = cellLevels.minOf { col -> col.minOrNull() ?: 255f }
        val occupiedThreshold = maxOf(76f, darkestCell + 28f)
        val grid = Array(geo.cols) { x ->
            BooleanArray(geo.rows) { y -> cellLevels[x][y] > occupiedThreshold }
        }

        val tray = readTray(bmp, geo) ?: return null
        return Reading(grid, tray, geo, bmp.width, bmp.height)
    }

    /**
     * Tray tiles are visually separated by narrow dark seams, so connected
     * component detection returns one blob per tile rather than one blob per
     * piece. Segment bright tile faces, then regroup their centers into the
     * three tray slots and reconstruct each piece's local cell coordinates.
     */
    var lastTrayDiagnostic: String = "not scanned"
        private set

    private fun readTray(bmp: Bitmap, geo: GameGeometry.Geo): List<Piece>? {
        val bands = readTrayByBands(bmp, geo)
        if (bands != null) {
            lastTrayDiagnostic = "bands: " + bands.joinToString { "${it.w}x${it.h}/${it.cells.size}" }
            return bands
        }

        val components = readTrayByComponents(bmp, geo)
        if (components != null) {
            lastTrayDiagnostic = "components: " + components.joinToString { "${it.w}x${it.h}/${it.cells.size}" }
            return components
        }

        lastTrayDiagnostic = "no tray tiles in scan region"
        return null
    }

    /**
     * Reads the three puzzle pieces from horizontal/vertical color bands.
     *
     * On a reduced MediaProjection frame, neighboring tile faces can touch and
     * connected-component analysis merges them into one large blob. Projection
     * bands preserve the repeated tile spacing, so long runs are split back
     * into individual cells before reconstructing each piece's shape.
     */
    /**
     * Estimate the tray's plain background from its perimeter. Woodoku's
     * backdrop is saturated blue, so a saturation-only mask mistakes the
     * entire background for colored puzzle tiles. Sampling the clear border
     * lets us demand a strong luminance contrast over the current game theme.
     */
    /**
     * Estimate the tray backdrop as an RGB color, not a single luminance.
     * Woodoku's blue backdrop can be brighter than its dark-red tray tiles,
     * so a fixed brightness threshold makes those perfectly valid pieces
     * disappear. Per-channel medians preserve the theme color for comparison.
     */
    private fun estimateTrayBackground(
        bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int
    ): Int {
        val l = left.coerceIn(0, bmp.width - 1)
        val r = (right - 1).coerceIn(l + 1, bmp.width - 1)
        val t = top.coerceIn(0, bmp.height - 1)
        val b = (bottom - 1).coerceIn(t + 1, bmp.height - 1)
        val red = mutableListOf<Int>()
        val green = mutableListOf<Int>()
        val blue = mutableListOf<Int>()
        val stepX = ((r - l) / 24).coerceAtLeast(1)
        val stepY = ((b - t) / 20).coerceAtLeast(1)

        fun addSample(x: Int, y: Int) {
            val p = bmp.getPixel(x.coerceIn(l, r), y.coerceIn(t, b))
            red.add(Color.red(p))
            green.add(Color.green(p))
            blue.add(Color.blue(p))
        }

        for (x in l..r step stepX) {
            addSample(x, t + 2)
            addSample(x, b - 2)
        }
        for (y in t..b step stepY) {
            addSample(l + 2, y)
            addSample(r - 2, y)
        }
        if (red.isEmpty()) return Color.rgb(67, 71, 195)

        fun median(values: List<Int>): Int {
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
        return Color.rgb(median(red), median(green), median(blue))
    }

    /**
     * Identify colorful tile faces by distance from the sampled game backdrop.
     * This detects dark red tiles as well as blue/pink/orange tiles and avoids
     * depending on absolute brightness, which changes across Woodoku themes.
     */
    private fun isTrayTilePixel(pixel: Int, background: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val br = Color.red(background)
        val bg = Color.green(background)
        val bb = Color.blue(background)
        val distance = kotlin.math.abs(r - br) +
            kotlin.math.abs(g - bg) +
            kotlin.math.abs(b - bb)
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        return distance >= 42 && chroma >= 22
    }

    private fun readTrayByBands(bmp: Bitmap, geo: GameGeometry.Geo): List<Piece>? {
        val boardBottom = geo.oy + geo.rows * geo.cell
        val y0 = (boardBottom + geo.cell * 0.45f).toInt().coerceAtLeast(0)
        val y1 = minOf(
            (boardBottom + geo.cell * 3.1f).toInt(),
            (bmp.height * 0.88f).toInt(),
            bmp.height
        )
        if (y1 - y0 < geo.cell * 0.7f) return null

        val boardLeft = geo.ox.toFloat()
        val boardWidth = geo.cols * geo.cell.toFloat()
        val pitch = geo.cell * 0.39f
        if (pitch < 4f || boardWidth <= 0f) return null

        val pieces = mutableListOf<Piece>()

        for (slot in 0..2) {
            val slotLeft = (boardLeft + slot * boardWidth / 3f)
                .toInt().coerceIn(0, bmp.width - 1)
            val slotRight = (boardLeft + (slot + 1) * boardWidth / 3f)
                .toInt().coerceIn(slotLeft + 1, bmp.width)
            val regionW = slotRight - slotLeft
            val regionH = y1 - y0
            if (regionW < pitch * 1.5f) return null

            val backgroundColor = estimateTrayBackground(
                bmp, slotLeft, y0, slotRight, y1
            )

            val mask = Array(regionH) { BooleanArray(regionW) }
            val columnInk = IntArray(regionW)
            val rowInk = IntArray(regionH)

            for (y in 0 until regionH) for (x in 0 until regionW) {
                val pixel = bmp.getPixel(slotLeft + x, y0 + y)
                // RGB distance is essential here: the red tray tiles are
                // darker than the blue background and fail a luma threshold.
                val coloredTile = isTrayTilePixel(pixel, backgroundColor)
                if (coloredTile) {
                    mask[y][x] = true
                    columnInk[x]++
                    rowInk[y]++
                }
            }

            val xBands = projectionBands(columnInk, pitch)
            val yBands = projectionBands(rowInk, pitch)

            // Woodoku Blast removes tray pieces as they are played. Empty slots
            // are normal and must not invalidate the entire tray scan. Earlier
            // logic required all three slots to contain tiles, so after the
            // user's first move it incorrectly reported "no tray pieces".
            if (xBands.isEmpty() && yBands.isEmpty()) continue
            if (xBands.isEmpty() || yBands.isEmpty() ||
                xBands.size > 5 || yBands.size > 5
            ) continue

            val detectedCells = mutableSetOf<Pair<Int, Int>>()
            for (cy in yBands.indices) for (cx in xBands.indices) {
                val density = patchDensity(mask, xBands[cx], yBands[cy], pitch * 0.28f)
                if (density >= 0.26f) detectedCells.add(cx to cy)
            }

            val connected = largestConnectedComponent(detectedCells)
            // Ignore an empty/noisy slot and continue scanning the other two.
            if (connected.isEmpty()) continue

            val minX = connected.minOf { it.first }
            val maxX = connected.maxOf { it.first }
            val minY = connected.minOf { it.second }
            val maxY = connected.maxOf { it.second }
            val width = maxX - minX + 1
            val height = maxY - minY + 1
            if (width !in 1..5 || height !in 1..5 || connected.size > 25) {
                continue
            }

            val normalized = connected
                .map { (x, y) -> (x - minX) to (y - minY) }
                .toSet()
            val known = library.firstOrNull { candidate ->
                candidate.cells.size == normalized.size &&
                    candidate.cells.all { it in normalized }
            }

            // Use an actual detected tile as the pickup point. The anchor is
            // also retained in piece-local coordinates for accurate placement.
            val anchor = connected.minWithOrNull(
                compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first }
            ) ?: return null
            val grabX = slotLeft + xBands[anchor.first]
            val grabY = y0 + yBands[anchor.second]
            val anchorX = anchor.first - minX
            val anchorY = anchor.second - minY

            pieces.add(
                if (known != null) {
                    Piece(
                        known.name, known.cells, known.w, known.h,
                        grabX, grabY, anchorX, anchorY
                    )
                } else {
                    Piece(
                        "slot$slot", normalized.toList(), width, height,
                        grabX, grabY, anchorX, anchorY
                    )
                }
            )
        }

        // One or more tray slots may be empty after a piece was used.
        // Accept any detected pieces, but reject a fully empty/noisy scan.
        return pieces.takeIf { it.isNotEmpty() && it.size <= 3 }
    }

    private fun projectionBands(profile: IntArray, pitch: Float): List<Float> {
        if (profile.isEmpty()) return emptyList()
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
                // Woodoku pieces can touch diagonally (staircases and
                // offset pairs). Treat all 8 surrounding cells as connected.
                val neighbours = buildList {
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx != 0 || dy != 0) {
                            add((cell.first + dx) to (cell.second + dy))
                        }
                    }
                }
                for (neighbour in neighbours) {
                    if (remaining.remove(neighbour)) stack.addLast(neighbour)
                }
            }

            if (component.size > largest.size) largest = component
        }
        return largest
    }

    /** Original connected-component reader retained as a fallback. */
    private fun readTrayByComponents(bmp: Bitmap, geo: GameGeometry.Geo): List<Piece>? {
        val boardBottom = geo.oy + geo.rows * geo.cell
        val y0 = (boardBottom + geo.cell * 0.25f).toInt().coerceAtLeast(0)
        val y1 = (y0 + geo.cell * 2.8f).toInt().coerceAtMost(bmp.height)
        val x0 = (geo.ox - geo.cell * 0.4f).toInt().coerceAtLeast(0)
        val x1 = (geo.ox + geo.cols * geo.cell + geo.cell * 0.4f)
            .toInt().coerceAtMost(bmp.width)
        if (y1 - y0 < geo.cell / 2 || x1 - x0 < 30) return null

        val regionW = x1 - x0
        val regionH = y1 - y0
        val backgroundColor = estimateTrayBackground(bmp, x0, y0, x1, y1)
        val mask = Array(regionH) { BooleanArray(regionW) }

        for (y in 0 until regionH) for (x in 0 until regionW) {
            val pixel = bmp.getPixel(x0 + x, y0 + y)
            mask[y][x] = isTrayTilePixel(pixel, backgroundColor)
        }

        val minArea = maxOf(12, (geo.cell * geo.cell * 0.006f).toInt())
        val minDimension = (geo.cell * 0.08f).toInt().coerceAtLeast(3)
        val seen = Array(regionH) { BooleanArray(regionW) }
        val blobs = mutableListOf<Blob>()

        for (startY in 0 until regionH) for (startX in 0 until regionW) {
            if (!mask[startY][startX] || seen[startY][startX]) continue

            val stack = ArrayDeque<Int>()
            stack.addLast(startY * regionW + startX)
            seen[startY][startX] = true

            var minX = startX
            var maxX = startX
            var minY = startY
            var maxY = startY
            var sumX = 0L
            var sumY = 0L
            var area = 0

            while (stack.isNotEmpty()) {
                val packed = stack.removeLast()
                val cx = packed % regionW
                val cy = packed / regionW
                area++
                sumX += cx
                sumY += cy
                minX = minOf(minX, cx)
                maxX = maxOf(maxX, cx)
                minY = minOf(minY, cy)
                maxY = maxOf(maxY, cy)

                val left = cx - 1
                val right = cx + 1
                val up = cy - 1
                val down = cy + 1
                if (left >= 0 && mask[cy][left] && !seen[cy][left]) {
                    seen[cy][left] = true
                    stack.addLast(cy * regionW + left)
                }
                if (right < regionW && mask[cy][right] && !seen[cy][right]) {
                    seen[cy][right] = true
                    stack.addLast(cy * regionW + right)
                }
                if (up >= 0 && mask[up][cx] && !seen[up][cx]) {
                    seen[up][cx] = true
                    stack.addLast(up * regionW + cx)
                }
                if (down < regionH && mask[down][cx] && !seen[down][cx]) {
                    seen[down][cx] = true
                    stack.addLast(down * regionW + cx)
                }
            }

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (area >= minArea && bw >= minDimension && bh >= minDimension) {
                blobs.add(
                    Blob(
                        x0 + sumX.toFloat() / area,
                        y0 + sumY.toFloat() / area,
                        bw,
                        bh,
                        area
                    )
                )
            }
        }

        if (blobs.isEmpty()) return null

        val trayLeft = x0.toFloat()
        val slotWidth = (x1 - x0) / 3f
        if (slotWidth <= 0f) return null

        val pieces = mutableListOf<Piece>()
        for (slot in 0..2) {
            val slotBlobs = blobs.filter { blob ->
                val slotIndex = ((blob.centerX - trayLeft) / slotWidth).toInt()
                slotIndex.coerceIn(0, 2) == slot
            }
            // A used tray slot is empty by design; don't reject the remaining
            // playable pieces simply because an earlier slot has disappeared.
            if (slotBlobs.isEmpty()) continue

            val typicalW = median(slotBlobs.map { it.width.toFloat() })
            val typicalH = median(slotBlobs.map { it.height.toFloat() })
            val xCenters = clusterCenters(
                slotBlobs.map { it.centerX },
                (typicalW * 0.55f).coerceAtLeast(3f)
            )
            val yCenters = clusterCenters(
                slotBlobs.map { it.centerY },
                (typicalH * 0.55f).coerceAtLeast(3f)
            )
            if (xCenters.isEmpty() || yCenters.isEmpty()) continue

            data class DetectedCell(val x: Int, val y: Int, val blob: Blob)
            val detected = slotBlobs.map { blob ->
                val cx = xCenters.indices.minByOrNull { abs(xCenters[it] - blob.centerX) } ?: 0
                val cy = yCenters.indices.minByOrNull { abs(yCenters[it] - blob.centerY) } ?: 0
                DetectedCell(cx, cy, blob)
            }
            val rawCells = detected.map { it.x to it.y }.toSet()

            val minX = rawCells.minOf { it.first }
            val maxX = rawCells.maxOf { it.first }
            val minY = rawCells.minOf { it.second }
            val maxY = rawCells.maxOf { it.second }
            val width = maxX - minX + 1
            val height = maxY - minY + 1
            if (width > 5 || height > 5 || rawCells.size > 25) continue

            val normalized = rawCells
                .map { (cx, cy) -> (cx - minX) to (cy - minY) }
                .toSet()
            val known = library.firstOrNull { candidate ->
                candidate.cells.size == normalized.size &&
                    candidate.cells.all { it in normalized }
            }

            // Grab a real tile, never the geometric center of a piece's
            // bounding box (which can be an empty square for L-shaped pieces).
            val anchor = detected.minWithOrNull(
                compareBy<DetectedCell> { it.y }.thenBy { it.x }
            ) ?: return null
            val anchorCellX = anchor.x - minX
            val anchorCellY = anchor.y - minY
            val grabX = xCenters[anchor.x]
            val grabY = yCenters[anchor.y]

            pieces.add(
                if (known != null) {
                    Piece(
                        known.name, known.cells, known.w, known.h,
                        grabX, grabY, anchorCellX, anchorCellY
                    )
                } else {
                    Piece(
                        "slot$slot", normalized.toList(), width, height,
                        grabX, grabY, anchorCellX, anchorCellY
                    )
                }
            )
        }

        return pieces.takeIf { it.isNotEmpty() && it.size <= 3 }
    }

    private fun clusterCenters(values: List<Float>, tolerance: Float): List<Float> {
        if (values.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Float>>()
        for (value in values.sorted()) {
            val current = groups.lastOrNull()
            if (current == null || abs(value - current.average().toFloat()) > tolerance) {
                groups.add(mutableListOf(value))
            } else {
                current.add(value)
            }
        }
        return groups.map { it.average().toFloat() }
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
}
