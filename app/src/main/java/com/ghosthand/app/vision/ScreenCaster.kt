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
import android.os.Looper

class ScreenCaster(private val ctx: Context) {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    fun start(resultCode: Int, data: Intent, onFrame: (Bitmap) -> Unit) {
        stop()
        val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
        val proj = mgr.getMediaProjection(resultCode, data) ?: return
        projection = proj

        val m = ctx.resources.displayMetrics
        val w = (m.widthPixels shr 1).coerceAtLeast(320)
        val h = (m.heightPixels shr 1).coerceAtLeast(240)
        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = rd

        display = proj.createVirtualDisplay(
            "ghosthand", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rd.surface, null, Handler(Looper.getMainLooper())
        )

        rd.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val buf = plane.buffer
                val rowPad = (rowStride - pixelStride * w) / pixelStride
                val bmp = Bitmap.createBitmap(w + rowPad, h, Bitmap.Config.ARGB_8888)
                buf.rewind()
                bmp.copyPixelsFromBuffer(buf)
                val out = if (rowPad > 0)
                    Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
                onFrame(out)
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, Handler(Looper.getMainLooper()))

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null; reader = null; projection = null
    }
}
