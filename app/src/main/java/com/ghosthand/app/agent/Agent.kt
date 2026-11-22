package com.ghosthand.app.agent

import android.graphics.Bitmap
import com.ghosthand.app.solver.BlockSolver
import com.ghosthand.app.vision.BoardReader
import com.ghosthand.app.vision.CapturedFrame
import com.ghosthand.app.vision.FramePoint
import com.ghosthand.app.vision.GameGeometry
import com.ghosthand.app.vision.Ocr
import com.ghosthand.app.vision.ScreenClassifier
import com.ghosthand.app.vision.ScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

class Agent(
    private val ocr: Ocr,
    private val classifier: ScreenClassifier,
    private val ad: AdSentinel,
    private val motor: Motor,
    private val onStatus: (String) -> Unit
) {
    private var geoCache: GameGeometry.Geo? = null
    private val reader = BoardReader()
    private var stopOnFailedPlacement = false

    suspend fun loop(frame: () -> CapturedFrame?) {
        onStatus("Waiting for screen…")
        stopOnFailedPlacement = false
        var hasReceivedFrame = false

        while (coroutineContext.isActive) {
            if (stopOnFailedPlacement) {
                delay(400)
                continue
            }

            val capture = frame()
            if (capture == null) {
                if (!hasReceivedFrame) onStatus("Waiting for screen…")
                delay(300)
                continue
            }

            val bmp = capture.bitmap
            if (!hasReceivedFrame) {
                hasReceivedFrame = true
                onStatus("Screen received ${bmp.width}x${bmp.height}")
                delay(250)
            }

            if (!motor.isFrameAligned(capture.transform, bmp.width, bmp.height)) {
                pauseUnsafe("Capture/touch transform mismatch — stopped safely")
                continue
            }

            when (val state = classifier.classify(bmp)) {
                is ScreenState.Ad -> {
                    onStatus("Popup/ad detected")
                    ad.runAd(bmp, state)
                }
                is ScreenState.Blank -> {
                    onStatus("Screen blank/loading")
                    delay(600)
                }
                is ScreenState.Menu -> {
                    val playButton = state.buttons.firstOrNull {
                        it.first in listOf(
                            "play", "continue", "resume", "retry", "ok",
                            "start", "again", "next", "yes"
                        )
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
                    if (!stepGameplay(capture, frame)) delay(700)
                }
            }
            delay(220)
        }
    }

    private suspend fun stepGameplay(
        capture: CapturedFrame,
        frame: () -> CapturedFrame?
    ): Boolean {
        val bmp = capture.bitmap
        var geo = geoCache

        if (geo == null) {
            onStatus("Grid: scanning")
            geo = GameGeometry.calibrate(bmp)
            if (geo == null || !GameGeometry.isValid(geo, bmp.width, bmp.height)) {
                geoCache = null
                onStatus("Grid: invalid 8x8 geometry")
                return false
            }
            geoCache = geo
            onStatus("Grid: ${geo.cols}x${geo.rows} validated")
            delay(250)
        } else if (!verify(geo, bmp)) {
            onStatus("Grid changed or geometry failed — recalibrating")
            geoCache = null
            return false
        }

        val reading = reader.read(bmp, geo) ?: run {
            onStatus("Tray: no pieces — ${reader.lastTrayDiagnostic}")
            geoCache = null
            return false
        }

        onStatus("Tray ${reading.pieces.size}: " +
            reading.pieces.joinToString(", ") { "${it.w}x${it.h}" })

        val solver = BlockSolver(geo.cols, geo.rows)
        val moves = solver.solve(reading.grid, reading.pieces)
        if (moves == null) {
            onStatus("No legal move: ${solver.lastDiagnostic}")
            delay(1200)
            return true
        }

        val move = moves.first()
        if (move.pieceIdx !in reading.pieces.indices) {
            pauseUnsafe("Move rejected: invalid tray index")
            return true
        }
        val piece = reading.pieces[move.pieceIdx]

        // Revalidate solver output immediately before dispatch, independently
        // of the solver's earlier search and score calculations.
        if (move.piece != piece ||
            !solver.isLegalPlacement(reading.grid, piece, move.x, move.y) ||
            piece.cells.isEmpty() ||
            piece.cells.distinct().size != piece.cells.size ||
            (piece.anchorCellX to piece.anchorCellY) !in piece.cells
        ) {
            pauseUnsafe("Move rejected: malformed piece or illegal destination")
            return true
        }

        val anchorX = move.x + piece.anchorCellX
        val anchorY = move.y + piece.anchorCellY
        val drop = GameGeometry.cellCenter(geo, anchorX, anchorY)
        if (drop == null) {
            pauseUnsafe("Move rejected: anchor is outside playable 8x8 board")
            return true
        }

        // A guessed tray-slot fallback is deliberately not allowed. The pickup
        // point must come from a detected occupied tile in the current frame.
        val grabX = piece.grabX
        val grabY = piece.grabY
        if (!grabX.isFinite() || !grabY.isFinite() ||
            grabX < 0f || grabY < 0f ||
            grabX >= bmp.width.toFloat() || grabY >= bmp.height.toFloat()
        ) {
            pauseUnsafe("Move rejected: no verified pickup tile")
            return true
        }

        val expectedGrid = solver.resultingGrid(reading.grid, piece, move.x, move.y)
        if (expectedGrid == null) {
            pauseUnsafe("Move rejected: expected board state is invalid")
            return true
        }

        val start = FramePoint(grabX, grabY)
        val end = FramePoint(drop.first, drop.second)
        val screenStart = capture.transform.frameToScreen(start)
        val screenEnd = capture.transform.frameToScreen(end)
        if (screenStart == null || screenEnd == null) {
            pauseUnsafe("Move rejected: capture-to-touch mapping rejected endpoint")
            return true
        }

        onStatus(
            "Plan p${move.pieceIdx + 1} -> c${anchorX + 1} r${anchorY + 1}; " +
                "frame ${grabX.toInt()},${grabY.toInt()} -> ${drop.first.toInt()},${drop.second.toInt()}; " +
                "touch ${screenStart.x.toInt()},${screenStart.y.toInt()} -> " +
                "${screenEnd.x.toInt()},${screenEnd.y.toInt()}"
        )

        val dispatched = motor.drag(start, end, capture.transform, ms = 1000)
        if (!dispatched) {
            pauseUnsafe("Gesture rejected/cancelled — placement not assumed")
            return true
        }

        motor.idle(1100)

        // Require a new MediaProjection frame, not the last pre-gesture image.
        val afterCapture = awaitFreshFrame(frame, capture.capturedAtMillis)
        if (afterCapture == null) {
            pauseUnsafe("No fresh screenshot after gesture")
            return true
        }
        val afterBmp = afterCapture.bitmap
        if (!motor.isFrameAligned(afterCapture.transform, afterBmp.width, afterBmp.height)) {
            pauseUnsafe("Display changed during placement — stopped")
            return true
        }

        val afterGeo = GameGeometry.calibrate(afterBmp)
        if (afterGeo == null || !GameGeometry.isValid(afterGeo, afterBmp.width, afterBmp.height)) {
            pauseUnsafe("Post-move board geometry could not be verified")
            return true
        }
        val afterReading = reader.read(afterBmp, afterGeo)
        if (afterReading == null) {
            pauseUnsafe("Post-move board/tray could not be read")
            return true
        }

        if (!gridsEqual(expectedGrid, afterReading.grid)) {
            pauseUnsafe("Placement mismatch: actual board differs from expected result")
            return true
        }
        if (!trayConsumptionVerified(reading, afterReading, move.pieceIdx)) {
            pauseUnsafe("Board matches, but tray consumption was not verified")
            return true
        }

        geoCache = afterGeo
        onStatus("Placement verified: board and tray match expected move")
        return true
    }

    private suspend fun awaitFreshFrame(
        frame: () -> CapturedFrame?,
        previousCaptureAt: Long
    ): CapturedFrame? = withTimeoutOrNull(3500) {
        while (coroutineContext.isActive) {
            val latest = frame()
            if (latest != null && latest.capturedAtMillis > previousCaptureAt) {
                return@withTimeoutOrNull latest
            }
            delay(100)
        }
        null
    }

    private fun trayConsumptionVerified(
        before: BoardReader.Reading,
        after: BoardReader.Reading,
        selectedIndex: Int
    ): Boolean {
        val selected = before.pieces.getOrNull(selectedIndex) ?: return false
        fun signature(piece: BoardReader.Piece): String =
            "${piece.w}x${piece.h}:" +
                piece.cells.sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
                    .joinToString(";") { "${it.first},${it.second}" }

        if (before.pieces.size > 1) {
            val expected = before.pieces.toMutableList().also { it.removeAt(selectedIndex) }
                .map(::signature).sorted()
            return after.pieces.size == before.pieces.size - 1 &&
                after.pieces.map(::signature).sorted() == expected
        }

        // The last piece may cause the game to populate a fresh three-piece
        // tray. Require a changed tray before allowing another move.
        return after.pieces.size == 3 &&
            after.pieces.map(::signature).sorted() != before.pieces.map(::signature).sorted() &&
            signature(selected) in before.pieces.map(::signature)
    }

    private fun pauseUnsafe(message: String) {
        stopOnFailedPlacement = true
        onStatus(message)
    }

    private fun gridsEqual(a: Array<BooleanArray>, b: Array<BooleanArray>): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { x ->
            a[x].size == b[x].size && a[x].indices.all { y -> a[x][y] == b[x][y] }
        }
    }

    private fun verify(geo: GameGeometry.Geo, bmp: Bitmap): Boolean {
        val detected = GameGeometry.calibrate(bmp) ?: return false
        if (!GameGeometry.isValid(detected, bmp.width, bmp.height)) return false
        if (geo.cols != detected.cols || geo.rows != detected.rows) return false
        val tolerance = maxOf(3, (geo.cell * 0.08f).toInt())
        return kotlin.math.abs(geo.ox - detected.ox) <= tolerance &&
            kotlin.math.abs(geo.oy - detected.oy) <= tolerance &&
            kotlin.math.abs(geo.cell - detected.cell) <= maxOf(2, tolerance / 2)
    }
}
