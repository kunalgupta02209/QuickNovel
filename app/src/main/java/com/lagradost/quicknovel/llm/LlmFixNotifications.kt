package com.lagradost.quicknovel.llm

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
import com.lagradost.quicknovel.LlmFixNotificationService
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError

/** Foreground notification for background LLM rewriting (progress + pause/resume/stop). */
object LlmFixNotifications {
    private const val CHANNEL_ID = "quicknovel.llm_fix"
    private const val CHANNEL_NAME = "AI chapter rewriting"
    private var channelCreated = false

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background on-device rewriting of machine-translated chapters"
                setSound(null, null); enableVibration(false)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        channelCreated = true
    }

    private fun build(context: Context, req: LlmFixManager.FixRequest, state: DownloadState, done: Int, total: Int): Notification {
        ensureChannel(context)
        val status = when (state) {
            DownloadState.IsDone -> "Rewrite complete"
            DownloadState.IsDownloading -> "Rewriting  $done / $total"
            DownloadState.IsPaused -> "Paused  $done / $total"
            DownloadState.IsFailed -> "Failed"
            DownloadState.IsStopped -> "Stopped"
            DownloadState.IsPending -> "Queued"
            else -> ""
        }
        val ongoing = state == DownloadState.IsDownloading || state == DownloadState.IsPaused || state == DownloadState.IsPending
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_auto_fix)
            .setContentTitle(req.name)
            .setContentText(status)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (state == DownloadState.IsDownloading || state == DownloadState.IsPaused) {
            builder.setProgress(total.coerceAtLeast(1), done, false)
            val actions = if (state == DownloadState.IsDownloading)
                listOf(DownloadActionType.Pause, DownloadActionType.Stop)
            else listOf(DownloadActionType.Resume, DownloadActionType.Stop)
            actions.forEachIndexed { i, action ->
                val intent = Intent(context, LlmFixNotificationService::class.java).apply {
                    putExtra("type", action.name.lowercase()); putExtra("key", req.key)
                }
                val pending = PendingIntent.getService(
                    context, req.notifId + 1 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
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

    fun update(context: Context, req: LlmFixManager.FixRequest, state: DownloadState, done: Int, total: Int) {
        with(NotificationManagerCompat.from(context)) {
            try { notify(req.notifId, build(context, req, state, done, total)) } catch (t: Throwable) { logError(t) }
        }
    }

    fun foregroundInfo(context: Context, req: LlmFixManager.FixRequest): ForegroundInfo {
        val total = req.rangeEnd - req.rangeStart + 1
        val notif = build(context, req, DownloadState.IsPending, 0, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(req.notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(req.notifId, notif)
    }
}
