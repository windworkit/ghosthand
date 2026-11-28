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
import android.hardware.display.DisplayManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
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
import com.ghosthand.app.agent.AgentCaptureService
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
    override fun onInterrupt() { stop() }

    override fun onDestroy() {
        instance = null
        stopCaptureService()
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
                statusText.value = "Starting… build ${BuildConfig.GIT_SHA}"
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

    private fun stopCaptureService() {
        runCatching { stopService(Intent(this, AgentCaptureService::class.java)) }
    }

    fun stop() {
        playJob?.cancel()
        // Smart Play owns the screen-capture session. Stopping automation must
        // also stop the foreground projection service so it does not keep
        // recording the screen after the user has pressed Stop.
        stopCaptureService()
    }

    /** Cancel automation after projection has already ended; do not stop the
     * capture service again from inside its own shutdown callback. */
    fun onCaptureStopped() {
        playJob?.cancel()
    }

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

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean {
        val dur = durationMs.coerceIn(40L, 60_000L)
        return suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }
    }

    /**
     * One continuous touch made of three chained strokes (API 26+
     * continueStroke): hold at the start, travel, hold at the end. The finger
     * never lifts between segments, so the game sees a single drag.
     */
    // An in-progress touch kept down between strokes (Smart Play aim fixing).
    private var heldStroke: GestureDescription.StrokeDescription? = null
    private var heldX = 0f
    private var heldY = 0f

    private suspend fun beginHeldDrag(x1: Float, y1: Float, x2: Float, y2: Float, moveMs: Long): Boolean {
        heldStroke = null
        val hold1 = Path().apply { moveTo(x1, y1); lineTo(x1 + 1f, y1) }
        val first = GestureDescription.StrokeDescription(hold1, 0, 220L, true)
        if (!dispatchStroke(first)) return false
        val move = Path().apply { moveTo(x1 + 1f, y1); lineTo(x2, y2) }
        val second = first.continueStroke(move, 0, moveMs, true)
        if (!dispatchStroke(second)) return false
        val settle = Path().apply { moveTo(x2, y2); lineTo(x2 + 1f, y2) }
        val third = second.continueStroke(settle, 0, 150L, true)
        if (!dispatchStroke(third)) return false
        heldStroke = third
        heldX = x2 + 1f
        heldY = y2
        return true
    }

    private suspend fun moveHeldTo(x: Float, y: Float, moveMs: Long): Boolean {
        val held = heldStroke ?: return false
        val move = Path().apply { moveTo(heldX, heldY); lineTo(x, y) }
        val moved = held.continueStroke(move, 0, moveMs.coerceIn(80L, 1000L), true)
        if (!dispatchStroke(moved)) { heldStroke = null; return false }
        val settle = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }
        val settled = moved.continueStroke(settle, 0, 150L, true)
        if (!dispatchStroke(settled)) { heldStroke = null; return false }
        heldStroke = settled
        heldX = x + 1f
        heldY = y
        return true
    }

    private suspend fun releaseHeldTouch(): Boolean {
        val held = heldStroke ?: return false
        heldStroke = null
        val lift = Path().apply { moveTo(heldX, heldY); lineTo(heldX - 1f, heldY) }
        return dispatchStroke(held.continueStroke(lift, 0, 120L, false))
    }

    private suspend fun dispatchHeldDrag(
        x1: Float, y1: Float, x2: Float, y2: Float,
        moveMs: Long, holdStartMs: Long = 220L, holdEndMs: Long = 200L
    ): Boolean {
        val hold1 = Path().apply { moveTo(x1, y1); lineTo(x1 + 1f, y1) }
        val first = GestureDescription.StrokeDescription(hold1, 0, holdStartMs, true)
        if (!dispatchStroke(first)) return false

        val move = Path().apply { moveTo(x1 + 1f, y1); lineTo(x2, y2) }
        val second = first.continueStroke(move, 0, moveMs, true)
        if (!dispatchStroke(second)) return false

        val hold2 = Path().apply { moveTo(x2, y2); lineTo(x2 + 1f, y2) }
        val third = second.continueStroke(hold2, 0, holdEndMs, false)
        return dispatchStroke(third)
    }

    private suspend fun dispatchStroke(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }

    private suspend fun dwell() { delay(rnd2(40L, 130L)) }
    private fun rnd(spread: Long) = if (spread <= 0) 0L else Random.nextLong(0, spread + 1)
    private fun rnd2(from: Long, to: Long) = if (to <= from) from else Random.nextLong(from, to + 1)
    private fun amp(s: Script) = s.jitterDp * resources.displayMetrics.density
    private fun off(a: Float) = if (a <= 0f) 0f else (Random.nextFloat() * 2 - 1) * a

    private fun screen(): Pair<Int, Int> = try {
        // Use the same active default-display bounds as ScreenCaster. The
        // default display changes dimensions when a Galaxy Fold moves between
        // cover and inner panels; service/app resource metrics can be stale.
        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val displayContext = if (display != null) createDisplayContext(display) else this
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && display != null) {
            runCatching {
                displayContext.createWindowContext(
                    display, WindowManager.LayoutParams.TYPE_APPLICATION, null
                ).getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            }.getOrNull()
        } else null

        val size = Point()
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            runCatching {
                @Suppress("DEPRECATION")
                display?.getRealSize(size)
            }
        }
        val width = bounds?.width()?.takeIf { it > 0 }
            ?: size.x.takeIf { it > 0 }
            ?: displayContext.resources.displayMetrics.widthPixels
        val height = bounds?.height()?.takeIf { it > 0 }
            ?: size.y.takeIf { it > 0 }
            ?: displayContext.resources.displayMetrics.heightPixels
        width to height
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
            override suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
                val (w, h) = screen()

                // Smart Play relies on cell-level placement. Random offsets or
                // a short drag can start outside a tile and cause a tap instead.
                // Use exact normalized coordinates and a deliberate straight drag.
                val startX = x1.coerceIn(0.01f, 0.99f) * w
                val startY = y1.coerceIn(0.01f, 0.99f) * h
                val endX = x2.coerceIn(0.01f, 0.99f) * w
                val endY = y2.coerceIn(0.01f, 0.99f) * h
                // Press-and-hold, move, then hold before lifting. A drag that
                // starts moving on touch-down and lifts while still moving
                // was ignored by Woodoku Blast on the Fold 7 (board unchanged
                // on every probe); games need the press to pick a piece up
                // and a settled finger to accept the drop.
                val completed = dispatchHeldDrag(
                    startX, startY, endX, endY,
                    moveMs = ms.coerceIn(500, 1800)
                )
                delay(220)
                return completed
            }

            override suspend fun dragAndHold(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
                val (w, h) = screen()
                return beginHeldDrag(
                    x1.coerceIn(0.01f, 0.99f) * w, y1.coerceIn(0.01f, 0.99f) * h,
                    x2.coerceIn(0.01f, 0.99f) * w, y2.coerceIn(0.01f, 0.99f) * h,
                    ms.coerceIn(500, 1800)
                )
            }

            override suspend fun moveHeld(x: Float, y: Float, ms: Long): Boolean {
                val (w, h) = screen()
                return moveHeldTo(x.coerceIn(0.01f, 0.99f) * w, y.coerceIn(0.01f, 0.99f) * h, ms)
            }

            override suspend fun releaseHeld(): Boolean {
                val released = releaseHeldTouch()
                delay(220)
                return released
            }
            override fun isFrameFresh(): Boolean = AgentBus.isFrameFresh()

            override fun isFrameAligned(frameWidth: Int, frameHeight: Int): Boolean {
                if (frameWidth <= 0 || frameHeight <= 0) return false
                val (displayWidth, displayHeight) = screen()
                if (displayWidth <= 0 || displayHeight <= 0) return false
                val frameAspect = frameWidth.toFloat() / frameHeight
                val displayAspect = displayWidth.toFloat() / displayHeight
                val relativeError = kotlin.math.abs(frameAspect - displayAspect) / displayAspect
                // Full-display capture should match closely. Refuse to drag
                // when Android has centered/letterboxed an app-only capture or
                // when the Fold changed panels after capture began.
                return relativeError <= 0.015f
            }
            override suspend fun idle(ms: Long) = delay(ms)
        }

        playJob = scope.launch {
            try {
                                Agent(ocr, ScreenClassifier(ocr), AdSentinel(ocr, motor), motor, isPaused = { paused.value }) {
                    statusText.value = it
                    main.post {
                        pillLabel?.text = if (it.startsWith("No fit:")) it.take(64) else it.take(28)
                    }
                }.loop { AgentBus.frame.value }
            } catch (e: CancellationException) {
                statusText.value = "Stopped"
                throw e
            } catch (e: Exception) {
                statusText.value = "Smart Play error: ${e.message ?: e.javaClass.simpleName}"
                android.util.Log.e("GhostHand", "Smart Play agent crashed", e)
                stopCaptureService()
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
