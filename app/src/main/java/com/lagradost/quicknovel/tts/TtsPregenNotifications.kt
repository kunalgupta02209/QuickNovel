package com.lagradost.quicknovel.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.lagradost.quicknovel.DownloadActionType
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.TtsPregenNotificationService
import com.lagradost.quicknovel.mvvm.logError

/** Builds the background TTS-generation notification (progress + pause/resume/stop) and the
 *  ForegroundInfo the WorkManager worker attaches, on a dedicated low-importance channel. */
object TtsPregenNotifications {
    private const val CHANNEL_ID = "quicknovel.tts_pregen"
    private const val CHANNEL_NAME = "TTS pre-generation"
    private var channelCreated = false

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background text-to-speech audio generation"
                setSound(null, null)
                enableVibration(false)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        channelCreated = true
    }

    private fun build(
        context: Context,
        req: TtsPregenManager.PregenRequest,
        state: DownloadState,
        done: Int,
        total: Int,
    ): Notification {
        ensureChannel(context)
        val statusText = when (state) {
            DownloadState.IsDone -> "Audio ready"
            DownloadState.IsDownloading -> "Generating audio  $done / $total"
            DownloadState.IsPaused -> "Paused  $done / $total"
            DownloadState.IsFailed -> "Failed"
            DownloadState.IsStopped -> "Stopped"
            DownloadState.IsPending -> "Queued"
            else -> ""
        }
        val ongoing = state == DownloadState.IsDownloading ||
                state == DownloadState.IsPaused || state == DownloadState.IsPending

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_baseline_volume_up_24)
            .setContentTitle(req.name)
            .setContentText(statusText)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        if (state == DownloadState.IsDownloading || state == DownloadState.IsPaused) {
            builder.setProgress(total.coerceAtLeast(1), done, false)
            val actions = if (state == DownloadState.IsDownloading)
                listOf(DownloadActionType.Pause, DownloadActionType.Stop)
            else
                listOf(DownloadActionType.Resume, DownloadActionType.Stop)
            actions.forEachIndexed { i, action ->
                val intent = Intent(context, TtsPregenNotificationService::class.java).apply {
                    putExtra("type", action.name.lowercase())
                    putExtra("key", req.key)
                }
                val pending = PendingIntent.getService(
                    context, req.notifId + 1 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                )
                val icon = when (action) {
                    DownloadActionType.Resume -> R.drawable.rdload
                    DownloadActionType.Pause -> R.drawable.rdpause
                    DownloadActionType.Stop -> R.drawable.rderror
                }
                builder.addAction(icon, action.name, pending)
            }
        }
        return builder.build()
    }

    fun update(
        context: Context,
        req: TtsPregenManager.PregenRequest,
        state: DownloadState,
        done: Int,
        total: Int,
    ) {
        val notif = build(context, req, state, done, total)
        with(NotificationManagerCompat.from(context)) {
            try {
                notify(req.notifId, notif)
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    fun foregroundInfo(context: Context, req: TtsPregenManager.PregenRequest): ForegroundInfo {
        val total = req.rangeEnd - req.rangeStart + 1
        val notif = build(context, req, DownloadState.IsPending, 0, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(req.notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else
            ForegroundInfo(req.notifId, notif)
    }
}
