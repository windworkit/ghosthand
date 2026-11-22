package com.ghosthand.app.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

class ScreenCaster(private val ctx: Context) {

    companion object {
        private const val TAG = "GhostHandCapture"
        // Keep screen analysis lightweight on phones with high-resolution foldable displays.
        private const val MAX_CAPTURE_DIMENSION = 720
        private const val MIN_FRAME_INTERVAL_MS = 400L
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var lastFrameAt = 0L

    @Synchronized
    fun start(resultCode: Int, data: Intent, onFrame: (CapturedFrame) -> Unit) {
        stop()

        val manager = ctx.getSystemService(MediaProjectionManager::class.java)
        val proj = manager.getMediaProjection(resultCode, data) ?: return
        projection = proj

        // The VirtualDisplay uses the full logical display pixel bounds that
        // AccessibilityService gestures use. WindowMetrics bounds are window
        // geometry and are not a reliable touch-coordinate origin.
        val activeDisplay = ActiveDisplayResolver.resolve(ctx)
            ?: throw IllegalStateException("Unable to resolve full active-display bounds")
        val width = activeDisplay.width
        val height = activeDisplay.height
        val densityDpi = activeDisplay.densityDpi
        // Downscale only the analysis bitmap. Preserve source dimensions in the
        // transform so the exact x/y scale can be reconstructed after rounding.
        val frameScale = minOf(1f, MAX_CAPTURE_DIMENSION.toFloat() / maxOf(width, height))
        val frameW = (width * frameScale).toInt().coerceAtLeast(1)
        val frameH = (height * frameScale).toInt().coerceAtLeast(1)

        val thread = HandlerThread("GhostHandScreenCapture").apply { start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        try {
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = imageReader

            // Android requires the callback to be registered before createVirtualDisplay().
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "Screen capture was stopped by Android or the user")
                    stopIfCurrent(proj)
                }
            }
            projectionCallback = callback
            proj.registerCallback(callback, handler)

            imageReader.setOnImageAvailableListener({ source ->
                val image = try {
                    source.acquireLatestImage()
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to acquire screen frame", e)
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrameAt < MIN_FRAME_INTERVAL_MS) {
                        return@setOnImageAvailableListener
                    }
                    lastFrameAt = now

                    // Do not guess through display rotation/resizing: the
                    // current capture must still match the snapshot we calibrated.
                    if (image.width != width || image.height != height) {
                        Log.w(TAG, "Capture is ${image.width}x${image.height}; expected ${width}x${height}")
                        return@setOnImageAvailableListener
                    }

                    val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val pixelStride = plane.pixelStride.coerceAtLeast(1)
                    val rowPaddingBytes = (plane.rowStride - pixelStride * image.width).coerceAtLeast(0)
                    val rowPaddingPixels = rowPaddingBytes / pixelStride
                    val paddedWidth = image.width + rowPaddingPixels

                    val padded = Bitmap.createBitmap(
                        paddedWidth, image.height, Bitmap.Config.ARGB_8888
                    )
                    val buffer = plane.buffer
                    buffer.rewind()
                    padded.copyPixelsFromBuffer(buffer)

                    val fullFrame = if (paddedWidth > width) {
                        Bitmap.createBitmap(padded, 0, 0, width, height).also {
                            padded.recycle()
                        }
                    } else {
                        padded
                    }
                    // Keep the virtual display and touch coordinate space full
                    // resolution; reduce only the bitmap handed to vision.
                    val frame = if (frameW != width || frameH != height) {
                        Bitmap.createScaledBitmap(fullFrame, frameW, frameH, true).also {
                            fullFrame.recycle()
                        }
                    } else fullFrame

                    val transform = ScreenCoordinateTransform.fullDisplay(
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        display = activeDisplay
                    )
                    try {
                        onFrame(CapturedFrame(frame, transform, now))
                    } catch (e: Exception) {
                        if (!frame.isRecycled) frame.recycle()
                        throw e
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process screen frame", e)
                } finally {
                    image.close()
                }
            }, handler)

            display = proj.createVirtualDisplay(
                "ghosthand",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )
            Log.i(TAG, "Screen capture started at ${width}x${height}, throttled")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start screen capture", e)
            stop()
            throw e
        }
    }

    private fun stopIfCurrent(expected: MediaProjection) {
        synchronized(this) {
            if (projection !== expected) return
            stopLocked()
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        val oldReader = reader
        val oldDisplay = display
        val oldProjection = projection
        val oldCallback = projectionCallback
        val oldThread = captureThread

        reader = null
        display = null
        projection = null
        projectionCallback = null
        captureThread = null
        captureHandler = null
        lastFrameAt = 0L

        runCatching { oldReader?.setOnImageAvailableListener(null, null) }
        runCatching { oldDisplay?.release() }
        runCatching { oldReader?.close() }

        if (oldProjection != null) {
            if (oldCallback != null) {
                runCatching { oldProjection.unregisterCallback(oldCallback) }
            }
            runCatching { oldProjection.stop() }
        }
        runCatching { oldThread?.quitSafely() }
    }
}
