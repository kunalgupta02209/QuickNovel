package com.lagradost.quicknovel

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.lagradost.quicknovel.llm.LlmFixManager

/** Routes LLM-fix notification action buttons (pause/resume/stop) to the manager, keyed by String. */
class LlmFixNotificationService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val key = intent.getStringExtra("key")
            val type = intent.getStringExtra("type")
            if (key != null && type != null) {
                val action = when (type) {
                    "resume" -> DownloadActionType.Resume
                    "pause" -> DownloadActionType.Pause
                    "stop" -> DownloadActionType.Stop
                    else -> null
                }
                if (action != null) LlmFixManager.addPendingAction(key, action)
            }
        }
        return if (intent == null) START_NOT_STICKY else START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
