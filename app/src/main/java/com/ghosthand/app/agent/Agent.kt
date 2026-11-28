
package com.ghosthand.app.agent

import android.graphics.Bitmap
import com.ghosthand.app.solver.BlockSolver
import com.ghosthand.app.vision.BoardReader
import com.ghosthand.app.vision.GameGeometry
import com.ghosthand.app.vision.BoardObjectType
import com.ghosthand.app.vision.ObjectiveReader
import com.ghosthand.app.vision.Ocr
import com.ghosthand.app.vision.ScreenClassifier
import com.ghosthand.app.vision.ScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

class Agent(
    private val ocr: Ocr,
    private val classifier: ScreenClassifier,
    private val ad: AdSentinel,
    private val motor: Motor,
    private val isPaused: () -> Boolean = { false },
    private val onStatus: (String) -> Unit
) {
    private var geoCache: GameGeometry.Geo? = null
    private val reader = BoardReader()
    private val objectiveReader = ObjectiveReader()
    // Stop blind retries when the game did not accept a placement.
    private var stopOnFailedPlacement = false
    private var wasPaused = false
    private var verifyFailures = 0

    /**
     * Where the finger must be, relative to the CENTER of the piece's target
     * bounding box, for the game to put the piece on the intended cells. Units
     * are board cells (+x right, +y down).
     *
     * The game enlarges a piece when it is picked up and floats it away from
     * the finger, so "grabbed tile lands under the finger" is not true. The
     * real offset is unknown in advance, so it is learned from where pieces
     * actually land (see [findLanding]) and remembered per piece size.
     */
    private data class Adj(val x: Float, val y: Float)
    private data class PlacementKey(val shape: String, val anchorX: Int, val anchorY: Int)

    // Calibration belongs to an exact shape and pickup anchor. Width/height
    // alone are not enough: L, T and zig-zag pieces can share a bounding box.
    private val learned = HashMap<PlacementKey, Adj>()

    private fun placementKey(piece: BoardReader.Piece): PlacementKey {
        val canonicalCells = piece.cells
            .sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .joinToString(";") { "${it.first},${it.second}" }
        return PlacementKey(
            "${piece.w}x${piece.h}:$canonicalCells",
            piece.anchorCellX,
            piece.anchorCellY
        )
    }

    suspend fun loop(frame: () -> Bitmap?) {
        onStatus("Waiting for screen…")
        stopOnFailedPlacement = false
        var hasReceivedFrame = false
        var lastProcessedFrame: Bitmap? = null
        while (coroutineContext.isActive) {
            if (isPaused()) {
                wasPaused = true
                delay(200)
                continue
            }
            if (wasPaused) {
                // Resuming from the pill also clears a "paused after failed drop" stop.
                wasPaused = false
                stopOnFailedPlacement = false
            }
            if (stopOnFailedPlacement) {
                delay(400)
                continue
            }
            val bmp = frame()
            if (bmp == null) {
                if (!hasReceivedFrame) onStatus("Waiting for screen…")
                delay(300)
                continue
            }
            // ScreenCaster publishes a new Bitmap for each captured frame.
            // Do not run OCR, board vision, or solver work repeatedly on the
            // same screenshot while waiting for the next 400ms capture.
            if (bmp === lastProcessedFrame) {
                delay(80)
                continue
            }
            lastProcessedFrame = bmp

            if (!hasReceivedFrame) {
                hasReceivedFrame = true
                onStatus("Screen received ${bmp.width}x${bmp.height}")
                delay(250)
            }

            // A captured app window or a stale inner/cover-panel capture can
            // be letterboxed inside the projection surface. Never attempt a
            // gesture unless the frame aspect matches the active touch display.
            if (!motor.isFrameFresh()) {
                onStatus("Waiting for a fresh screen frame…")
                delay(250)
                continue
            }
            if (!motor.isFrameAligned(bmp.width, bmp.height)) {
                stopOnFailedPlacement = true
                onStatus("Capture/display mismatch — stopped safely")
                delay(400)
                continue
            }

            when (val s = classifier.classify(bmp)) {
                is ScreenState.Ad -> {
                    onStatus("Popup/ad detected")
                    ad.runAd(bmp, s)
                }

                is ScreenState.Blank -> { onStatus("Screen blank/loading"); delay(600) }

                is ScreenState.Menu -> {
                    val playButton = s.buttons.firstOrNull {
                        it.first in listOf("play", "continue", "resume", "retry",
                            "ok", "start", "again", "next", "yes")
                    }
                    if (playButton != null) {
                        onStatus("Menu: tapping ${playButton.first}")
                        motor.tap(playButton.second.first, playButton.second.second)
                        motor.idle(900)
                    } else {
                        onStatus("Menu found; no known button")
                        delay(900)
                    }
                }

                is ScreenState.Gameplay -> {
                    if (!stepGameplay(bmp, frame)) delay(700)
                }
            }
            delay(220)
        }
    }

    private suspend fun stepGameplay(bmp: Bitmap, frame: () -> Bitmap?): Boolean {
        var geo = geoCache
        if (geo == null) {
            onStatus("Grid: scanning")
            geo = GameGeometry.calibrate(bmp)
            if (geo == null || !isGeometryUsable(geo, bmp)) {
                geoCache = null
                onStatus("Grid: no valid 8x8 board")
                return false
            }
            geoCache = geo
            verifyFailures = 0
            onStatus("Grid: ${geo.cols}x${geo.rows} found")
            delay(300)
        } else if (!isGeometryUsable(geo, bmp)) {
            geoCache = null
            verifyFailures = 0
            onStatus("Grid: outside capture — recalibrating")
            return false
        } else if (verify(geo, bmp)) {
            verifyFailures = 0
        } else if (++verifyFailures >= 2) {
            // One bad frame (animation, particles) must not throw the grid away.
            onStatus("Grid: recalibrating")
            geoCache = null
            verifyFailures = 0
            return false
        }

        onStatus("Tray: reading pieces")
        val reading = reader.read(bmp, geo) ?: run {
            onStatus("Tray: no pieces — ${reader.lastTrayDiagnostic}")
            // Tray is empty/animating between sets; keep the grid calibration.
            return false
        }
        val traySummary = reading.pieces.joinToString(", ") { "${it.w}x${it.h}/${it.cells.size}" }
        val objectiveSnapshot = objectiveReader.read(
            classifier.lastWords, classifier.lastLines, bmp.width, bmp.height
        )
        val objectiveSummary = objectiveSnapshot.counters.joinToString(", ") {
            "${it.type.name.lowercase()} ${it.current ?: "?"}/${it.target ?: "?"}"
        }.ifEmpty {
            if (objectiveSnapshot.unclassifiedNumericTokens.isNotEmpty()) {
                "unlabeled counters: ${objectiveSnapshot.unclassifiedNumericTokens.joinToString("/")}"
            } else {
                "no readable objective text"
            }
        }
        if (reading.backgroundSupport < MIN_BACKGROUND_SUPPORT) {
            stopOnFailedPlacement = true
            onStatus(
                "Board colors uncertain (${(reading.backgroundSupport * 100).toInt()}% background samples) — paused"
            )
            return true
        }
        val unclassifiedOccupied = reading.boardCells.sumOf { column ->
            column.count { it.objectType == BoardObjectType.UNCLASSIFIED_OCCUPIED }
        }
        onStatus(
            "Tray ${reading.pieces.size}: $traySummary; occupied=$unclassifiedOccupied; " +
                "background=${(reading.backgroundSupport * 100).toInt()}%; goals=$objectiveSummary"
        )
        val solver = BlockSolver(geo.cols, geo.rows)
        val moves = solver.solve(reading.grid, reading.pieces)

        if (moves == null) {
            onStatus("No fit: ${solver.lastDiagnostic}")
            delay(1200)
            return true
        }

        // One move per fresh board read; re-solve after each placement.
        val m = moves.first()
        val piece = reading.pieces[m.pieceIdx]
        val key = placementKey(piece)
        val grabX = if (piece.grabX.isFinite()) piece.grabX else traySlot(reading, m.pieceIdx).first
        val grabY = if (piece.grabY.isFinite()) piece.grabY else traySlot(reading, m.pieceIdx).second

        // Aim the tile actually grabbed at its destination cell. The piece's
        // bounding-box center is not the pickup point for asymmetric shapes.
        val cell = geo.cell.toFloat()
        val anchorX = piece.anchorCellX.coerceIn(0, piece.w - 1)
        val anchorY = piece.anchorCellY.coerceIn(0, piece.h - 1)
        if (piece.cells.none { it.first == anchorX && it.second == anchorY }) {
            stopOnFailedPlacement = true
            onStatus("Invalid pickup anchor — paused")
            return true
        }
        val targetAnchorX = geo.ox + (m.x + anchorX + 0.5f) * cell
        val targetAnchorY = geo.oy + (m.y + anchorY + 0.5f) * cell
        val base = adjFor(key)

        // attempt 0 uses the learned offset. If the game rejects the drop (grid
        // unchanged) the same move is retried with the aim nudged, so a wrong
        // starting guess is found instead of pausing forever.
        val totalAttempts = PROBES.size + 1
        for (attempt in 0 until totalAttempts) {
            val probe = if (attempt == 0) 0f to 0f else PROBES[attempt - 1]
            val adj = Adj(base.x + probe.first, base.y + probe.second)
            val dropX = targetAnchorX + adj.x * cell
            val dropY = targetAnchorY + adj.y * cell
            val startNx = grabX / reading.bmpW
            val startNy = grabY / reading.bmpH
            val endNx = dropX / reading.bmpW
            val endNy = dropY / reading.bmpH

            // Never clamp an invalid destination to an image edge.
            if (listOf(startNx, startNy, endNx, endNy).any { !it.isFinite() || it !in 0.01f..0.99f }) {
                stopOnFailedPlacement = true
                onStatus("Drag coordinates outside capture — paused")
                return true
            }

            onStatus("Plan p${m.pieceIdx + 1} -> c${m.x + 1} r${m.y + 1} #${attempt + 1}; goals=$objectiveSummary")
            val dispatched = motor.drag(startNx, startNy, endNx, endNy, ms = 1000)
            if (!dispatched) {
                stopOnFailedPlacement = true
                onStatus("Android cancelled/rejected drag — paused")
                return true
            }
            motor.idle(700)

            val after = settledGrid(frame, geo)
            if (after == null) {
                stopOnFailedPlacement = true
                onStatus("Can't re-read board — paused")
                return true
            }
            if (gridsEqual(reading.grid, after.occupied)) {
                if (colorSignaturesEqual(reading.colorSignatures, after.colorSignatures)) {
                    continue // no observable board change: rejected; try the next probe
                }
                // A special objective can change its visual state without
                // changing cell occupancy. Do not classify that as a rejected
                // drop or repeat the same gesture; icon/state semantics are not
                // confirmed yet, so pause for a fresh level read.
                stopOnFailedPlacement = true
                onStatus("Board changed visually without occupancy change — objective transition; paused")
                return true
            }

            val landing = findLanding(
                solver, reading.grid, after.occupied, piece, m.x, m.y, geo.cols, geo.rows
            )
            if (landing == null) {
                stopOnFailedPlacement = true
                onStatus("Board changed; landing uncertain — paused")
                return true
            } else if (landing.first == 0 && landing.second == 0) {
                learned[key] = adj
                onStatus("Placed on target")
            } else {
                // It landed (dx, dy) cells away from the intent; move the aim the other way.
                learned[key] = Adj(adj.x - landing.first, adj.y - landing.second)
                onStatus("Off by ${fmt(landing.first)},${fmt(landing.second)}; adjusted")
            }
            delay(300)
            return true
        }

        stopOnFailedPlacement = true
        onStatus("Drop rejected ${totalAttempts}x — paused")
        return true
    }

    /** Use calibration only for this exact shape and pickup anchor. */
    private fun adjFor(key: PlacementKey): Adj =
        learned[key] ?: Adj(PRIOR_X, PRIOR_Y)

    /**
     * Find the (dx, dy) shift, relative to the intended top-left cell, at which
     * placing the piece on the old board (including line clears) reproduces the
     * new board. Prefers the smallest shift. Null when nothing matches.
     */
    private fun findLanding(
        solver: BlockSolver,
        before: Array<BooleanArray>,
        after: Array<BooleanArray>,
        piece: BoardReader.Piece,
        intendedX: Int,
        intendedY: Int,
        cols: Int,
        rows: Int
    ): Pair<Int, Int>? {
        var match: Pair<Int, Int>? = null
        var matchCount = 0
        for (dx in -5..5) for (dy in -5..5) {
            val x = intendedX + dx
            val y = intendedY + dy
            if (x < 0 || y < 0 || x + piece.w > cols || y + piece.h > rows) continue
            if (!solver.canFit(before, piece, x, y)) continue
            if (!gridsEqual(solver.place(before, piece, x, y), after)) continue

            matchCount++
            if (matchCount > 1) {
                // More than one offset explains the same screenshot. Do not
                // learn a calibration from an ambiguous board transition.
                return null
            }
            match = dx to dy
        }
        return match
    }

    /** Re-read the board until two consecutive reads agree (animations finished). */
    private suspend fun settledGrid(
        frame: () -> Bitmap?,
        geo: GameGeometry.Geo
    ): BoardReader.GridSnapshot? {
        var previous: BoardReader.GridSnapshot? = null
        for (i in 0 until 8) {
            delay(450)
            val bmp = frame() ?: continue
            if (!motor.isFrameFresh() || !motor.isFrameAligned(bmp.width, bmp.height)) return null
            val current = reader.readGridSnapshot(bmp, geo)
            if (current.backgroundSupport < MIN_BACKGROUND_SUPPORT) return null
            val prev = previous
            if (prev != null && boardSnapshotsEqual(prev, current)) return current
            previous = current
        }
        // An unstable board is not a trustworthy placement result.
        return null
    }

    private fun boardSnapshotsEqual(
        first: BoardReader.GridSnapshot,
        second: BoardReader.GridSnapshot
    ): Boolean = reader.sameVisualState(first, second)

    private fun fmt(v: Int): String = if (v > 0) "+$v" else v.toString()

    private fun traySlot(r: BoardReader.Reading, idx: Int): Pair<Float, Float> {
        val geo = r.geo
        val boardL = geo.ox.toFloat()
        val boardW = geo.cols * geo.cell.toFloat()
        val slotW = boardW / 3f
        val cx = boardL + slotW * (idx + 0.5f)
        val cy = geo.oy + geo.rows * geo.cell + geo.cell * 1.3f
        return cx to cy
    }

    private fun gridsEqual(a: Array<BooleanArray>, b: Array<BooleanArray>): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { x ->
            a[x].size == b[x].size && a[x].indices.all { y -> a[x][y] == b[x][y] }
        }
    }

    /**
     * Never sample a clamped/partial board. Clamping would cause several
     * logical cells to read pixels from the same screen edge and could make a
     * false empty-cell map look internally consistent.
     */
    private fun isGeometryUsable(geo: GameGeometry.Geo, bmp: Bitmap): Boolean {
        if (geo.cols != 8 || geo.rows != 8 || geo.cell < 6) return false
        if (geo.ox < 0 || geo.oy < 0) return false
        val right = geo.ox + geo.cols * geo.cell
        val bottom = geo.oy + geo.rows * geo.cell
        return right <= bmp.width && bottom <= bmp.height
    }

    /**
     * True while the board frame is still where it was calibrated. Looks for the
     * strong frame edge just below the last row, averaged across the whole board
     * width. (The old check sampled one cell, so it failed whenever the
     * bottom-left cell was empty.)
     */
    private fun verify(geo: GameGeometry.Geo, bmp: Bitmap): Boolean {
        val left = geo.ox.coerceIn(0, bmp.width - 1)
        val right = (geo.ox + geo.cols * geo.cell).coerceIn(left + 1, bmp.width)
        val yStart = (geo.oy + geo.rows * geo.cell - 2).coerceIn(2, bmp.height - 3)
        val yEnd = (yStart + geo.cell / 2 + 2).coerceAtMost(bmp.height - 3)
        var best = 0f
        for (y in yStart..yEnd) {
            var total = 0f
            var n = 0
            var x = left
            while (x < right) {
                total += abs(lum(bmp.getPixel(x, y - 2)) - lum(bmp.getPixel(x, y + 2))).toFloat()
                n++
                x += 3
            }
            best = maxOf(best, total / n.coerceAtLeast(1))
        }
        return best > 25f
    }

    private fun lum(c: Int) = (android.graphics.Color.red(c) +
            android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3

    private companion object {
        const val MIN_BACKGROUND_SUPPORT = 0.35f

        // Starting guess: finger about two cells below the piece's center
        // (the game lifts the dragged piece above the finger). Replaced by
        // what is observed after the first placement.
        const val PRIOR_X = 0f
        const val PRIOR_Y = 2f

        // Nudges tried, in order, when the game rejects a drop.
        val PROBES = listOf(
            0f to -1f, 0f to -2f, 0f to 1f, 0f to -3f, -1f to 0f, 1f to 0f
        )
    }
}
