package com.ghosthand.app.vision

import android.graphics.Bitmap
import android.graphics.Color

class BoardReader {

    data class Piece(val name: String, val cells: List<Pair<Int, Int>>,
                     val w: Int, val h: Int)

    data class Reading(
        val grid: Array<BooleanArray>,
        val pieces: List<Piece>,
        val geo: GameGeometry.Geo,
        val bmpW: Int, val bmpH: Int
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
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> if (c == 'X') cells.add(x to y) } }
        return Piece(name, cells, rows[0].length, rows.size)
    }

    fun read(bmp: Bitmap, geo: GameGeometry.Geo): Reading? {
        val grid = Array(geo.cols) { BooleanArray(geo.rows) }
        for (x in 0 until geo.cols) for (y in 0 until geo.rows) {
            val px = bmp.getPixel(
                (geo.ox + x * geo.cell + geo.cell / 2).coerceIn(0, bmp.width - 1),
                (geo.oy + y * geo.cell + geo.cell / 2).coerceIn(0, bmp.height - 1)
            )
            val hsv = FloatArray(3); Color.colorToHSV(px, hsv)
            grid[x][y] = hsv[1] > 0.28f && hsv[2] > 0.2f
        }

        val tray = readTray(bmp, geo) ?: return null
        return Reading(grid, tray, geo, bmp.width, bmp.height)
    }

    private fun readTray(bmp: Bitmap, geo: GameGeometry.Geo): List<Piece>? {
        val y0 = geo.oy + geo.rows * geo.cell + (geo.cell / 2)
        val y1 = (y0 + geo.cell * 2.6f).toInt().coerceAtMost(bmp.height - 1)
        if (y1 - y0 < geo.cell) return null
        val x0 = (geo.ox - geo.cell * 0.4f).toInt().coerceAtLeast(0)
        val x1 = (geo.ox + geo.cols * geo.cell + geo.cell * 0.4f).toInt().coerceAtMost(bmp.width - 1)

        val mask = Array(y1 - y0) { BooleanArray(x1 - x0) }
        for (y in 0 until y1 - y0) for (x in 0 until x1 - x0) {
            val px = bmp.getPixel(x0 + x, y0 + y)
            val hsv = FloatArray(3); Color.colorToHSV(px, hsv)
            mask[y][x] = hsv[1] > 0.28f && hsv[2] > 0.2f
        }
        val seen = Array(mask.size) { BooleanArray(mask[0].size) }
        val blobs = mutableListOf<List<Pair<Int, Int>>>()
        for (y in mask.indices) for (x in mask[0].indices) {
            if (!mask[y][x] || seen[y][x]) continue
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(x to y); seen[y][x] = true
            val blob = mutableListOf<Pair<Int, Int>>()
            while (stack.isNotEmpty()) {
                val (cx, cy) = stack.removeLast(); blob.add(cx to cy)
                for (d in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = cx + d.first; val ny = cy + d.second
                    if (nx in mask[0].indices && ny in mask.indices && mask[ny][nx] && !seen[ny][nx]) {
                        seen[ny][nx] = true; stack.addLast(nx to ny)
                    }
                }
            }
            if (blob.size > geo.cell / 2) blobs.add(blob)
        }
        blobs.sortByDescending { it.size }

        val pieces = blobs.take(3).mapNotNull { blob -> blobToPiece(blob) }
        return if (pieces.isNotEmpty()) pieces.take(3) else null
    }

    private fun blobToPiece(blob: List<Pair<Int, Int>>): Piece? {
        if (blob.isEmpty()) return null
        val xs = blob.map { it.first }; val ys = blob.map { it.second }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val bw = maxX - minX + 1; val bh = maxY - minY + 1

        val colInk = IntArray(bw); val rowInk = IntArray(bh)
        blob.forEach { (x, y) -> colInk[x - minX]++; rowInk[y - minY]++ }

        fun groups(profile: IntArray): List<IntRange> {
            val thr = profile.max() * 0.25f
            val out = mutableListOf<IntRange>()
            var s = -1
            profile.forEachIndexed { i, v ->
                val ink = v > thr
                if (ink && s < 0) s = i
                if (!ink && s >= 0) { out.add(s until i); s = -1 }
            }
            if (s >= 0) out.add(s until profile.size)
            return out.filter { it.first <= it.last && !it.isEmpty() }
        }
        val gx = groups(colInk); val gy = groups(rowInk)
        if (gx.isEmpty() || gy.isEmpty()) return null

        val cells = sortedSetOf(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        blob.forEach { (x, y) ->
            val gi = gx.indexOfFirst { x - minX in it }; val gj = gy.indexOfFirst { y - minY in it }
            if (gi >= 0 && gj >= 0) cells.add(gi to gj)
        }
        val grid = cells.toList()
        val w = gx.size; val h = gy.size

        val nlx = grid.minOf { it.first }; val nly = grid.minOf { it.second }
        val norm = grid.map { (it.first - nlx) to (it.second - nly) }.toSet()

        return library.firstOrNull { p ->
            p.cells.size == norm.size && p.cells.all { it in norm }
        }?.let { Piece(it.name, grid, w, h) } ?: Piece("custom", grid, w, h)
    }
}
