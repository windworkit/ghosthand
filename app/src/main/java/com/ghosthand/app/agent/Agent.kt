package com.ghosthand.app.agent

import android.graphics.Bitmap
import com.ghosthand.app.solver.BlockSolver
import com.ghosthand.app.vision.BoardReader
import com.ghosthand.app.vision.GameGeometry
import com.ghosthand.app.vision.Ocr
import com.ghosthand.app.vision.ScreenClassifier
import com.ghosthand.app.vision.ScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    suspend fun loop(frame: () -> Bitmap?) {
        onStatus("Waiting for screen…")
        var hasReceivedFrame = false
        while (coroutineContext.isActive) {
            val bmp = frame()
            if (bmp == null) {
                if (!hasReceivedFrame) onStatus("Waiting for screen…")
                delay(300)
                continue
            }

            if (!hasReceivedFrame) {
                hasReceivedFrame = true
                onStatus("Screen received ${bmp.width}x${bmp.height}")
                delay(250)
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
            if (geo == null) {
                onStatus("Grid: no lines")
                return false
            }
            geoCache = geo
            onStatus("Grid: ${geo.cols}x${geo.rows} found")
            delay(300)
        } else if (!verify(geo, bmp)) {
            onStatus("Grid: recalibrating")
            geoCache = null
            return false
        }

        onStatus("Tray: reading pieces")
        val reading = reader.read(bmp, geo) ?: run {
            onStatus("Tray: no pieces")
            geoCache = null
            return false
        }
        onStatus("Tray: ${reading.pieces.size} pieces")
        val solver = BlockSolver(geo.cols, geo.rows)
        val moves = solver.solve(reading.grid, reading.pieces)

        if (moves == null) {
            onStatus("Solver: no valid move")
            delay(1200)
            return true
        }

        for ((index, m) in moves.withIndex()) {
            val slot = traySlot(reading, m.pieceIdx)
            val bx = geo.ox + (m.x + m.piece.w / 2f) * geo.cell
            val by = geo.oy + (m.y + m.piece.h / 2f) * geo.cell
            onStatus("Moving ${index + 1}/${moves.size}")
            motor.drag(
                slot.first / reading.bmpW, slot.second / reading.bmpH,
                bx / reading.bmpW, by / reading.bmpH,
                ms = 340
            )
            motor.idle(650)
        }
        onStatus("Moves sent; checking")
        motor.idle(500)
        return true
    }

    private fun traySlot(r: BoardReader.Reading, idx: Int): Pair<Float, Float> {
        val geo = r.geo
        val boardL = geo.ox.toFloat()
        val boardW = geo.cols * geo.cell.toFloat()
        val slotW = boardW / 3f
        val cx = boardL + slotW * (idx + 0.5f)
        val cy = geo.oy + geo.rows * geo.cell + geo.cell * 1.3f
        return cx to cy
    }

    private fun verify(geo: GameGeometry.Geo, bmp: Bitmap): Boolean {
        val x = (geo.ox + 2).coerceIn(0, bmp.width - 1)
        val y = minOf(geo.oy + geo.rows * geo.cell + 2, bmp.height - 2)
        val a = bmp.getPixel(x, y - 2); val b = bmp.getPixel(x, y + 2)
        val la = lum(a); val lb = lum(b)
        return kotlin.math.abs(la - lb) > 18
    }

    private fun lum(c: Int) = (android.graphics.Color.red(c) +
            android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3
}
