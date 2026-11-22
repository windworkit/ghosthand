package com.ghosthand.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ghosthand.app.agent.Agent
import com.ghosthand.app.agent.AdSentinel
import com.ghosthand.app.agent.AgentBus
import com.ghosthand.app.agent.Motor
import com.ghosthand.app.vision.Ocr
import com.ghosthand.app.vision.ScreenClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

class AutoPlayerService : AccessibilityService() {

    data class PickOutcome(val session: Int, val x: Float, val y: Float)

    companion object {
        var instance: AutoPlayerService? = null
            private set

        val isRunning = MutableStateFlow(false)
        val paused = MutableStateFlow(false)
        val statusText = MutableStateFlow("Idle — nothing playing")
        private val pickResult = MutableStateFlow<PickOutcome?>(null)
        private var pickSession = 0

        suspend fun awaitPick(session: Int): PickOutcome =
            pickResult.filterNotNull().first { it.session == session }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob: Job? = null
    private var activePickSession: Int? = null

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        runCatching { crossFrame?.let { wm().removeView(it) } }
        runCatching { pillFrame?.let { wm().removeView(it) } }
        crossFrame = null; pillFrame = null
        scope.cancel()
        super.onDestroy()
    }

    fun play(script: Script) {
        if (isRunning.value || script.steps.isEmpty()) return
        isRunning.value = true
        paused.value = false
        statusText.value = "Starting…"
        main.post { showPill(script.keepAwake) }
        playJob = scope.launch {
            try {
                val passes = if (script.repeats <= 0) -1 else script.repeats
                var pass = 0
                while (isActive && (passes < 0 || pass < passes)) {
                    runPass(script, pass, passes)
                    pass++
                }
                statusText.value = "Finished — $pass pass(es) complete"
            } catch (e: CancellationException) {
                statusText.value = "Stopped"
                throw e
            } finally {
                isRunning.value = false
                paused.value = false
                main.post { hidePill() }
            }
        }
    }

    fun stop() { playJob?.cancel() }

    fun togglePause() {
        paused.value = !paused.value
        statusText.value = if (paused.value) "Paused — resume from the pill" else "Running…"
        main.post { pillPauseBtn?.text = if (paused.value) "\u25B6" else "\u275A\u275A" }
    }

    private suspend fun runPass(s: Script, pass: Int, passes: Int) {
        val steps = s.steps
        val (endOf, startOf) = matchBrackets(steps)
        val loopStack = ArrayDeque<IntArray>()
        var i = 0
        while (i < steps.size && kotlin.coroutines.coroutineContext.isActive) {
            while (paused.value && kotlin.coroutines.coroutineContext.isActive) delay(200)
            val st = steps[i]
            val passTag = if (passes < 0) "P${pass + 1}" else "P${pass + 1}/$passes"
            statusText.value = "$passTag · S${i + 1} ${describe(st)}"
            main.post { pillLabel?.text = "$passTag·S${i + 1}" }
            when (st) {
                is Step.LoopStart -> {
                    val top = loopStack.lastOrNull()
                    if (top != null && top[0] == i) {
                        if (top[1] == Int.MAX_VALUE) i++
                        else if (--top[1] > 0) i++
                        else { loopStack.removeLast(); i = (endOf[i] ?: steps.lastIndex) + 1 }
                    } else {
                        loopStack.addLast(intArrayOf(i, if (st.times <= 0) Int.MAX_VALUE else st.times))
                        i++
                    }
                }
                is Step.LoopEnd -> {
                    val back = startOf[i]
                    if (back == null) i++ else i = back
                    delay(2); yield()
                }
                is Step.Tap       -> { doTap(s, st.x, st.y, 46 + rnd(38)); dwell() }
                is Step.LongPress -> { doTap(s, st.x, st.y, st.ms.coerceAtLeast(280)); dwell() }
                is Step.Swipe     -> { doSwipe(s, st); dwell() }
                is Step.Wait      -> delay((st.ms + rnd2(-s.jitterMs, s.jitterMs)).coerceAtLeast(30))
            }
            yield()
        }
    }

    private fun matchBrackets(steps: List<Step>): Pair<Map<Int, Int>, Map<Int, Int>> {
        val endOf = mutableMapOf<Int, Int>(); val startOf = mutableMapOf<Int, Int>()
        val open = ArrayDeque<Int>()
        steps.forEachIndexed { i, st ->
            when (st) {
                is Step.LoopStart -> open.addLast(i)
                is Step.LoopEnd -> if (open.isNotEmpty()) {
                    val s = open.removeLast(); endOf[s] = i; startOf[i] = s
                }
                else -> {}
            }
        }
        return endOf to startOf
    }

    private suspend fun doTap(s: Script, nx: Float, ny: Float, durationMs: Long) {
        val (w, h) = screen(); val a = amp(s)
        dispatch(Path().apply { moveTo(nx * w + off(a), ny * h + off(a)) }, durationMs)
    }

    private suspend fun doSwipe(s: Script, st: Step.Swipe) {
        val (w, h) = screen(); val a = amp(s)
        val x1 = st.x1 * w + off(a); val y1 = st.y1 * h + off(a)
        val x2 = st.x2 * w + off(a); val y2 = st.y2 * h + off(a)
        val mx = (x1 + x2) / 2f; val my = (y1 + y2) / 2f
        val dx = x2 - x1; val dy = y2 - y1
        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val bow = (Random.nextFloat() * 2 - 1) * dist * 0.07f
        val px = -dy / dist * bow; val py = dx / dist * bow
        val path = Path().apply { moveTo(x1, y1); quadTo(mx + px, my + py, x2, y2) }
        dispatch(path, st.ms)
    }

    private suspend fun dispatch(path: Path, durationMs: Long) {
        val dur = durationMs.coerceIn(40L, 60_000L)
        return suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(Unit) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(Unit) }
            }, null)
            if (!ok && cont.isActive) cont.resume(Unit)
        }
    }

    private suspend fun dwell() { delay(rnd2(40L, 130L)) }
    private fun rnd(spread: Long) = if (spread <= 0) 0L else Random.nextLong(0, spread + 1)
    private fun rnd2(from: Long, to: Long) = if (to <= from) from else Random.nextLong(from, to + 1)
    private fun amp(s: Script) = s.jitterDp * resources.displayMetrics.density
    private fun off(a: Float) = if (a <= 0f) 0f else (Random.nextFloat() * 2 - 1) * a

    private fun screen(): Pair<Int, Int> = try {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm().currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = Point()
            @Suppress("DEPRECATION") wm().defaultDisplay.getRealSize(p)
            p.x to p.y
        }
    } catch (e: Exception) {
        resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
    }

    // ---------------- smart play (agent mode) ----------------

    private val ocr by lazy { Ocr() }

    fun startSmartPlay() {
        if (isRunning.value) return
        isRunning.value = true
        paused.value = false
        statusText.value = "Starting screen reader…"
        main.post { showPill(true) }

        val motor = object : Motor {
            override suspend fun tap(x: Float, y: Float) {
                val (w, h) = screen()
                val j = 5f * resources.displayMetrics.density
                dispatch(Path().apply { moveTo(x * w + off(j), y * h + off(j)) }, 48 + rnd(40))
            }
            override suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
                val (w, h) = screen()

                // Smart Play relies on cell-level placement. Random offsets or
                // a short drag can start outside a tile and cause a tap instead.
                // Use exact normalized coordinates and a deliberate straight drag.
                val startX = x1.coerceIn(0.01f, 0.99f) * w
                val startY = y1.coerceIn(0.01f, 0.99f) * h
                val endX = x2.coerceIn(0.01f, 0.99f) * w
                val endY = y2.coerceIn(0.01f, 0.99f) * h
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }

                dispatch(path, ms.coerceIn(500, 1800))
                delay(220)
            }
            override suspend fun idle(ms: Long) = delay(ms)
        }

        playJob = scope.launch {
            try {
                Agent(ocr, ScreenClassifier(ocr), AdSentinel(ocr, motor), motor) {
                    statusText.value = it
                    main.post { pillLabel?.text = it.take(20) }
                }.loop { AgentBus.frame.value }
            } catch (e: CancellationException) {
                statusText.value = "Stopped"
                throw e
            } catch (e: Exception) {
                statusText.value = "Smart Play error: ${e.message ?: e.javaClass.simpleName}"
                android.util.Log.e("GhostHand", "Smart Play agent crashed", e)
            } finally {
                isRunning.value = false
                paused.value = false
                main.post { hidePill() }
            }
        }
    }

    // ---------------- crosshair picker ----------------

    private var crossFrame: LinearLayout? = null
    private var crossRing: View? = null
    private var crossLp: WindowManager.LayoutParams? = null

    fun beginPick(): Int {
        val s = ++pickSession
        activePickSession?.let { old -> pickResult.value = PickOutcome(old, -1f, -1f) }
        activePickSession = s
        pickResult.value = null
        main.post { showCrosshair(s) }
        return s
    }

    private fun showCrosshair(session: Int) {
        hideCrosshair()
        val (sw, sh) = screen()
        val ringSize = dp(112)

        val ring = object : View(this) {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * resources.displayMetrics.density
                color = 0xFFF2AE3C.toInt()
            }
            val dashed = Paint(stroke).apply {
                pathEffect = DashPathEffect(floatArrayOf(dp(7).toFloat(), dp(7).toFloat()), 0f)
            }
            val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x28F2AE3C }
            override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(ringSize, ringSize) }
            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f; val r = ringSize / 2f - dp(6)
                c.drawCircle(cx, cy, r, tint)
                c.drawCircle(cx, cy, r, dashed)
                c.drawCircle(cx, cy, r * 0.42f, stroke)
                c.drawLine(cx - r, cy, cx - r * 0.62f, cy, stroke)
                c.drawLine(cx + r * 0.62f, cy, cx + r, cy, stroke)
                c.drawLine(cx, cy - r, cx, cy - r * 0.62f, stroke)
                c.drawLine(cx, cy + r * 0.62f, cx, cy + r, stroke)
                c.drawCircle(cx, cy, dp(4).toFloat(), stroke)
            }
        }
        crossRing = ring

        fun token(label: String, fg: Int, fill: Int): TextView = TextView(this).apply {
            text = label; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(fg); typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(21).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) }
        }

        val ok = token("\u2713", 0xFF17130C.toInt(), 0xFFF2AE3C.toInt())
        val no = token("\u2715", 0xFFEDE4D3.toInt(), 0xFF3B372B.toInt())
        ok.setOnClickListener {
            val lp = crossLp ?: return@setOnClickListener
            val frame = crossFrame ?: return@setOnClickListener
            val r = crossRing
            val nx = ((lp.x + frame.width / 2f) / sw).coerceIn(0.02f, 0.98f)
            val ny = ((lp.y + (r?.top ?: 0) + (r?.height ?: 0) / 2f) / sh).coerceIn(0.02f, 0.98f)
            resolvePick(session, nx, ny)
        }
        no.setOnClickListener { resolvePick(session, -1f, -1f) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF21D1A14.toInt()); cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x66F2AE3C)
            }
        }
        bar.addView(ok); bar.addView(no)

        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        frame.addView(ring); frame.addView(bar)
        crossFrame = frame

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - dp(180)) / 2
            y = (sh * 0.4f).toInt()
        }
        crossLp = lp

        var lastX = 0; var lastY = 0
        ring.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = lp.x - e.rawX.toInt(); lastY = lp.y - e.rawY.toInt(); true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = e.rawX.toInt() + lastX; lp.y = e.rawY.toInt() + lastY
                    runCatching { wm().updateViewLayout(frame, lp) }; true
                }
                else -> false
            }
        }
        runCatching { wm().addView(frame, lp) }
        Toast.makeText(applicationContext, "Drag the ring onto the target, then ✓", Toast.LENGTH_LONG).show()
    }

    private fun resolvePick(session: Int, nx: Float, ny: Float) {
        hideCrosshair()
        if (activePickSession == session) {
            pickResult.value = PickOutcome(session, nx, ny)
            activePickSession = null
        }
        if (ny >= 0f) {
            Toast.makeText(applicationContext,
                "Captured ${(nx * 100).roundToInt()}%, ${(ny * 100).roundToInt()}%",
                Toast.LENGTH_SHORT).show()
            hopToApp()
        }
    }

    private fun hideCrosshair() {
        crossFrame?.let { runCatching { wm().removeView(it) } }
        crossFrame = null; crossRing = null; crossLp = null
    }

    private fun hopToApp() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    // ---------------- floating control pill ----------------

    private var pillFrame: LinearLayout? = null
    private var pillLabel: TextView? = null
    private var pillPauseBtn: TextView? = null

    private fun showPill(keepAwake: Boolean) {
        if (pillFrame != null) return
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(0xF21D1A14.toInt()); cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x80F2AE3C.toInt())
            }
        }
        val label = TextView(this).apply {
            text = "Starting…"; textSize = 10f
            setTextColor(0xFFF2AE3C.toInt()); typeface = Typeface.MONOSPACE
        }
        pillLabel = label
        frame.addView(label)

        val pause = TextView(this).apply {
            text = "\u275A\u275A"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(0xFFEDE4D3.toInt())
            background = GradientDrawable().apply { setColor(0xFF302B20.toInt()); cornerRadius = dp(17).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(8) }
        }
        pause.setOnClickListener { togglePause() }
        pillPauseBtn = pause
        frame.addView(pause)

        val stop = TextView(this).apply {
            text = "\u25A0"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(0xFFE36952.toInt())
            background = GradientDrawable().apply { setColor(0xFF33221C.toInt()); cornerRadius = dp(17).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) }
        }
        stop.setOnClickListener { stop() }
        frame.addView(stop)
        pillFrame = frame

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (keepAwake) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(52) }

        var lastX = 0; var lastY = 0
        label.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = lp.x - e.rawX.toInt(); lastY = lp.y - e.rawY.toInt(); true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = e.rawX.toInt() + lastX; lp.y = e.rawY.toInt() + lastY
                    runCatching { wm().updateViewLayout(frame, lp) }; true
                }
                else -> false
            }
        }
        try {
            wm().addView(frame, lp)
        } catch (e: Exception) {
            pillFrame = null
            pillLabel = null
            pillPauseBtn = null
            statusText.value = "Controls unavailable: ${e.javaClass.simpleName}"
            android.util.Log.e("GhostHand", "Unable to display accessibility control pill", e)
            Toast.makeText(
                applicationContext,
                "GhostHand couldn't display its controls: ${e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hidePill() {
        pillFrame?.let { runCatching { wm().removeView(it) } }
        pillFrame = null; pillLabel = null; pillPauseBtn = null
    }

    private fun wm(): WindowManager = getSystemService(WindowManager::class.java)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
