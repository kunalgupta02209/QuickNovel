package com.lagradost.quicknovel.tts

import android.content.Context
import com.lagradost.quicknovel.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** UI-facing state for one on-device model. */
sealed class ModelDownloadState {
    data object Idle : ModelDownloadState()
    data class Downloading(val progress: Float) : ModelDownloadState() // 0f..1f
    data object Ready : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

/**
 * Orchestrates download-on-first-run of the on-device TTS models into `filesDir` and exposes their
 * state as an observable [StateFlow]. Thin wrapper over [TtsModels]; readiness is always re-derived
 * from disk so it survives process death.
 */
class ModelDownloadManager(context: Context) {
    // Hold the application context to avoid leaking an Activity.
    private val appContext = context.applicationContext

    private val _states = MutableStateFlow(initialStates())
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    private fun initialStates(): Map<String, ModelDownloadState> =
        TtsModels.ALL.associate { def ->
            def.id to if (TtsModels.isReady(appContext, def)) ModelDownloadState.Ready else ModelDownloadState.Idle
        }

    fun isReady(id: String?): Boolean {
        val def = TtsModels.ALL.firstOrNull { it.id == id } ?: return false
        return TtsModels.isReady(appContext, def)
    }

    fun readyModels(): List<TtsModels.ModelDef> =
        TtsModels.ALL.filter { it.supported && TtsModels.isReady(appContext, it) }

    /**
     * Download + extract a model. Heavy (network + pure-Java bzip2 extraction of 30–160 MB) — always
     * call off the main thread; this hops to [Dispatchers.IO] regardless. Progress is emitted on [states].
     */
    suspend fun download(id: String) = withContext(Dispatchers.IO) {
        val def = TtsModels.ALL.firstOrNull { it.id == id && it.supported } ?: return@withContext
        _states.update { it + (id to ModelDownloadState.Downloading(0f)) }
        try {
            TtsModels.downloadAndExtract(appContext, def) { p ->
                _states.update { it + (id to ModelDownloadState.Downloading(p.coerceIn(0f, 1f))) }
            }
            _states.update { it + (id to ModelDownloadState.Ready) }
        } catch (t: Throwable) {
            logError(t)
            _states.update { it + (id to ModelDownloadState.Error(t.message ?: "Download failed")) }
        }
    }

    fun delete(id: String) {
        val def = TtsModels.ALL.firstOrNull { it.id == id } ?: return
        TtsModels.modelDir(appContext, def).deleteRecursively()
        _states.update { it + (id to ModelDownloadState.Idle) }
    }
}
