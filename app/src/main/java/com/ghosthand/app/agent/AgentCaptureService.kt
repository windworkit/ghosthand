package com.ghosthand.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ghosthand.app.R
import com.ghosthand.app.vision.ScreenCaster

class AgentCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    private var caster: ScreenCaster? = null

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
            AgentBus.frame.value = null
            try {
                val newCaster = ScreenCaster(this)
                newCaster.start(code, data) { captured -> AgentBus.push(captured) }
                caster = newCaster
                AgentBus.casterActive = true
            } catch (e: Exception) {
                android.util.Log.e("GhostHandCapture", "Screen capture startup failed", e)
                AgentBus.casterActive = false
                AgentBus.frame.value = null
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
        AgentBus.casterActive = false
        AgentBus.frame.value = null
        caster?.stop(); caster = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
