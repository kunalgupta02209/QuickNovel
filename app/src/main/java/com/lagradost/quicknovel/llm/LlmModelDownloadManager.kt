package com.lagradost.quicknovel.llm

import android.content.Context
import com.lagradost.quicknovel.LlmModelDownloadWorker
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.tts.ModelDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * GLOBAL (singleton) download state for the LLM fixer models. Unlike a per-activity manager, this lives
 * as long as the process so a background [LlmModelDownloadWorker] and any observing UI share one source
 * of truth — the ~0.4–2 GB download survives the reader being closed. Readiness is always re-derived
 * from disk (`.ready` markers) so it is correct across process death.
 */
object LlmModelDownloadManager {
    private val _states = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    /** Recompute each model's state from disk (Ready if downloaded), preserving in-flight downloads. */
    fun refreshFromDisk(context: Context) {
        val ctx = context.applicationContext
        _states.update { cur ->
            LlmModels.ALL.associate { def ->
                val existing = cur[def.id]
                def.id to when {
                    LlmModels.isReady(ctx, def) -> ModelDownloadState.Ready
                    existing is ModelDownloadState.Downloading -> existing
                    else -> ModelDownloadState.Idle
                }
            }
        }
    }

    fun isReady(context: Context, id: String?): Boolean = LlmModels.isReady(context.applicationContext, id)

    /** Enqueue a background WorkManager download (foreground-service notification, survives the activity). */
    fun startBackgroundDownload(context: Context, id: String) {
        if (LlmModels.isReady(context, id)) return
        _states.update { it + (id to ModelDownloadState.Downloading(0f)) }
        LlmModelDownloadWorker.enqueue(context, id)
    }

    /** Blocking download, run by the worker; drives [states] + the caller's notification callback. */
    suspend fun runDownload(context: Context, id: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val def = LlmModels.ALL.firstOrNull { it.id == id } ?: return@withContext
        _states.update { it + (id to ModelDownloadState.Downloading(0f)) }
        try {
            LlmModels.downloadModel(context.applicationContext, def) { p ->
                val clamped = p.coerceIn(0f, 1f)
                _states.update { it + (id to ModelDownloadState.Downloading(clamped)) }
                onProgress(clamped)
            }
            _states.update { it + (id to ModelDownloadState.Ready) }
        } catch (t: Throwable) {
            logError(t)
            _states.update { it + (id to ModelDownloadState.Error(t.message ?: "Download failed")) }
            throw t
        }
    }

    fun delete(context: Context, id: String) {
        val def = LlmModels.ALL.firstOrNull { it.id == id } ?: return
        LlmModels.delete(context.applicationContext, def)
        _states.update { it + (id to ModelDownloadState.Idle) }
    }
}
