package com.lagradost.quicknovel

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import com.lagradost.quicknovel.llm.LlmModelDownloadManager
import com.lagradost.quicknovel.llm.LlmModelDownloadNotifications
import com.lagradost.quicknovel.llm.LlmModels

/**
 * Background download of an LLM fixer model GGUF (0.4–2 GB). Runs as a foreground WorkManager job with
 * a progress notification, so it survives the reader activity being closed. Unique per model id
 * (KEEP) so tapping download twice doesn't start two downloads; requires a network connection.
 */
class LlmModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val MODEL_ID = "model_id"

        fun enqueue(context: Context, id: String) {
            DownloadFileWorkManager.getWorkerManager(context).enqueueUniqueWork(
                "LLM_MODEL_DL_$id",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(LlmModelDownloadWorker::class.java)
                    .setInputData(Data.Builder().putString(MODEL_ID, id).build())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(MODEL_ID) ?: return Result.failure()
        val def = LlmModels.byId(id)
        runCatching { setForeground(LlmModelDownloadNotifications.foregroundInfo(applicationContext, def, 0f)) }
        return try {
            LlmModelDownloadManager.runDownload(applicationContext, id) { p ->
                runCatching { LlmModelDownloadNotifications.update(applicationContext, def, p) }
            }
            LlmModelDownloadNotifications.complete(applicationContext, def)
            Result.success()
        } catch (t: Throwable) {
            LlmModelDownloadNotifications.fail(applicationContext, def)
            Result.failure()
        }
    }
}
