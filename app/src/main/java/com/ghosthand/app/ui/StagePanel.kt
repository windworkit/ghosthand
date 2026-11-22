package com.ghosthand.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghosthand.app.Script
import com.ghosthand.app.Step
import kotlin.math.hypot

private data class APoint(val index: Int, val x: Float, val y: Float, val kind: Int)

@Composable
fun StagePanel(
    script: Script, selected: Int?,
    onSelect: (Int) -> Unit, modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textPaint = remember(density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF171309.toInt()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = with(density) { 11.sp.toPx() }
        }
    }
    val points = remember(script) {
        buildList {
            script.steps.forEachIndexed { i, st ->
                when (st) {
                    is Step.Tap       -> add(APoint(i, st.x, st.y, 0))
                    is Step.LongPress -> add(APoint(i, st.x, st.y, 1))
                    is Step.Swipe     -> { add(APoint(i, st.x1, st.y1, 3)); add(APoint(i, st.x2, st.y2, 2)) }
                    else -> {}
                }
            }
        }
    }

    val cfg = LocalConfiguration.current
    val aspect = cfg.screenWidthDp.toFloat() / cfg.screenHeightDp.toFloat().coerceAtLeast(1f)

    Column(modifier) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("STAGE", color = Muted, fontSize = 10.sp, fontFamily = Mono, letterSpacing = 2.sp)
            Spacer(Modifier.width(10.dp))
            Text("coords are % — safe across fold states", color = AmberDeep, fontSize = 10.sp, fontFamily = Mono)
        }
        Surface(
            shape = RoundedCornerShape(14.dp), color = CardC, border = BorderStroke(1.dp, LineC),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Box {
                Canvas(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight()
                        .fillMaxWidth(fractionOf(aspect))
                        .pointerInput(points) {
                            detectTapGestures { off ->
                                val w = size.width.toFloat(); val h = size.height.toFloat()
                                points
                                    .minByOrNull { hypot((it.x * w - off.x).toDouble(), (it.y * h - off.y).toDouble()) }
                                    ?.takeIf { hypot((it.x * w - off.x).toDouble(), (it.y * h - off.y).toDouble()) < 120 }
                                    ?.let { onSelect(it.index) }
                            }
                        }
                ) {
                    for (gx in 1..7) drawLine(LineC.copy(alpha = 0.35f),
                        Offset(size.width * gx / 8f, 0f), Offset(size.width * gx / 8f, size.height), 1f)
                    for (gy in 1..15) drawLine(LineC.copy(alpha = 0.35f),
                        Offset(0f, size.height * gy / 16f), Offset(size.width, size.height * gy / 16f), 1f)

                    var prev: Offset? = null
                    points.forEach { p ->
                        val o = Offset(p.x * size.width, p.y * size.height)
                        if (p.kind == 3) prev = o
                        else {
                            prev?.let { drawLine(AmberDeep.copy(alpha = 0.25f), it, o, 3f) }
                            prev = o
                        }
                    }
                    script.steps.forEach { st ->
                        if (st is Step.Swipe) {
                            val a = Offset(st.x1 * size.width, st.y1 * size.height)
                            val b = Offset(st.x2 * size.width, st.y2 * size.height)
                            drawLine(Amber.copy(alpha = 0.8f), a, b, 4f)
                            val ang = Math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                            val head = 26f
                            for (s in listOf(Math.PI * 0.85, Math.PI * 1.15)) {
                                drawLine(Amber.copy(alpha = 0.8f), b,
                                    Offset((b.x + (head * Math.cos(ang + s)).toFloat()).coerceIn(0f, size.width),
                                           (b.y + (head * Math.sin(ang + s)).toFloat()).coerceIn(0f, size.height)), 4f)
                            }
                        }
                    }

                    points.forEach { p ->
                        val o = Offset(p.x * size.width, p.y * size.height)
                        val r = if (p.kind == 1) 14f else 11f
                        when (p.kind) {
                            0 -> { drawCircle(Amber, r, o); drawCircle(Paper, r * 0.55f, o) }
                            1 -> { drawCircle(Amber, r, o, style = Stroke(4f)); drawCircle(AmberDeep, 5f, o) }
                            3 -> drawCircle(Amber, 8f, o, style = Stroke(5f))
                            2 -> drawCircle(Amber, 7f, o)
                        }
                        if (p.index >= 0) {
                            if (p.index == selected) drawCircle(Color.White, r + 6f, o, style = Stroke(2f))
                            drawContext.canvas.nativeCanvas.drawText("${p.index + 1}", o.x, o.y + r + 14f, textPaint)
                        }
                    }
                }
            }
        }
    }
}

private fun fractionOf(aspect: Float): Float = (aspect * 2.1f).coerceIn(0.35f, 1f)
