package com.lagradost.quicknovel

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import com.lagradost.quicknovel.tts.TtsPregenManager
import com.lagradost.quicknovel.tts.TtsPregenNotifications

/**
 * WorkManager entry point for background TTS pre-generation. A separate worker from
 * [DownloadFileWorkManager]; uses the same static-map indirection to dodge the 10 240-byte Data cap,
 * and calls setForeground so the OS keeps the job alive (proper foreground-service compliance).
 *
 * All pregen jobs run SERIALLY on one unique-work chain ("TTS_PREGEN", APPEND) so two heavy
 * OfflineTts instances never fight for CPU. Duplicate (book,model,sid) requests are additionally
 * deduped by TtsPregenManager.currentJobs + skip-existing-files.
 */
class TtsPregenWorkManager(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val ID = "id"
        const val DATA = "data"
        const val ID_PREGEN = "TTS_PREGEN"

        private var workNumber = 0
        private val workData = HashMap<Int, Any>()
        private fun insertWork(d: Any): Int = synchronized(workData) { workNumber += 1; workData[workNumber] = d; workNumber }
        private fun popWork(k: Int): Any? = synchronized(workData) { workData.remove(k) }

        fun enqueue(context: Context, req: TtsPregenManager.PregenRequest) {
            DownloadFileWorkManager.getWorkerManager(context).enqueueUniqueWork(
                ID_PREGEN,
                ExistingWorkPolicy.APPEND,
                OneTimeWorkRequest.Builder(TtsPregenWorkManager::class.java)
                    .setInputData(
                        Data.Builder().putString(ID, ID_PREGEN).putInt(DATA, insertWork(req)).build()
                    )
                    .build()
            )
        }
    }

    override suspend fun doWork(): Result {
        // On process-death re-run the static payload is gone -> fail; resumability comes from the
        // on-disk .done markers + persisted record, so a re-enqueue resumes cleanly (files skipped).
        val req = popWork(inputData.getInt(DATA, -1)) as? TtsPregenManager.PregenRequest
            ?: return Result.failure()
        runCatching { setForeground(TtsPregenNotifications.foregroundInfo(applicationContext, req)) }
        TtsPregenManager.runJob(applicationContext, req)
        return Result.success()
    }
}
