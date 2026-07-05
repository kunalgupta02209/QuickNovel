package com.lagradost.quicknovel.llm

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

/** Progress notification for the background LLM model download. */
object LlmModelDownloadNotifications {
    private const val CHANNEL_ID = "quicknovel.llm_model_dl"
    private const val CHANNEL_NAME = "AI model download"
    private var channelCreated = false

    private fun notifId(def: LlmModels.ModelDef) = ("llm_dl_" + def.id).hashCode()

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "Downloading the on-device rewriting model"
                setSound(null, null); enableVibration(false)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        channelCreated = true
    }

    private fun build(context: Context, def: LlmModels.ModelDef, progress: Float, done: Boolean, failed: Boolean): Notification {
        ensureChannel(context)
        val pct = (progress * 100).toInt()
        val text = when {
            failed -> "Download failed"
            done -> "Model ready"
            else -> "Downloading  $pct%  (~${def.approxSizeMb} MB)"
        }
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.netflix_download)
            .setContentTitle(def.displayName)
            .setContentText(text)
            .setOngoing(!done && !failed)
            .setAutoCancel(done || failed)
        if (!done && !failed) b.setProgress(100, pct, progress <= 0f)
        return b.build()
    }

    fun update(context: Context, def: LlmModels.ModelDef, progress: Float) {
        with(NotificationManagerCompat.from(context)) {
            try { notify(notifId(def), build(context, def, progress, done = false, failed = false)) } catch (t: Throwable) { logError(t) }
        }
    }

    fun complete(context: Context, def: LlmModels.ModelDef) = update(context, def, 1f, done = true)
    fun fail(context: Context, def: LlmModels.ModelDef) = update(context, def, 0f, failed = true)

    private fun update(context: Context, def: LlmModels.ModelDef, progress: Float, done: Boolean = false, failed: Boolean = false) {
        with(NotificationManagerCompat.from(context)) {
            try { notify(notifId(def), build(context, def, progress, done, failed)) } catch (t: Throwable) { logError(t) }
        }
    }

    fun foregroundInfo(context: Context, def: LlmModels.ModelDef, progress: Float): ForegroundInfo {
        val notif = build(context, def, progress, done = false, failed = false)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(notifId(def), notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notifId(def), notif)
    }
}
