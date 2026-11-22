package com.ghosthand.app

import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.ghosthand.app.agent.AgentCaptureService
import com.ghosthand.app.ui.EditorScreen
import com.ghosthand.app.ui.Paper
import com.ghosthand.app.ui.ScriptListScreen
import com.ghosthand.app.ui.ghColors
import com.ghosthand.app.ui.ghType

class GhostApp : Application() {
    override fun onCreate() { super.onCreate(); ScriptStore.init(this) }
}

class MainActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != RESULT_OK || res.data == null) {
            Toast.makeText(this, "Screen sharing was cancelled.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        val player = AutoPlayerService.instance
        if (player == null) {
            Toast.makeText(
                this,
                "GhostHand Accessibility disconnected. Re-enable it, then try again.",
                Toast.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }

        // Start the visible player controls before starting screen capture. This
        // makes an accessibility/overlay problem visible instead of failing silently.
        player.startSmartPlay()

        val i = Intent(this, AgentCaptureService::class.java)
            .putExtra(AgentCaptureService.EXTRA_RESULT_CODE, res.resultCode)
            .putExtra(AgentCaptureService.EXTRA_DATA, res.data)
        try {
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            player.stop()
            Toast.makeText(
                this,
                "GhostHand couldn't start screen capture: ${e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
            android.util.Log.e("GhostHand", "Unable to start capture service", e)
        }
    }

    fun requestProjection() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { GhostHandRoot() }
    }
}

private sealed interface Route {
    data object Home : Route
    data class Editor(val id: String) : Route
}

@Composable
private fun GhostHandRoot() {
    var route by remember { mutableStateOf<Route>(Route.Home) }
    BackHandler(enabled = route !is Route.Home) { route = Route.Home }
    MaterialTheme(colorScheme = ghColors(), typography = ghType()) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            when (val r = route) {
                Route.Home -> ScriptListScreen(open = { route = Route.Editor(it) })
                is Route.Editor -> EditorScreen(id = r.id, back = { route = Route.Home })
            }
        }
    }
}
