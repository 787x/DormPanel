package com.dormpanel.app.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.dormpanel.app.MainActivity
import com.dormpanel.app.R

internal class AirPlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val controller by lazy { AirPlayController.get(this) }
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var receiver: AirPlayReceiver
    private var notificationPhase: Pair<String, AirPlayPhase>? = null
    private var started = false
    private var destroyed = false
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "AirPlay 音频接收",
            NotificationManager.IMPORTANCE_LOW))
        thread = HandlerThread("AirPlay receiver").apply { start() }
        worker = Handler(thread.looper)
        receiver = AirPlayReceiver(applicationContext, worker) { value ->
            main.post {
                if (!destroyed) {
                    controller.onServiceState(this, value)
                    val key = value.name to value.phase
                    if (controller.settings.enabled && key != notificationPhase) {
                        notificationPhase = key
                        notifications.notify(NOTIFICATION, notification(value.name, value.phase))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = controller.settings
        if (!started) {
            started = true
            controller.onServiceStart(this)
            startForeground(NOTIFICATION, notification(settings.name, AirPlayPhase.STARTING))
        }
        if (intent?.action == DISABLE) {
            controller.disableFromNotification()
            return START_NOT_STICKY
        }
        if (!settings.enabled || intent?.action == STOP) {
            worker.post {
                receiver.close()
                main.post {
                    if (stopSelfResult(startId)) {
                        controller.onServiceStop(this)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }
            return START_NOT_STICKY
        }
        val command = AirPlayCommand.entries.firstOrNull { it.name == intent?.getStringExtra("command") }
        worker.post {
            receiver.start(settings.name)
            if (command != null) receiver.command(command)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        worker.post {
            receiver.close()
            main.post { controller.onServiceStop(this) }
            thread.quitSafely()
        }
        super.onDestroy()
    }

    private fun notification(name: String, phase: AirPlayPhase): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, AirPlayService::class.java).setAction(DISABLE),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(name).setContentText(phase.label(this))
            .setSmallIcon(R.drawable.ic_airplay_audio)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "关闭 AirPlay", stop).build())
            .build()
    }

    companion object {
        const val STOP = "com.dormpanel.app.airplay.STOP"
        private const val DISABLE = "com.dormpanel.app.airplay.DISABLE"
        private const val CHANNEL = "airplay_receiver"
        private const val NOTIFICATION = 8401
    }
}
