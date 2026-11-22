package com.ghosthand.app.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ghosthand.app.MainActivity
import com.ghosthand.app.Script
import com.ghosthand.app.ScriptStore
import com.ghosthand.app.estimateMs
import com.ghosthand.app.fmtDur
import java.util.UUID

@Composable
fun ScriptListScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(ScriptStore.list()) }
    var serviceOn by remember { mutableStateOf(AutoPlayerService.instance != null) }
    var confirmDelete by remember { mutableStateOf<Script?>(null) }
    val running by AutoPlayerService.isRunning.collectAsState()
    val status by AutoPlayerService.statusText.collectAsState()

    val lc = LocalLifecycleOwner.current
    DisposableEffect(lc) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                items = ScriptStore.list()
                serviceOn = AutoPlayerService.instance != null
            }
        }
        lc.lifecycle.addObserver(obs)
        onDispose { lc.lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Text("GhostHand", style = MaterialTheme.typography.titleLarge, color = Ink)
        Spacer(Modifier.height(2.dp))
        Text("map the moves once — your phone plays the level",
            color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text("NORMALIZED COORDS · FOLD + COVER SAFE · JITTERED INPUT",
            color = AmberDeep, fontSize = 9.sp, fontFamily = Mono, letterSpacing = 1.sp)
        Spacer(Modifier.height(16.dp))

        if (running) PlayingStrip(status)
        if (!serviceOn) ServiceBanner { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = CardHi, border = BorderStroke(1.dp, Amber),
            modifier = Modifier.fillMaxWidth().clickable {
                val act = ctx as? MainActivity ?: return@clickable
                if (AutoPlayerService.instance == null) {
                    Toast.makeText(ctx, "Enable GhostHand in Accessibility first", Toast.LENGTH_SHORT).show()
                    return@clickable
                }
                act.requestProjection()
            }
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("SMART PLAY", color = Color(0xFF171309), fontSize = 11.sp, fontFamily = Mono,
                        modifier = Modifier.background(Amber, RoundedCornerShape(4.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (running) "agent is live — stop via the pill" else "Woodoku-style puzzle solver",
                        color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("Watches the screen, reads the board, places the best pieces,\nsees ads/reward popups through to the end.",
                    color = Muted, fontSize = 10.sp, fontFamily = Mono, lineHeight = 14.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        if (items.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("◎", color = Amber, fontSize = 56.sp)
                Spacer(Modifier.height(10.dp))
                Text("No runs yet", color = Ink, fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Create a run, open your game, position taps with the\ncrosshair, wrap them in a loop — and let it grind.",
                    color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(items, key = { _, s -> s.id }) { _, s ->
                    DeckRow(
                        s = s,
                        onOpen = { open(s.id) },
                        onCopy = {
                            ScriptStore.save(s.copy(id = UUID.randomUUID().toString(), name = s.name + " copy"))
                            items = ScriptStore.list()
                        },
                        onDelete = { confirmDelete = s }
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                val s = ScriptStore.create("Run ${items.size + 1}")
                open(s.id)
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp).height(52.dp),
            border = BorderStroke(1.dp, AmberDeep),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("+", color = Amber, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text("New run", color = Amber)
        }
    }

    confirmDelete?.let { victim ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${victim.name}”?") },
            text = { Text("The move list is gone for good.") },
            confirmButton = {
                TextButton(onClick = {
                    ScriptStore.delete(victim.id); items = ScriptStore.list(); confirmDelete = null
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } }
        )
    }
}

@Composable
private fun PlayingStrip(status: String) {
    val t by rememberInfiniteTransition(label = "run").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    Surface(
        shape = RoundedCornerShape(10.dp), color = CardC,
        border = BorderStroke(1.dp, AmberDeep), modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(12.dp)) {
                drawCircle(Amber, radius = (4 + 4 * t).dp.toPx())
                drawCircle(Amber.copy(alpha = 1f - t), radius = 4.dp.toPx())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("RUNNING", color = Amber, fontSize = 10.sp, fontFamily = Mono, letterSpacing = 2.sp)
                Text(status, color = Muted, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { AutoPlayerService.instance?.stop() }) {
                Text("STOP", color = Danger, fontFamily = Mono)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ServiceBanner(onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = CardC, border = BorderStroke(1.dp, AmberDeep),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("One-time setup", color = Amber, fontSize = 12.sp)
                Text("GhostHand needs Accessibility on so it can touch the screen for you.",
                    color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            TextButton(onClick = onOpen) { Text("Open settings") }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun DeckRow(s: Script, onOpen: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = CardC,
        border = BorderStroke(1.dp, LineC),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${s.steps.size} moves · ${if (s.repeats == 0) "endless" else "×${s.repeats}"} · ≈ ${fmtDur(estimateMs(s))}",
                    color = Muted, fontSize = 11.sp, fontFamily = Mono
                )
            }
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, null, tint = Muted, modifier = Modifier.size(17.dp)) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null, tint = Muted, modifier = Modifier.size(17.dp)) }
        }
    }
}
