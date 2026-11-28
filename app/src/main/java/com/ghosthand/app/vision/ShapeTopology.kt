package com.ghosthand.app.vision

/**
 * Helpers for polyomino-like tray shapes. Puzzle blocks connect edge-to-edge;
 * diagonal corner contact alone does not make one connected piece.
 */
object ShapeTopology {

    private val orthogonalOffsets = arrayOf(
        -1 to 0,
        1 to 0,
        0 to -1,
        0 to 1
    )

    fun components(cells: Set<Pair<Int, Int>>): List<Set<Pair<Int, Int>>> {
        if (cells.isEmpty()) return emptyList()
        val remaining = cells.toMutableSet()
        val result = mutableListOf<Set<Pair<Int, Int>>>()

        while (remaining.isNotEmpty()) {
            val first = remaining.first()
            remaining.remove(first)
            val queue = ArrayDeque<Pair<Int, Int>>()
            val component = linkedSetOf<Pair<Int, Int>>()
            queue.addLast(first)

            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                component.add(cell)
                for ((dx, dy) in orthogonalOffsets) {
                    val neighbour = (cell.first + dx) to (cell.second + dy)
                    if (remaining.remove(neighbour)) queue.addLast(neighbour)
                }
            }
            result.add(component)
        }
        return result
    }

    fun isConnected(cells: Collection<Pair<Int, Int>>): Boolean {
        if (cells.isEmpty() || cells.distinct().size != cells.size) return false
        return components(cells.toSet()).size == 1
    }

    fun isValidShape(
        cells: Collection<Pair<Int, Int>>,
        width: Int,
        height: Int,
        maxCells: Int = 25
    ): Boolean {
        if (width !in 1..5 || height !in 1..5) return false
        if (cells.isEmpty() || cells.size > maxCells) return false
        if (cells.distinct().size != cells.size) return false
        if (cells.any { (x, y) -> x !in 0 until width || y !in 0 until height }) return false
        return isConnected(cells)
    }
}
