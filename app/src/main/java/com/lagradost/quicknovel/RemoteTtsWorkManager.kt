package com.lagradost.quicknovel

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import com.lagradost.quicknovel.tts.RemoteTtsManager
import com.lagradost.quicknovel.tts.RemoteTtsNotifications

/**
 * WorkManager entry point for the server-offloaded TTS generation. A SEPARATE unique-work chain
 * ("TTS_REMOTE") from on-device pre-gen ("TTS_PREGEN") so a cloud job and a local job never block
 * each other. Uses the same static-map indirection as [TtsPregenWorkManager] to dodge the Data cap,
 * and setForeground so the OS keeps the poll/download alive.
 */
class RemoteTtsWorkManager(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val ID = "id"
        const val DATA = "data"
        const val ID_REMOTE = "TTS_REMOTE"

        private var workNumber = 0
        private val workData = HashMap<Int, Any>()
        private fun insertWork(d: Any): Int = synchronized(workData) { workNumber += 1; workData[workNumber] = d; workNumber }
        private fun popWork(k: Int): Any? = synchronized(workData) { workData.remove(k) }

        fun enqueue(context: Context, req: RemoteTtsManager.RemoteTtsRequest) {
            DownloadFileWorkManager.getWorkerManager(context).enqueueUniqueWork(
                ID_REMOTE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequest.Builder(RemoteTtsWorkManager::class.java)
                    .setInputData(
                        Data.Builder().putString(ID, ID_REMOTE).putInt(DATA, insertWork(req)).build()
                    )
                    .build()
            )
        }
    }

    override suspend fun doWork(): Result {
        val req = popWork(inputData.getInt(DATA, -1)) as? RemoteTtsManager.RemoteTtsRequest
            ?: return Result.failure()
        runCatching { setForeground(RemoteTtsNotifications.foregroundInfo(applicationContext, req)) }
        RemoteTtsManager.runJob(applicationContext, req)
        return Result.success()
    }
}
