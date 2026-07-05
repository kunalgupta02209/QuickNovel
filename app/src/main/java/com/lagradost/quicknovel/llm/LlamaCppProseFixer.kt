package com.lagradost.quicknovel.llm

import android.content.Context
import android.net.Uri
import androidx.annotation.RequiresApi
import com.lagradost.quicknovel.mvvm.logError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File

/**
 * llama.cpp-backed [ProseFixerEngine] (Qwen2.5 GGUF via the kotlinllamacpp binding). The binding is a
 * fire-and-forget API that streams tokens onto a shared [LlamaHelper.LLMEvent] flow, so both [load]
 * and [generate] kick off the native call inside `onSubscription` (guaranteeing the collector is
 * subscribed before the first event) and terminate on the flow's Loaded / Done / Error markers.
 *
 * Requires API 24 (the binding's minSdk); callers gate on Build.VERSION.SDK_INT >= N. All work is on
 * Dispatchers.IO since the native calls are blocking C++.
 */
@RequiresApi(24)
class LlamaCppProseFixer(
    context: Context,
    private val modelFile: File,
) : ProseFixerEngine {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val events = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 512)
    private val helper = LlamaHelper(appContext.contentResolver, scope, events)

    @Volatile
    private var loaded = false
    override val isLoaded: Boolean get() = loaded

    override suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        if (loaded) return@withContext true
        val terminal = try {
            withTimeoutOrNull(180_000) {
                events
                    .onSubscription {
                        runCatching {
                            // filesDir models are app-private, so a file:// URI opens fine (no scoped storage).
                            helper.load(Uri.fromFile(modelFile).toString(), LlmModels.CTX_LEN, null) { }
                        }.onFailure { logError(it) }
                    }
                    .first { it is LlamaHelper.LLMEvent.Loaded || it is LlamaHelper.LLMEvent.Error }
            }
        } catch (t: Throwable) {
            logError(t); null
        }
        loaded = terminal is LlamaHelper.LLMEvent.Loaded
        loaded
    }

    override suspend fun generate(prompt: String, onToken: ((String) -> Unit)?): String = withContext(Dispatchers.IO) {
        if (!loaded) return@withContext ""
        val sb = StringBuilder()
        try {
            events
                .onSubscription {
                    // 3rd arg = emit_partial_completion: true streams each token as an LLMEvent.Ongoing
                    // (false runs generation but emits nothing per-token — verified via RNLlama logs).
                    runCatching { helper.predict(prompt, null, true) }.onFailure { logError(it) }
                }
                .takeWhile { it !is LlamaHelper.LLMEvent.Done && it !is LlamaHelper.LLMEvent.Error }
                .collect { e -> if (e is LlamaHelper.LLMEvent.Ongoing) { sb.append(e.word); onToken?.invoke(e.word) } }
        } catch (t: Throwable) {
            logError(t)
        }
        sb.toString().trim()
    }

    /** Cooperative cancel of an in-flight generation (used by the background worker's stop/pause). */
    fun stop() {
        runCatching { helper.stopPrediction() }
    }

    override fun close() {
        runCatching { helper.abort() }
        runCatching { helper.release() }
        runCatching { scope.coroutineContext[Job]?.cancel() }
        loaded = false
    }
}
