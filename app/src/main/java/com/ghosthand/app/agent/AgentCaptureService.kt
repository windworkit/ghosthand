package com.ghosthand.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ghosthand.app.R
import com.ghosthand.app.AutoPlayerService
import com.ghosthand.app.vision.ScreenCaster

class AgentCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    private var caster: ScreenCaster? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA)

        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel("gh", "Smart play", NotificationManager.IMPORTANCE_LOW)
        )
        val notif: Notification = NotificationCompat.Builder(this, "gh")
            .setContentTitle("GhostHand is watching the screen")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true).build()

        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notif)

        if (code != 0 && data != null) {
            AgentBus.clear()
            try {
                val newCaster = ScreenCaster(this)
                val started = newCaster.start(
                    code,
                    data,
                    onStopped = {
                        AgentBus.clear()
                        AutoPlayerService.instance?.onCaptureStopped()
                        mainHandler.post {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    },
                    onFrame = { bmp -> AgentBus.push(bmp) }
                )
                if (!started) throw IllegalStateException("MediaProjection could not be created")
                caster = newCaster
            } catch (e: Exception) {
                android.util.Log.e("GhostHandCapture", "Screen capture startup failed", e)
                AgentBus.clear()
                AutoPlayerService.instance?.onCaptureStopped()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AgentBus.clear()
        caster?.stop(); caster = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
