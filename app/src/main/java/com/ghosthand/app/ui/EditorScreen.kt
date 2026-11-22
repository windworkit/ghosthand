@file:OptIn(ExperimentalMaterial3Api::class)

package com.ghosthand.app.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LifecycleEventObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ghosthand.app.AutoPlayerService
import com.ghosthand.app.Script
import com.ghosthand.app.ScriptStore
import com.ghosthand.app.Step
import com.ghosthand.app.depths
import com.ghosthand.app.describe
import com.ghosthand.app.estimateMs
import com.ghosthand.app.fmtDur
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EditorScreen(id: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var script by remember(id) { mutableStateOf(ScriptStore.load(id) ?: Script(id = id)) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    val running by AutoPlayerService.isRunning.collectAsState()
    val status by AutoPlayerService.statusText.collectAsState()
    val listState = rememberLazyListState()

    var svcOn by remember { mutableStateOf(AutoPlayerService.instance != null) }
    val lc = LocalLifecycleOwner.current
    DisposableEffect(lc) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) svcOn = AutoPlayerService.instance != null
        }
        lc.lifecycle.addObserver(obs)
        onDispose { lc.lifecycle.removeObserver(obs) }
    }

    fun mutate(s: Script) { script = s; ScriptStore.save(s) }
    fun setStep(i: Int, st: Step) {
        val l = script.steps.toMutableList(); l[i] = st
        mutate(script.copy(steps = l))
    }

    fun openAccessSettings() {
        scope.launch {
            val r = snackbar.showSnackbar(
                if (AutoPlayerService.instance == null) "Enable GhostHand in Accessibility first"
                else "GhostHand is not enabled",
                actionLabel = "Settings", duration = SnackbarDuration.Long
            )
            if (r == SnackbarResult.ActionPerformed)
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    fun launchPick(i: Int, transform: (Step, Float, Float) -> Step) {
        val svc = AutoPlayerService.instance
        if (svc == null) { openAccessSettings(); return }
        val session = svc.beginPick()
        (ctx as? Activity)?.moveTaskToBack(true)
        scope.launch {
            val out = AutoPlayerService.awaitPick(session)
            if (out.y < 0f) { snackbar.showSnackbar("Pick cancelled"); return@launch }
            val l = script.steps.toMutableList()
            l[i] = transform(l[i], out.x, out.y)
            mutate(script.copy(steps = l))
            snackbar.showSnackbar("Position set — ${(out.x * 100).roundToInt()}% · ${(out.y * 100).roundToInt()}%")
        }
    }

    LaunchedEffect(selected) { selected?.let { listState.animateScrollToItem(it) } }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { sheetOpen = true },
                containerColor = Amber, contentColor = Color(0xFF171309)
            ) { Icon(Icons.Filled.Add, null) }
        },
        topBar = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ink) }
                TextField(
                    value = script.name,
                    onValueChange = { mutate(script.copy(name = it.take(40))) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Amber
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (running) IconButton(onClick = { AutoPlayerService.instance?.stop() }) {
                    Icon(Icons.Filled.Stop, null, tint = Danger)
                }
            }
        },
        bottomBar = {
            PlayBar(
                script = script, running = running, status = status,
                onPlay = {
                    val svc = AutoPlayerService.instance
                    if (svc == null) openAccessSettings() else svc.play(script)
                },
                onStop = { AutoPlayerService.instance?.stop() }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (!svcOn) ServiceMini { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            SessionStrip(script) { mutate(it) }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 620.dp
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        StepsColumn(
                            script = script, selected = selected, listState = listState,
                            onSelected = { selected = if (it == selected) null else it },
                            onSet = ::setStep,
                            onPick = { i, tr -> launchPick(i, tr) },
                            modifier = Modifier.weight(1.15f)
                        )
                        StagePanel(
                            script = script, selected = selected, onSelect = { selected = it },
                            modifier = Modifier.weight(0.85f).padding(end = 16.dp, top = 4.dp)
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        StagePanel(
                            script = script, selected = selected, onSelect = { selected = it },
                            modifier = Modifier.fillMaxWidth().height(230.dp).padding(horizontal = 16.dp)
                        )
                        StepsColumn(
                            script = script, selected = selected, listState = listState,
                            onSelected = { selected = if (it == selected) null else it },
                            onSet = ::setStep,
                            onPick = { i, tr -> launchPick(i, tr) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, containerColor = CardC) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text("Add a move", style = MaterialTheme.typography.titleLarge, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text("positions default to center — refine them with the crosshair picker",
                    color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                fun add(st: Step) {
                    val l = script.steps.toMutableList(); l.add(st)
                    mutate(script.copy(steps = l))
                    selected = l.size - 1
                    sheetOpen = false
                }
                MoveOption("TAP", "one press at a fixed point") { add(Step.Tap(0.5f, 0.5f)) }
                MoveOption("HOLD", "press and hold to open things") { add(Step.LongPress(0.5f, 0.5f)) }
                MoveOption("SWIPE", "a flick between two points") { add(Step.Swipe(0.3f, 0.6f, 0.7f, 0.4f)) }
                MoveOption("WAIT", "a pause — gets the timing jitter") { add(Step.Wait(500)) }
                MoveOption("LOOP", "repeat everything down to the next END") { add(Step.LoopStart(5)) }
                MoveOption("END", "close the nearest LOOP above") { add(Step.LoopEnd) }
            }
        }
    }
}

@Composable
private fun ServiceMini(onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp), color = CardC,
        border = BorderStroke(1.dp, AmberDeep),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Accessibility is off — picks and playback need it.", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onOpen) { Text("Fix", color = Amber, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SessionStrip(script: Script, onChange: (Script) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PASSES", color = Muted, fontSize = 10.sp, fontFamily = Mono, letterSpacing = 1.sp)
            Spacer(Modifier.width(10.dp))
            listOf(1, 3, 5, 10, 0).forEach { v ->
                FilterChip(
                    selected = script.repeats == v,
                    onClick = { onChange(script.copy(repeats = v)) },
                    label = { Text(if (v == 0) "∞" else "×$v", fontFamily = Mono, fontSize = 12.sp) },
                    border = BorderStroke(1.dp, if (script.repeats == v) Amber else LineC)
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stepper("JITTER t", script.jitterMs, "ms", 20L, 0L, 600L) { onChange(script.copy(jitterMs = it)) }
            StepperF("pos", script.jitterDp, "dp", 1f, 0f, 20f) { onChange(script.copy(jitterDp = it)) }
            Spacer(Modifier.width(6.dp))
            Text("AWAKE", color = Muted, fontSize = 10.sp, fontFamily = Mono, letterSpacing = 1.sp)
            Switch(
                checked = script.keepAwake,
                onCheckedChange = { onChange(script.copy(keepAwake = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Amber, uncheckedTrackColor = LineC)
            )
        }
    }
}

@Composable
private fun Stepper(label: String, v: Long, suffix: String, step: Long, min: Long, max: Long, on: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 10.sp, fontFamily = Mono)
        IconButton(onClick = { on((v - step).coerceAtLeast(min)) }, modifier = Modifier.size(26.dp)) { Text("−", color = Ink) }
        Text("$v$suffix", color = Ink, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { on((v + step).coerceAtMost(max)) }, modifier = Modifier.size(26.dp)) { Text("+", color = Ink) }
    }
}

@Composable
private fun StepperF(label: String, v: Float, suffix: String, step: Float, min: Float, max: Float, on: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 10.sp, fontFamily = Mono)
        IconButton(onClick = { on((v - step).coerceAtLeast(min)) }, modifier = Modifier.size(26.dp)) { Text("−", color = Ink) }
        Text("${v.roundToInt()}$suffix", color = Ink, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { on((v + step).coerceAtMost(max)) }, modifier = Modifier.size(26.dp)) { Text("+", color = Ink) }
    }
}

@Composable
private fun StepsColumn(
    script: Script, selected: Int?, listState: LazyListState,
    onSelected: (Int) -> Unit, onSet: (Int, Step) -> Unit,
    onPick: (Int, (Step, Float, Float) -> Step) -> Unit,
    modifier: Modifier = Modifier
) {
    val depthList = remember(script.steps) { depths(script.steps) }
    LazyColumn(
        state = listState, modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(script.steps) { i, st ->
            StepRow(
                i = i, step = st, depth = depthList.getOrElse(i) { 0 },
                expanded = selected == i,
                onToggle = { onSelected(i) },
                onSet = { s -> onSet(i, s) },
                onDelete = {
                    val l = script.steps.toMutableList(); l.removeAt(i)
                    onSetSteps@Unit
                    ScriptStore.save(script.copy(steps = l))
                },
                onMove = { dir ->
                    val j = i + dir
                    if (j in script.steps.indices) {
                        val l = script.steps.toMutableList()
                        val tmp = l[i]; l[i] = l[j]; l[j] = tmp
                        ScriptStore.save(script.copy(steps = l))
                    }
                },
                onDuplicate = {
                    val l = script.steps.toMutableList(); l.add(i + 1, l[i])
                    ScriptStore.save(script.copy(steps = l))
                },
                onPick = { tr -> onPick(i, tr) }
            )
        }
        if (script.steps.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("◎", color = AmberDeep, fontSize = 34.sp)
                Spacer(Modifier.height(8.dp))
                Text("No moves yet — hit ＋, then use the crosshair\nto mark spots on the actual game screen.",
                    color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun StepRow(
    i: Int, step: Step, depth: Int, expanded: Boolean,
    onToggle: () -> Unit, onSet: (Step) -> Unit,
    onDelete: () -> Unit, onMove: (Int) -> Unit, onDuplicate: () -> Unit,
    onPick: ((Step, Float, Float) -> Step) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (expanded) CardHi else CardC,
        border = BorderStroke(1.dp, if (expanded) AmberDeep else LineC),
        modifier = Modifier.padding(start = (depth * 14).dp)
            .fillMaxWidth().animateContentSize()
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Muted, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.width(22.dp))
                Tag(step)
                Spacer(Modifier.width(10.dp))
                Text(describe(step), color = Ink, fontSize = 12.sp, fontFamily = Mono,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onMove(-1) }, Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowUp, null, tint = Muted, Modifier.size(16.dp)) }
                IconButton(onClick = { onMove(1) }, Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = Muted, Modifier.size(16.dp)) }
                IconButton(onClick = onDuplicate, Modifier.size(28.dp)) { Icon(Icons.Filled.ContentCopy, null, tint = Muted, Modifier.size(14.dp)) }
                IconButton(onClick = onDelete, Modifier.size(28.dp)) { Icon(Icons.Filled.Delete, null, tint = Danger, Modifier.size(15.dp)) }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 4.dp)) {
                    when (step) {
                        is Step.Tap -> {
                            CoordSlider("x", step.x) { onSet(step.copy(x = it)) }
                            CoordSlider("y", step.y) { onSet(step.copy(y = it)) }
                            PickBtn("Pick this position") { onPick { _, nx, ny -> step.copy(x = nx, y = ny) } }
                        }
                        is Step.LongPress -> {
                            CoordSlider("x", step.x) { onSet(step.copy(x = it)) }
                            CoordSlider("y", step.y) { onSet(step.copy(y = it)) }
                            Stepper("HOLD", step.ms, "ms", 100L, 200L, 10_000L) { onSet(step.copy(ms = it)) }
                            PickBtn("Pick this position") { onPick { _, nx, ny -> step.copy(x = nx, y = ny) } }
                        }
                        is Step.Swipe -> {
                            Text("FROM", color = Muted, fontSize = 10.sp, fontFamily = Mono)
                            CoordSlider("x", step.x1) { onSet(step.copy(x1 = it)) }
                            CoordSlider("y", step.y1) { onSet(step.copy(y1 = it)) }
                            PickBtn("Pick swipe start") { onPick { _, nx, ny -> step.copy(x1 = nx, y1 = ny) } }
                            Text("TO", color = Muted, fontSize = 10.sp, fontFamily = Mono)
                            CoordSlider("x", step.x2) { onSet(step.copy(x2 = it)) }
                            CoordSlider("y", step.y2) { onSet(step.copy(y2 = it)) }
                            Stepper("DUR", step.ms, "ms", 40L, 80L, 3_000L) { onSet(step.copy(ms = it)) }
                            PickBtn("Pick swipe end") { onPick { _, nx, ny -> step.copy(x2 = nx, y2 = ny) } }
                        }
                        is Step.Wait -> Stepper("WAIT", step.ms, "ms", 50L, 50L, 120_000L) { onSet(step.copy(ms = it)) }
                        is Step.LoopStart -> Row {
                            listOf(2, 3, 5, 10, 20, 0).forEach { v ->
                                FilterChip(
                                    selected = step.times == v,
                                    onClick = { onSet(step.copy(times = v)) },
                                    label = { Text(if (v == 0) "∞" else "×$v", fontFamily = Mono, fontSize = 11.sp) },
                                    border = BorderStroke(1.dp, if (step.times == v) Amber else LineC)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                        is Step.LoopEnd -> Text("closes the nearest LOOP above", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(step: Step) {
    val (txt, fg, bg) = when (step) {
        is Step.Tap       -> Triple("TAP",  Color(0xFF171309), Amber)
        is Step.LongPress -> Triple("HOLD", Ink, AmberDeep)
        is Step.Swipe     -> Triple("SWIPE", Color(0xFF171309), GoodGreen)
        is Step.Wait      -> Triple("WAIT", Muted, LineC)
        is Step.LoopStart -> Triple(if (step.times <= 0) "LOOP ∞" else "LOOP ×${step.times}", Color(0xFF171309), Amber)
        is Step.LoopEnd   -> Triple("END", Muted, LineC)
    }
    Text(txt, color = fg, fontSize = 9.sp, fontFamily = Mono,
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp))
}

@Composable
private fun CoordSlider(label: String, v: Float, on: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, color = Muted, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.width(16.dp))
        Slider(
            value = v, onValueChange = on,
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = LineC),
            modifier = Modifier.weight(1f)
        )
        Text("${(v * 100).roundToInt()}%", color = Ink, fontSize = 12.sp, fontFamily = Mono,
            modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun PickBtn(label: String, on: () -> Unit) {
    OutlinedButton(
        onClick = on,
        border = BorderStroke(1.dp, AmberDeep),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Text("◎", color = Amber)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Amber, fontSize = 12.sp)
    }
}

@Composable
private fun MoveOption(txt: String, desc: String, onAdd: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp), color = CardC,
        border = BorderStroke(1.dp, LineC),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onAdd)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(txt, color = Amber, fontSize = 10.sp, fontFamily = Mono,
                modifier = Modifier.background(AmberDeep.copy(alpha = 0.25f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
            Spacer(Modifier.width(12.dp))
            Text(desc, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PlayBar(
    script: Script, running: Boolean, status: String,
    onPlay: () -> Unit, onStop: () -> Unit
) {
    Surface(color = CardC, border = BorderStroke(1.dp, LineC)) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (script.repeats == 0) "≈ ${fmtDur(estimateMs(script))} / pass · endless"
                    else "≈ ${fmtDur(estimateMs(script))} total",
                    color = Muted, fontSize = 11.sp, fontFamily = Mono
                )
                if (running) Text(status, color = Amber, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Text("${script.steps.size} moves queued", color = Muted, fontSize = 11.sp, fontFamily = Mono)
            }
            if (running) {
                OutlinedButton(
                    onClick = onStop,
                    border = BorderStroke(1.dp, Danger),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Stop, null, tint = Danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("STOP", color = Danger, fontFamily = Mono, fontSize = 13.sp)
                }
            } else {
                Button(
                    onClick = onPlay,
                    enabled = script.steps.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber, contentColor = Color(0xFF171309),
                        disabledContainerColor = LineC, disabledContentColor = Muted
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("PLAY", fontFamily = Mono, fontSize = 14.sp)
                }
            }
        }
    }
}
