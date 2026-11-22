package com.ghosthand.app.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrWord(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

class Ocr {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun words(bmp: Bitmap): List<OcrWord> =
        suspendCancellableCoroutine { cont ->
            val img = InputImage.fromBitmap(bmp, 0)
            client.process(img)
                .addOnSuccessListener { t ->
                    val W = bmp.width.toFloat(); val H = bmp.height.toFloat()
                    val out = t.textBlocks.flatMap { b -> b.lines }.flatMap { l -> l.elements }
                        .filter { el -> el.confidence == null || el.confidence >= 0.4f }
                        .map { el ->
                            val r: Rect = el.boundingBox ?: Rect()
                            OcrWord(
                                el.text,
                                r.exactCenterX() / W, r.exactCenterY() / H,
                                r.width() / W, r.height() / H
                            )
                        }
                    if (cont.isActive) cont.resume(out)
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
        }
}
