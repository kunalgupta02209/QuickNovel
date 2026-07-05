package com.lagradost.quicknovel

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import com.lagradost.quicknovel.llm.LlmFixManager
import com.lagradost.quicknovel.llm.LlmFixNotifications

/** Serial background worker for LLM chapter rewriting (twin of [TtsPregenWorkManager]). */
class LlmFixWorkManager(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val ID = "id"
        const val DATA = "data"
        const val ID_FIX = "LLM_FIX"

        private var workNumber = 0
        private val workData = HashMap<Int, Any>()
        private fun insertWork(d: Any): Int = synchronized(workData) { workNumber += 1; workData[workNumber] = d; workNumber }
        private fun popWork(k: Int): Any? = synchronized(workData) { workData.remove(k) }

        fun enqueue(context: Context, req: LlmFixManager.FixRequest) {
            DownloadFileWorkManager.getWorkerManager(context).enqueueUniqueWork(
                ID_FIX,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequest.Builder(LlmFixWorkManager::class.java)
                    .setInputData(Data.Builder().putString(ID, ID_FIX).putInt(DATA, insertWork(req)).build())
                    .build(),
            )
        }
    }

    override suspend fun doWork(): Result {
        val req = popWork(inputData.getInt(DATA, -1)) as? LlmFixManager.FixRequest ?: return Result.failure()
        runCatching { setForeground(LlmFixNotifications.foregroundInfo(applicationContext, req)) }
        LlmFixManager.runJob(applicationContext, req)
        return Result.success()
    }
}
