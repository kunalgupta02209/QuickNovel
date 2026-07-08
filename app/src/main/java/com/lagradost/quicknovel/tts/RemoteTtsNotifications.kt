package com.lagradost.quicknovel.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError

/** Low-importance foreground/progress notification for the server-offloaded ("Cloud TTS") generation. */
object RemoteTtsNotifications {
    private const val CHANNEL_ID = "quicknovel.tts_remote"
    private const val CHANNEL_NAME = "Cloud TTS"
    private var channelCreated = false

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Server-generated text-to-speech audio"
                setSound(null, null)
                enableVibration(false)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        channelCreated = true
    }

    private fun build(context: Context, req: RemoteTtsManager.RemoteTtsRequest, done: Int, total: Int, finished: Boolean): Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_baseline_volume_up_24)
            .setContentTitle(req.name)
            .setContentText(if (finished) "Cloud audio ready" else "Generating on server  $done / $total")
            .setOngoing(!finished)
            .setAutoCancel(finished)
        if (!finished) {
            builder.setProgress(total.coerceAtLeast(1), done, false)
            // pause/resume/stop route through RemoteTtsNotificationService -> addPendingAction
            listOf("pause" to "Pause", "resume" to "Resume", "stop" to "Stop").forEachIndexed { i, (type, label) ->
                val intent = android.content.Intent(context, com.lagradost.quicknovel.RemoteTtsNotificationService::class.java)
                    .putExtra("type", type)
                    .putExtra("key", req.key)
                val pending = android.app.PendingIntent.getService(
                    context, req.notifId + 1 + i, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0)
                )
                builder.addAction(0, label, pending)
            }
        }
        return builder.build()
    }

    fun update(context: Context, req: RemoteTtsManager.RemoteTtsRequest, done: Int, total: Int, finished: Boolean) {
        with(NotificationManagerCompat.from(context)) {
            try {
                notify(req.notifId, build(context, req, done, total, finished))
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    fun foregroundInfo(context: Context, req: RemoteTtsManager.RemoteTtsRequest): ForegroundInfo {
        val total = req.rangeEnd - req.rangeStart + 1
        val notif = build(context, req, 0, total, finished = false)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(req.notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            ForegroundInfo(req.notifId, notif)
    }
}
