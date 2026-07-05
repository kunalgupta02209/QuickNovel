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
        if (!LlmModels.isReady(context, def)) {
            android.util.Log.e("LlmFixFlow", "ensureEngine: model $modelId not ready on disk"); return null
        }
        val file = LlmModels.modelFile(context, def)
        android.util.Log.i("LlmFixFlow", "ensureEngine: loading $modelId (${file.length() / (1024 * 1024)} MB)…")
        val e = LlamaCppProseFixer(context.applicationContext, file)
        return if (e.load()) {
            android.util.Log.i("LlmFixFlow", "ensureEngine: $modelId loaded")
            engine = e; engineModelId = modelId; e
        } else {
            android.util.Log.e("LlmFixFlow", "ensureEngine: load() FAILED for $modelId")
            e.close(); null
        }
    }

    fun releaseEngine() {
        engine?.close(); engine = null; engineModelId = null
    }

    /** Interrupt the in-flight generation (used to cancel an on-the-spot fix). */
    fun stopGeneration() {
        runCatching { engine?.stop() }
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
        cfg: FixConfig,
        onProgress: ((chunkIndex: Int, chunkCount: Int, token: String) -> Unit)? = null,
    ): String? {
        FixedTextCache.load(context, bookId, cfg.modelId, cfg.promptVersion, chapterIndex)?.let { return it }
        if (rawText.isBlank()) return null
        val e = ensureEngine(context, cfg.modelId) ?: return null
        return try {
            val memory = CharacterGraph.memoryBlock(bookId, chapterIndex) // cross-chapter consistency
            // A full chapter is far larger than the context window, so rewrite it in paragraph-sized
            // chunks (prompt + chunk + generated output must all fit in LlmModels.CTX_LEN) and stitch.
            val chunks = splitIntoChunks(rawText, CHUNK_CHARS)
            android.util.Log.i("LlmFixFlow", "fixChapter ch$chapterIndex: ${rawText.length} chars -> ${chunks.size} chunk(s)")
            val sb = StringBuilder()
            for ((i, chunk) in chunks.withIndex()) {
                if (chunk.isBlank()) continue
                val prompt = ProseFixPrompt.buildFixPrompt(
                    systemPrompt = cfg.systemPrompt,
                    previousChapters = if (i == 0) previousChapters.takeLast(800) else "",
                    characterMemory = memory,
                    chapterText = chunk,
                    supertonic = cfg.supertonic,
                )
                val out = cleanOutput(e.generate(prompt) { tok -> onProgress?.invoke(i, chunks.size, tok) })
                android.util.Log.i("LlmFixFlow", "  chunk ${i + 1}/${chunks.size}: in=${chunk.length} out=${out.length}")
                if (out.isNotBlank()) sb.append(out).append("\n\n")
            }
            val full = dedupeRepetition(sb.toString())
            if (full.isBlank()) {
                android.util.Log.e("LlmFixFlow", "fixChapter: all chunks blank for ch $chapterIndex")
                null
            } else {
                FixedTextCache.save(context, bookId, cfg.modelId, cfg.promptVersion, chapterIndex, full)
                full
            }
        } catch (t: Throwable) {
            android.util.Log.e("LlmFixFlow", "fixChapter: exception", t)
            logError(t); null
        }
    }

    /** Target chunk size in characters (~700-900 tokens) so prompt + chunk + output fit CTX_LEN. */
    private const val CHUNK_CHARS = 2800

    /** Split chapter text into <= [maxChars] chunks on paragraph boundaries (hard-splitting any giant
     *  paragraph), so each chunk plus its prompt and rewrite fit the model's context window. */
    private fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        val paras = text.split(Regex("\n\\s*\n"))
        val chunks = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) { chunks.add(cur.toString().trim()); cur.setLength(0) } }
        for (p in paras) {
            when {
                p.length > maxChars -> {
                    flush()
                    p.chunked(maxChars).forEach { chunks.add(it.trim()) }
                }
                cur.length + p.length > maxChars -> {
                    flush(); cur.append(p).append("\n\n")
                }
                else -> cur.append(p).append("\n\n")
            }
        }
        flush()
        return chunks.filter { it.isNotBlank() }
    }

    /**
     * Collapse the repetition small models fall into (the binding exposes no repeat-penalty): drop
     * near-duplicate paragraphs and consecutive duplicate sentences. PRESERVES paragraph breaks so the
     * fixed text stays comparable to the original.
     */
    private fun dedupeRepetition(text: String): String {
        val paras = text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val keptParas = ArrayList<String>()
        for (p in paras) {
            if (keptParas.takeLast(3).any { me.xdrop.fuzzywuzzy.FuzzySearch.ratio(it, p) >= 90 }) continue
            val sents = p.split(Regex("(?<=[.!?\"”])\\s+"))
            val ks = ArrayList<String>()
            for (s in sents) {
                val t = s.trim()
                if (t.isEmpty()) continue
                if (ks.isNotEmpty() && me.xdrop.fuzzywuzzy.FuzzySearch.ratio(ks.last(), t) >= 92) continue
                ks.add(t)
            }
            if (ks.isNotEmpty()) keptParas.add(ks.joinToString(" "))
        }
        return keptParas.joinToString("\n\n").trim()
    }

    /**
     * Extract characters from a chapter's fixed text and merge them into the book's memory graph.
     * A second LLM pass, so only the background worker runs it (never the on-the-spot fix).
     */
    suspend fun extractCharacters(context: Context, bookId: String, chapterIndex: Int, fixedText: String, cfg: FixConfig) {
        if (fixedText.isBlank()) return
        val e = ensureEngine(context, cfg.modelId) ?: return
        try {
            val prompt = ProseFixPrompt.buildExtractPrompt(CharacterGraph.knownNames(bookId).take(40), fixedText.take(6000))
            val extracted = CharacterGraph.parseExtraction(e.generate(prompt))
            CharacterGraph.merge(bookId, extracted, chapterIndex)
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /** Summarize the novel's setting (genre / world / tone) from a chapter and store it on the graph. */
    suspend fun extractSetting(context: Context, bookId: String, chapterText: String, cfg: FixConfig) {
        if (chapterText.isBlank()) return
        val e = ensureEngine(context, cfg.modelId) ?: return
        try {
            val out = cleanOutput(e.generate(ProseFixPrompt.buildSettingPrompt(chapterText)))
            if (out.isNotBlank()) CharacterGraph.saveSetting(bookId, out)
        } catch (t: Throwable) {
            logError(t)
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
