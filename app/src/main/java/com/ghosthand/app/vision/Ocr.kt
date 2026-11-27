package com.ghosthand.app.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrWord(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

data class OcrResult(
    val words: List<OcrWord>,
    val lines: List<OcrWord>
)

class Ocr {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bmp: Bitmap): OcrResult =
        suspendCancellableCoroutine { cont ->
            val img = InputImage.fromBitmap(bmp, 0)
            client.process(img)
                .addOnSuccessListener { result ->
                    val width = bmp.width.toFloat()
                    val height = bmp.height.toFloat()
                    val recognizedLines = result.textBlocks.flatMap { it.lines }

                    val words = recognizedLines.flatMap { line ->
                        line.elements
                            .filter { element ->
                                element.confidence == null || element.confidence >= 0.4f
                            }
                            .map { element ->
                                val bounds: Rect = element.boundingBox ?: Rect()
                                OcrWord(
                                    element.text,
                                    bounds.exactCenterX() / width,
                                    bounds.exactCenterY() / height,
                                    bounds.width() / width,
                                    bounds.height() / height
                                )
                            }
                    }
                    // Keep full line strings as well as tokens. Objective
                    // counters often appear as "2/5" beside an icon or label;
                    // token-only OCR can split that information apart.
                    val lines = recognizedLines.map { line ->
                        val bounds: Rect = line.boundingBox ?: Rect()
                        OcrWord(
                            line.text,
                            bounds.exactCenterX() / width,
                            bounds.exactCenterY() / height,
                            bounds.width() / width,
                            bounds.height() / height
                        )
                    }
                    if (cont.isActive) cont.resume(OcrResult(words, lines))
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(OcrResult(emptyList(), emptyList()))
                }
        }

    suspend fun words(bmp: Bitmap): List<OcrWord> = recognize(bmp).words
}
