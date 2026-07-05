package com.lagradost.quicknovel.llm

import android.content.Context
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.tts.ModelDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Download-on-first-run manager for the LLM fixer models, reusing [ModelDownloadState] and mirroring
 * the TTS [com.lagradost.quicknovel.tts.ModelDownloadManager]. Readiness is always re-derived from
 * disk so it survives process death.
 */
class LlmModelDownloadManager(context: Context) {
    private val appContext = context.applicationContext

    private val _states = MutableStateFlow(initialStates())
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    private fun initialStates(): Map<String, ModelDownloadState> =
        LlmModels.ALL.associate { def ->
            def.id to if (LlmModels.isReady(appContext, def)) ModelDownloadState.Ready else ModelDownloadState.Idle
        }

    fun isReady(id: String?): Boolean = LlmModels.isReady(appContext, id)

    suspend fun download(id: String) = withContext(Dispatchers.IO) {
        val def = LlmModels.ALL.firstOrNull { it.id == id } ?: return@withContext
        _states.update { it + (id to ModelDownloadState.Downloading(0f)) }
        try {
            LlmModels.downloadModel(appContext, def) { p ->
                _states.update { it + (id to ModelDownloadState.Downloading(p.coerceIn(0f, 1f))) }
            }
            _states.update { it + (id to ModelDownloadState.Ready) }
        } catch (t: Throwable) {
            logError(t)
            _states.update { it + (id to ModelDownloadState.Error(t.message ?: "Download failed")) }
        }
    }

    fun delete(id: String) {
        val def = LlmModels.ALL.firstOrNull { it.id == id } ?: return
        LlmModels.delete(appContext, def)
        _states.update { it + (id to ModelDownloadState.Idle) }
    }
}
