package com.lagradost.quicknovel.llm

import android.content.Context
import com.lagradost.quicknovel.mvvm.logError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates the on-device prose fix: hold ONE warm [LlamaCppProseFixer] (loading a GGUF is heavy),
 * build the ChatML prompt from the editable system prompt + previous-chapter context + character
 * memory, run generation, clean the output, and write it through [FixedTextCache]. Both the live
 * reader and the background worker go through here, so a chapter is only ever fixed once per
 * (model, promptVersion) and both paths read identical text.
 */
object ChapterFixer {
    @Volatile private var engine: LlamaCppProseFixer? = null
    @Volatile private var engineModelId: String? = null
    private val engineMutex = Mutex()

    /** Load (or reuse) the engine for [modelId]. Null if the model isn't downloaded or fails to load. */
    suspend fun ensureEngine(context: Context, modelId: String): LlamaCppProseFixer? = engineMutex.withLock {
        val cur = engine
        if (cur != null && cur.isLoaded && engineModelId == modelId) return cur
        cur?.close(); engine = null; engineModelId = null
        val def = LlmModels.byId(modelId)
        if (!LlmModels.isReady(context, def)) return null
        val e = LlamaCppProseFixer(context.applicationContext, LlmModels.modelFile(context, def))
        return if (e.load()) {
            engine = e; engineModelId = modelId; e
        } else {
            e.close(); null
        }
    }

    fun releaseEngine() {
        engine?.close(); engine = null; engineModelId = null
    }

    data class FixConfig(
        val modelId: String,
        val promptVersion: Int,
        val systemPrompt: String,      // "" -> built-in default
        val supertonic: Boolean = false,
    )

    /**
     * Return the fixed text for one chapter, generating + caching it if needed. A cache hit is a ~ms
     * disk read. Returns null if the model isn't ready or generation fails (caller falls back to raw).
     */
    suspend fun fixChapter(
        context: Context,
        bookId: String,
        chapterIndex: Int,
        rawText: String,
        previousChapters: String,
        characterMemory: String,
        cfg: FixConfig,
    ): String? {
        FixedTextCache.load(context, bookId, cfg.modelId, cfg.promptVersion, chapterIndex)?.let { return it }
        if (rawText.isBlank()) return null
        val e = ensureEngine(context, cfg.modelId) ?: return null
        return try {
            val prompt = ProseFixPrompt.buildFixPrompt(
                systemPrompt = cfg.systemPrompt,
                previousChapters = previousChapters,
                characterMemory = characterMemory,
                chapterText = rawText,
                supertonic = cfg.supertonic,
            )
            val out = cleanOutput(e.generate(prompt))
            if (out.isBlank()) null
            else {
                FixedTextCache.save(context, bookId, cfg.modelId, cfg.promptVersion, chapterIndex, out)
                out
            }
        } catch (t: Throwable) {
            logError(t); null
        }
    }

    /** Strip anything a small model might leak around the prose (chat markers, code fences, a leading label). */
    private fun cleanOutput(raw: String): String {
        var s = raw.trim()
        // Drop any stray ChatML / assistant markers.
        s = s.replace("<|im_end|>", "").replace("<|im_start|>", "")
        s = s.removePrefix("assistant").trim()
        // Strip a wrapping ``` code fence if the model added one.
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', "").substringBeforeLast("```").trim()
        }
        return s.trim()
    }
}
