package com.lagradost.quicknovel

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.tts.RemoteTtsManager
import com.lagradost.quicknovel.tts.RemoteTtsNotifications

/**
 * WorkManager entry point for the server-offloaded TTS generation. A SEPARATE unique-work chain
 * ("TTS_REMOTE") from on-device pre-gen ("TTS_PREGEN") so a cloud job and a local job never block
 * each other. The request is Jackson-serialized INTO the worker's input Data (it is small metadata,
 * far under the 10KB cap) so it survives process death — a re-run after the app is killed resumes
 * via the persisted server jobId + on-disk cache instead of failing like the old static-map payload.
 * Requires a network connection (the whole job is server I/O).
 */
class RemoteTtsWorkManager(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val DATA_JSON = "req_json"
        const val ID_REMOTE = "TTS_REMOTE"

        fun enqueue(context: Context, req: RemoteTtsManager.RemoteTtsRequest) {
            DownloadFileWorkManager.getWorkerManager(context).enqueueUniqueWork(
                ID_REMOTE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequest.Builder(RemoteTtsWorkManager::class.java)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setInputData(
                        Data.Builder()
                            .putString(DATA_JSON, DataStore.mapper.writeValueAsString(req))
                            .build()
                    )
                    .build()
            )
        }
    }

    override suspend fun doWork(): Result {
        val req = runCatching {
            DataStore.mapper.readValue<RemoteTtsManager.RemoteTtsRequest>(
                inputData.getString(DATA_JSON) ?: return Result.failure()
            )
        }.getOrNull() ?: return Result.failure()
        runCatching { setForeground(RemoteTtsNotifications.foregroundInfo(applicationContext, req)) }
        RemoteTtsManager.runJob(applicationContext, req)
        return Result.success()
    }
}
