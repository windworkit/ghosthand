package com.ghosthand.app.solver

import com.ghosthand.app.vision.BoardReader
import com.ghosthand.app.vision.ShapeTopology

class BlockSolver(private val cols: Int, private val rows: Int) {

    // Exposed to Smart Play so a genuine no-fit result can be distinguished
    // from a bad board read or an incorrectly detected tray shape.
    var lastDiagnostic: String = "not run"
        private set

    data class Move(val pieceIdx: Int, val piece: BoardReader.Piece, val x: Int, val y: Int)

    fun solve(grid: Array<BooleanArray>, pieces: List<BoardReader.Piece>): List<Move>? {
        if (grid.size != cols || grid.any { it.size != rows }) {
            lastDiagnostic = "invalid board dimensions; expected ${cols}x${rows}"
            return null
        }
        if (pieces.isEmpty()) {
            lastDiagnostic = "tray returned 0 pieces"
            return null
        }
        val invalidPiece = pieces.indexOfFirst { !isValidPiece(it) }
        if (invalidPiece >= 0) {
            val piece = pieces[invalidPiece]
            lastDiagnostic = "piece ${invalidPiece + 1} is malformed: ${piece.w}x${piece.h}, ${piece.cells.size} cells"
            return null
        }
        val emptyCells = grid.sumOf { col -> col.count { !it } }
        val fitCounts = pieces.map { piece -> countPlacements(grid, piece) }
        lastDiagnostic = "empty=$emptyCells/${cols * rows}; " +
            pieces.mapIndexed { i, p ->
                "${p.name} ${p.w}x${p.h}/${p.cells.size} blocks=${fitCounts[i]} fits"
            }.joinToString("; ")

        var best: List<Move>? = null; var bestScore = Long.MIN_VALUE
        for (perm in permutations(pieces.indices.toList())) {
            var work = grid; var score = 0L
            val moves = mutableListOf<Move>()
            var dead = false
            for (i in perm) {
                val p = pieces[i]
                var bi: Pair<Int, Int>? = null; var bs = Long.MIN_VALUE
                for (x in 0..cols - p.w) for (y in 0..rows - p.h) {
                    if (!fits(work, p, x, y)) continue
                    val s = scorePlacement(work, p, x, y, pieces.filterIndexed { j, _ -> j != i })
                    if (s >= bs) { bs = s; bi = x to y }
                }
                if (bi == null) { dead = true; break }
                work = apply(work, p, bi.first, bi.second)
                moves.add(Move(i, p, bi.first, bi.second))
                score += bs
            }
            if (!dead && score > bestScore) { bestScore = score; best = moves }
        }
        if (best != null) return best

        // When all three pieces cannot be played in one uninterrupted
        // sequence, still make progress with the best individual move.
        // Do not assume pieces[0] fits: the first tray slot may be a large
        // square while a line or L-piece elsewhere in the tray can still fit.
        var fallback: Move? = null
        var fallbackScore = Long.MIN_VALUE
        for (pieceIdx in pieces.indices) {
            val piece = pieces[pieceIdx]
            if (piece.w > cols || piece.h > rows) continue
            val remaining = pieces.filterIndexed { index, _ -> index != pieceIdx }
            for (x in 0..(cols - piece.w)) for (y in 0..(rows - piece.h)) {
                if (!fits(grid, piece, x, y)) continue
                val score = scorePlacement(grid, piece, x, y, remaining)
                if (score > fallbackScore) {
                    fallbackScore = score
                    fallback = Move(pieceIdx, piece, x, y)
                }
            }
        }
        return fallback?.let { listOf(it) }
    }

    private fun isValidPiece(piece: BoardReader.Piece): Boolean {
        return ShapeTopology.isValidShape(piece.cells, piece.w, piece.h) &&
            piece.w <= cols && piece.h <= rows
    }

    private fun countPlacements(g: Array<BooleanArray>, p: BoardReader.Piece): Int {
        if (p.w !in 1..5 || p.h !in 1..5 || p.w > cols || p.h > rows ||
            p.cells.isEmpty() || p.cells.any { (x, y) -> x !in 0 until p.w || y !in 0 until p.h }
        ) return 0
        var count = 0
        for (x in 0..(cols - p.w)) for (y in 0..(rows - p.h)) {
            if (fits(g, p, x, y)) count++
        }
        return count
    }

    private fun fits(g: Array<BooleanArray>, p: BoardReader.Piece, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || p.w !in 1..5 || p.h !in 1..5 ||
            x + p.w > cols || y + p.h > rows || p.cells.isEmpty()
        ) return false
        if (p.cells.any { (cx, cy) -> cx !in 0 until p.w || cy !in 0 until p.h }) {
            return false
        }
        return p.cells.all { (cx, cy) -> !g[x + cx][y + cy] }
    }

    private fun apply(g: Array<BooleanArray>, p: BoardReader.Piece, x: Int, y: Int): Array<BooleanArray> {
        val n = Array(cols) { g[it].clone() }
        p.cells.forEach { (cx, cy) -> n[x + cx][y + cy] = true }
        // Determine all completed lines from the same post-placement board.
        // Clearing rows first and then checking columns can miss a column that
        // was full before the row clear removed their shared intersection.
        val fullRows = BooleanArray(rows) { yy ->
            (0 until cols).all { xx -> n[xx][yy] }
        }
        val fullCols = BooleanArray(cols) { xx ->
            (0 until rows).all { yy -> n[xx][yy] }
        }
        for (xx in 0 until cols) for (yy in 0 until rows) {
            if (fullRows[yy] || fullCols[xx]) n[xx][yy] = false
        }
        return n
    }
fun canFit(g: Array<BooleanArray>, p: BoardReader.Piece, x: Int, y: Int): Boolean = fits(g, p, x, y)

fun place(g: Array<BooleanArray>, p: BoardReader.Piece, x: Int, y: Int): Array<BooleanArray> = apply(g, p, x, y)
    private fun scorePlacement(
        g: Array<BooleanArray>, p: BoardReader.Piece, x: Int, y: Int,
        others: List<BoardReader.Piece>
    ): Long {
        val after = apply(g, p, x, y)
        val before = count(g); val afterN = count(after)
        val cleared = before + p.cells.size - afterN
        var s = cleared.toLong() * cleared * 300L
        s -= holes(after) * 150L
        s -= columnHeights(after).sum() * 5L
        s -= bumpiness(after) * 20L
        var placeable = 0
        outer@ for (o in others) {
            for (ox in 0..cols - o.w) for (oy in 0..rows - o.h)
                if (fits(after, o, ox, oy)) { placeable++; continue@outer }
        }
        s += placeable * 120L
        return s
    }

    private fun count(g: Array<BooleanArray>) = g.sumOf { col -> col.count { it } }

    private fun holes(g: Array<BooleanArray>): Int {
        var h = 0
        for (x in 0 until cols) {
            var seen = false
            for (y in 0 until rows) {
                if (g[x][y]) seen = true
                else if (seen) h++
            }
        }
        return h
    }

    private fun columnHeights(g: Array<BooleanArray>): IntArray =
        IntArray(cols) { x ->
            (0 until rows).firstOrNull { y -> g[x][y] }?.let { rows - it } ?: 0
        }

    private fun bumpiness(g: Array<BooleanArray>): Int {
        val h = columnHeights(g); var b = 0
        for (i in 1 until h.size) b += kotlin.math.abs(h[i] - h[i - 1])
        return b
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        val out = mutableListOf<List<T>>()
        for (i in items.indices) {
            val rest = items.filterIndexed { j, _ -> j != i }
            for (p in permutations(rest)) out.add(listOf(items[i]) + p)
        }
        return out
    }
}
