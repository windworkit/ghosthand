
package com.ghosthand.app.agent

import android.graphics.Bitmap
import android.os.SystemClock
import com.ghosthand.app.solver.BlockSolver
import com.ghosthand.app.solver.ObjectiveGoal
import com.ghosthand.app.vision.BoardReader
import com.ghosthand.app.vision.GameGeometry
import com.ghosthand.app.vision.BoardObjectType
import com.ghosthand.app.vision.FloatingPieceLocator
import com.ghosthand.app.vision.ObjectiveReader
import com.ghosthand.app.vision.ObjectiveProgressTracker
import com.ghosthand.app.vision.ObjectiveBoardClassificationGate
import com.ghosthand.app.vision.Ocr
import com.ghosthand.app.vision.ScreenClassifier
import com.ghosthand.app.vision.ScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.hypot

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
    private val objectiveProgressTracker = ObjectiveProgressTracker()
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

    // The finger-to-piece lift is mostly the same for every piece once aiming
    // uses the piece centre, so the last confirmed offset is the starting
    // guess for shapes not seen yet (instead of the fixed prior).
    private var sharedAdj: Adj? = null

    // How far a held piece moves per pixel of finger movement, per axis,
    // measured while aiming. Null until seen; some games move it faster
    // than the finger so a far drag doesn't need a long swipe.
    private var gainX: Float? = null
    private var gainY: Float? = null

    private fun remember(key: PlacementKey, adj: Adj) {
        // A finger more than a few cells from the piece is a mis-read, not
        // a real lift; storing it would throw every later drag off screen.
        if (!adj.x.isFinite() || !adj.y.isFinite() ||
            abs(adj.x) > MAX_LEARNED_ADJ_CELLS || abs(adj.y) > MAX_LEARNED_ADJ_CELLS
        ) return
        learned[key] = adj
        sharedAdj = adj
    }

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
                onStatus("Grid: no valid 8x8/9x9 board")
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
        // A goal counter is treated as progress only after the same value is
        // observed on two separate captured frames; a one-frame OCR glitch
        // must not influence diagnostics or any future move-learning logic.
        val objectiveProgressUpdate = objectiveProgressTracker.observe(objectiveSnapshot)
        val progressSummary = objectiveProgressUpdate.increases.joinToString(", ") {
            "${it.type.name.lowercase()} +${it.amount} (${it.current}/${it.target})"
        }
        val regressionSummary = objectiveProgressUpdate.decreases.joinToString(", ") {
            "${it.type.name.lowercase()} ${it.previous}→${it.current} (not scored)"
        }
        val objectiveProgressSuffix = when {
            progressSummary.isNotEmpty() -> "; progress=$progressSummary"
            regressionSummary.isNotEmpty() -> "; counter reset=$regressionSummary"
            else -> ""
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
        val objectiveGoals = objectiveSnapshot.counters.mapNotNull { counter ->
            val current = counter.current ?: return@mapNotNull null
            val target = counter.target ?: return@mapNotNull null
            if (target <= 0 || current !in 0..target) return@mapNotNull null
            ObjectiveGoal(counter.type, current, target)
        }.groupBy { it.type }.map { (type, counters) ->
            // Two UI counters for the same object type share one board-cell
            // category, so combine their remaining totals instead of sending
            // duplicate goal types into the solver.
            ObjectiveGoal(
                type,
                counters.sumOf { it.current },
                counters.sumOf { it.target }
            )
        }
        // The objective simulator requires a supported semantic label AND
        // at least 92% object-classification confidence for every occupied cell.
        // Occupancy alone never qualifies as an ordinary block.
        val objectiveClassification = ObjectiveBoardClassificationGate.classify(
            reading.boardCells
        )
        val objectiveBoard = objectiveClassification.board
        val objectiveBoardReady = objectiveClassification.isReady
        onStatus(
            "Tray ${reading.pieces.size}: $traySummary; occupied=$unclassifiedOccupied; " +
                "background=${(reading.backgroundSupport * 100).toInt()}%; goals=$objectiveSummary$objectiveProgressSuffix; " +
                "objective scoring=${if (objectiveBoardReady) "armed" else objectiveClassification.rejectionReason}"
        )
        val solver = BlockSolver(geo.cols, geo.rows)
        val moves = solver.solve(reading.grid, reading.pieces, objectiveBoard, objectiveGoals)

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

        // The game enlarges a lifted piece from tray size to board size and
        // positions it by its middle relative to the finger. Aiming the
        // grabbed tile instead made drops land left/high on a Fold 7 when the
        // grab tile sat at a piece's end. So aim the piece's bounding-box
        // centre at the destination's centre; the grab tile only matters for
        // picking the piece up.
        val cell = geo.cell.toFloat()
        val anchorX = piece.anchorCellX.coerceIn(0, piece.w - 1)
        val anchorY = piece.anchorCellY.coerceIn(0, piece.h - 1)
        if (piece.cells.none { it.first == anchorX && it.second == anchorY }) {
            stopOnFailedPlacement = true
            onStatus("Invalid pickup anchor — paused")
            return true
        }
        val targetAnchorX = geo.ox + (m.x + piece.w / 2f) * cell
        val targetAnchorY = geo.oy + (m.y + piece.h / 2f) * cell
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
            // Hold the piece over the board, look where the game actually
            // draws it, slide the finger to put the piece centre on the target
            // centre, then let go. Falls back to a plain drag if holding
            // isn't available.
            var finalAdj = adj
            val dispatched: Boolean
            // Some games move a held piece farther than the finger moves, so a
            // straight drag to the aim point flings a far-travelling piece off
            // the board where it can't be seen or corrected. Stop short
            // (scaled by the learned finger-to-piece speed), look where the
            // piece is, then finish while measuring how far it really moves.
            val firstX = grabX + (dropX - grabX) / (gainX ?: CAUTIOUS_GAIN)
            val firstY = grabY + (dropY - grabY) / (gainY ?: CAUTIOUS_GAIN)
            val firstNx = firstX / reading.bmpW
            val firstNy = firstY / reading.bmpH
            if (motor.dragAndHold(startNx, startNy, firstNx, firstNy, ms = 900)) {
                var fingerX = firstX
                var fingerY = firstY
                var since = SystemClock.elapsedRealtime() + FRAME_LAG_MS
                var curGainX = gainX ?: 1f
                var curGainY = gainY ?: 1f
                var measuredX = false
                var measuredY = false
                var prevFingerX = Float.NaN
                var prevFingerY = Float.NaN
                var prevPieceX = Float.NaN
                var prevPieceY = Float.NaN
                var lastErr = Float.MAX_VALUE
                var seen = false
                var onTarget = false
                var untrusted = false
                for (fix in 0 until MAX_AIM_FIXES) {
                    // The game eases a held piece toward the finger, so a
                    // frame grabbed mid-glide shows it still trailing behind.
                    // Measuring that made the next fix fling it far past the
                    // target. Only measure once it has stopped moving.
                    val settled = locateSettled(bmp, since, geo, piece, grabX, grabY)
                    if (settled === Settle.Moving) {
                        untrusted = true
                        break
                    }
                    val located = (settled as? Settle.At)?.where ?: break
                    seen = true
                    // How far did the piece move for the last finger move?
                    if (prevFingerX.isFinite()) {
                        val dfx = fingerX - prevFingerX
                        val dfy = fingerY - prevFingerY
                        if (abs(dfx) >= cell * GAIN_MIN_MOVE_CELLS) {
                            val g = (located.centerX - prevPieceX) / dfx
                            if (g in MIN_GAIN..MAX_GAIN) { curGainX = g; measuredX = true }
                        }
                        if (abs(dfy) >= cell * GAIN_MIN_MOVE_CELLS) {
                            val g = (located.centerY - prevPieceY) / dfy
                            if (g in MIN_GAIN..MAX_GAIN) { curGainY = g; measuredY = true }
                        }
                    }
                    val ex = targetAnchorX - located.centerX
                    val ey = targetAnchorY - located.centerY
                    if (abs(ex) <= cell * AIM_TOLERANCE && abs(ey) <= cell * AIM_TOLERANCE) {
                        onTarget = true
                        break
                    }
                    // A real lock follows the finger, so every fix must shrink
                    // the error. If it doesn't, the locator latched onto
                    // something static (a same-coloured tile, an apple).
                    val err = hypot(ex, ey)
                    if (err > lastErr * AIM_MIN_IMPROVEMENT) {
                        untrusted = true
                        break
                    }
                    lastErr = err
                    // Go most of the way, never more than a few cells per
                    // fix: an overshoot can't be seen once it's off the board.
                    val stepCap = cell * MAX_FIX_STEP_CELLS
                    val newX = fingerX + (AIM_DAMPING * ex / curGainX).coerceIn(-stepCap, stepCap)
                    val newY = fingerY + (AIM_DAMPING * ey / curGainY).coerceIn(-stepCap, stepCap)
                    val nx = newX / reading.bmpW
                    val ny = newY / reading.bmpH
                    if (!nx.isFinite() || !ny.isFinite() || nx !in 0.01f..0.99f || ny !in 0.01f..0.99f) {
                        untrusted = true
                        break
                    }
                    onStatus("Aim fix ${"%.1f".format(ex / cell)},${"%.1f".format(ey / cell)} cells")
                    if (!motor.moveHeld(nx, ny, 250)) break
                    prevFingerX = fingerX; prevFingerY = fingerY
                    prevPieceX = located.centerX; prevPieceY = located.centerY
                    fingerX = newX
                    fingerY = newY
                    since = SystemClock.elapsedRealtime() + FRAME_LAG_MS
                }
                if (measuredX) gainX = curGainX
                if (measuredY) gainY = curGainY

                if (untrusted) {
                    // Can't trust where the piece is: put it back in the tray
                    // rather than dropping it in a random cell.
                    motor.moveHeld(startNx, startNy, 350)
                    motor.releaseHeld()
                    stopOnFailedPlacement = true
                    onStatus("Aim check unreliable; piece returned to tray — paused")
                    return true
                }
                if (!seen && (fingerX != dropX || fingerY != dropY)) {
                    // Never saw the piece; finish the drag to the plain aim point.
                    onStatus("Aim check: piece not found; dropping as aimed")
                    if (motor.moveHeld(endNx, endNy, 250)) {
                        fingerX = dropX
                        fingerY = dropY
                        delay(FRAME_LAG_MS)
                    }
                } else if (seen && !onTarget) {
                    // Last fix was sent but not checked; it's the best estimate.
                    onStatus("Aim: dropping after ${MAX_AIM_FIXES} fixes")
                }
                finalAdj = Adj((fingerX - targetAnchorX) / cell, (fingerY - targetAnchorY) / cell)
                motor.releaseHeld()
                // The touch has ended either way; let the board re-read decide.
                dispatched = true
            } else {
                dispatched = motor.drag(startNx, startNy, endNx, endNy, ms = 1000)
            }
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
                val beforeSnapshot = BoardReader.GridSnapshot(
                    reading.grid,
                    reading.colorSignatures,
                    reading.backgroundSupport,
                    reading.traySignature
                )
                if (reader.sameVisualState(beforeSnapshot, after)) {
                    continue // no board or tray change: rejected; try the next probe
                }
                // A special objective or tray item can change without a
                // corresponding occupancy transition. Do not call that a
                // rejected drag or repeat it; pause for a fresh level read.
                stopOnFailedPlacement = true
                onStatus("Board/tray changed without occupancy change — objective transition; paused")
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
                remember(key, finalAdj)
                onStatus("Placed on target")
            } else {
                // It landed (dx, dy) cells away from the intent; move the aim the other way.
                remember(key, Adj(finalAdj.x - landing.first, finalAdj.y - landing.second))
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
        learned[key] ?: sharedAdj ?: Adj(PRIOR_X, PRIOR_Y)

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

    /** A frame captured after [sinceMs] (so it shows the held piece), or null. */
    private suspend fun heldFrame(before: Bitmap, sinceMs: Long): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + HELD_FRAME_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val f = AgentBus.frame.value
            if (f != null && f !== before && !f.isRecycled && AgentBus.lastFrameAtMs >= sinceMs) return f
            delay(40)
        }
        return null
    }

    private sealed class Settle {
        data class At(val where: FloatingPieceLocator.Located) : Settle()
        object Moving : Settle()
        object NotFound : Settle()
    }

    /**
     * Locate the held piece once two consecutive frames agree on where it is.
     * [Settle.Moving] if it was seen but never held still before the timeout.
     */
    private suspend fun locateSettled(
        before: Bitmap, sinceMs: Long, geo: GameGeometry.Geo,
        piece: BoardReader.Piece, grabX: Float, grabY: Float
    ): Settle {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        var since = sinceMs
        var last: FloatingPieceLocator.Located? = null
        var everSeen = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val held = heldFrame(before, since) ?: break
            since = AgentBus.lastFrameAtMs + 1
            val now = locateHeldPiece(before, held, geo, piece, grabX, grabY)
            if (now == null) {
                last = null
                continue
            }
            everSeen = true
            val prev = last
            if (prev != null &&
                abs(now.centerX - prev.centerX) <= geo.cell * SETTLE_TOLERANCE &&
                abs(now.centerY - prev.centerY) <= geo.cell * SETTLE_TOLERANCE
            ) return Settle.At(now)
            last = now
        }
        return if (everSeen) Settle.Moving else Settle.NotFound
    }

    private fun locateHeldPiece(
        before: Bitmap, held: Bitmap, geo: GameGeometry.Geo,
        piece: BoardReader.Piece, grabX: Float, grabY: Float
    ): FloatingPieceLocator.Located? {
        if (before.width != held.width || before.height != held.height) return null
        val w = before.width
        val cell = geo.cell.toFloat()
        val top = (geo.oy - cell * 1.5f).toInt().coerceIn(0, before.height - 1)
        val bottom = (geo.oy + geo.rows * cell + cell * 3f).toInt().coerceIn(top + 1, before.height)
        val rows = bottom - top
        return runCatching {
            val a = IntArray(w * rows)
            val b = IntArray(w * rows)
            before.getPixels(a, 0, w, 0, top, w, rows)
            held.getPixels(b, 0, w, 0, top, w, rows)
            // The grabbed tray tile still shows the piece colour in `before`.
            val gy = grabY.toInt() - top
            if (gy !in 0 until rows) return null
            val color = FloatingPieceLocator.sampleColor(
                a, w, rows, grabX.toInt(), gy, (cell * 0.08f).toInt().coerceAtLeast(1)
            )
            FloatingPieceLocator.locate(
                a, b, w, rows, 0, 0, w, rows, color, cell, piece.w, piece.h
            )?.let { it.copy(centerY = it.centerY + top) }
        }.getOrNull()
    }

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
        if (geo.cols !in 8..9 || geo.rows != geo.cols || geo.cell < 6) return false
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

        // Held-drag aim correction.
        const val MAX_AIM_FIXES = 4
        const val AIM_TOLERANCE = 0.15f // cells
        // Each held-aim fix must cut the remaining error to at most this share.
        const val AIM_MIN_IMPROVEMENT = 0.7f
        // Before the piece speed is known, the first stop assumes the piece
        // may move this many times as far as the finger, so it stops short.
        const val CAUTIOUS_GAIN = 1.6f
        // Each fix covers this share of the measured error (no overshoot).
        const val AIM_DAMPING = 0.85f
        // Largest finger move a single fix may make.
        const val MAX_FIX_STEP_CELLS = 2.5f
        // Held piece counts as still when two frames agree within this.
        const val SETTLE_TOLERANCE = 0.15f // cells
        const val SETTLE_TIMEOUT_MS = 1_500L
        // A finger move must be at least this long to measure piece speed.
        const val GAIN_MIN_MOVE_CELLS = 0.4f
        const val MIN_GAIN = 0.5f
        const val MAX_GAIN = 3f
        // Learned finger-to-piece offsets beyond this are treated as mis-reads.
        const val MAX_LEARNED_ADJ_CELLS = 5f
        const val FRAME_LAG_MS = 150L
        const val HELD_FRAME_TIMEOUT_MS = 1_200L

        // Starting guess: finger below the piece's centre (the game lifts the
        // dragged piece above the finger). 2 cells landed "a bit high" on a
        // Fold 7, so start lower. Replaced by what is observed after the first
        // successful placement.
        const val PRIOR_X = 0f
        const val PRIOR_Y = 2.5f

        // Nudges tried, in order, when the game rejects a drop. Half-cell
        // steps around the guess, nearest first, before larger ones. (The old
        // list moved the finger up first, which made a high drop worse.)
        val PROBES = listOf(
            0f to 0.5f, 0.5f to 0f, 0f to -0.5f, -0.5f to 0f,
            0.5f to 0.5f, 0f to 1f, 0f to -1f
        )
    }
}
