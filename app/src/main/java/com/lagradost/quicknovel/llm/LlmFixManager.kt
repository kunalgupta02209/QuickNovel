package com.lagradost.quicknovel.llm

import android.content.Context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2Helper.readDownloadedChapter
import com.lagradost.quicknovel.DownloadActionType
import com.lagradost.quicknovel.DownloadProgressState
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.EPUB_AUTHOR_NOTES
import com.lagradost.quicknovel.LLM_FIX_FOLDER
import com.lagradost.quicknovel.LlmFixWorkManager
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.tts.TtsAudioCache
import com.lagradost.quicknovel.util.Event
import kotlinx.coroutines.delay

/**
 * Background chapter-rewrite orchestrator — a text-domain twin of [com.lagradost.quicknovel.tts
 * .TtsPregenManager]. Keyed by "<bookId>|<modelId>|<promptVersion>", it runs the [ChapterFixer] over a
 * chapter range, reading each downloaded chapter offline, feeding previous chapters + character memory
 * as context, and writing the fixed text to [FixedTextCache] (the same cache the reader/TTS read).
 * Cooperative pause/resume/stop; progress + records published via [Event]s.
 */
object LlmFixManager {

    data class FixRequest(
        val bookId: Int,
        val apiName: String,
        val author: String?,
        val name: String,
        val posterUrl: String?,
        val modelId: String,
        val promptVersion: Int,
        val systemPrompt: String,
        val prevChapters: Int,
        val rangeStart: Int,
        val rangeEnd: Int,
    ) {
        val key: String get() = "$bookId|$modelId|$promptVersion"
        val notifId: Int get() = key.hashCode() xor 0x99150000.toInt()
        val bookIdStr: String get() = "b$bookId"
    }

    data class LlmFixRecord(
        val bookId: Int = 0,
        val apiName: String = "",
        val author: String? = null,
        val name: String = "",
        val posterUrl: String? = null,
        val modelId: String = "",
        val promptVersion: Int = 1,
        val rangeStart: Int = 0,
        val rangeEnd: Int = 0,
        val fixedChapters: Int = 0,
        val totalChapters: Int = 0,
        val bytes: Long = 0,
        val lastUpdated: Long = 0,
    ) {
        val key: String get() = "$bookId|$modelId|$promptVersion"
    }

    private val lock = Any()
    val progress = HashMap<String, DownloadProgressState>()
    val records = HashMap<String, LlmFixRecord>()
    private val currentJobs = HashSet<String>()
    private val pendingAction = HashMap<String, DownloadActionType>()

    val progressChanged = Event<Pair<String, DownloadProgressState>>()
    val recordChanged = Event<Pair<String, LlmFixRecord>>()
    val removed = Event<String>()
    val refreshed = Event<Int>()

    fun addPendingAction(key: String, action: DownloadActionType) = synchronized(lock) {
        if (currentJobs.contains(key)) pendingAction[key] = action
    }

    private fun consumeAction(key: String): DownloadActionType? = synchronized(lock) { pendingAction.remove(key) }
    private fun peekStop(key: String): Boolean = synchronized(lock) { pendingAction[key] == DownloadActionType.Stop }
    fun isRunning(key: String): Boolean = synchronized(lock) { currentJobs.contains(key) }

    fun enqueue(context: Context, req: FixRequest) {
        emit(context.applicationContext, req, DownloadState.IsPending, 0, req.rangeEnd - req.rangeStart + 1)
        LlmFixWorkManager.enqueue(context, req)
    }

    suspend fun runJob(context: Context, req: FixRequest) {
        val key = req.key
        synchronized(lock) { if (!currentJobs.add(key)) return }
        val ctx = context.applicationContext
        val authorNotes = getKey<Boolean>(EPUB_AUTHOR_NOTES, true) ?: true
        val total = req.rangeEnd - req.rangeStart + 1
        var done = 0
        var finalState = DownloadState.IsDone
        val cfg = ChapterFixer.FixConfig(req.modelId, req.promptVersion, req.systemPrompt)
        try {
            if (!LlmModels.isReady(ctx, LlmModels.byId(req.modelId))) {
                finalState = DownloadState.IsFailed
            } else {
                emit(ctx, req, DownloadState.IsDownloading, 0, total)
                loop@ for (index in req.rangeStart..req.rangeEnd) {
                    if (!waitIfPaused(ctx, req, done, total)) { finalState = DownloadState.IsStopped; break@loop }
                    if (!FixedTextCache.isFixed(ctx, req.bookIdStr, req.modelId, req.promptVersion, index)) {
                        val raw = readRawChapter(ctx, req, index, authorNotes)
                        if (raw != null) {
                            val prev = buildPrevContext(ctx, req, index, authorNotes)
                            val out = ChapterFixer.fixChapter(ctx, req.bookIdStr, index, raw, prev, cfg)
                            if (out == null && peekStop(key)) { finalState = DownloadState.IsStopped; break@loop }
                            // Populate the character memory graph from the fixed text (background only).
                            if (out != null) ChapterFixer.extractCharacters(ctx, req.bookIdStr, index, out, cfg)
                        }
                    }
                    done++
                    emit(ctx, req, DownloadState.IsDownloading, done, total)
                }
            }
        } catch (t: Throwable) {
            logError(t); finalState = DownloadState.IsFailed
        } finally {
            emit(ctx, req, finalState, done, total)
            synchronized(lock) { currentJobs.remove(key); pendingAction.remove(key) }
        }
    }

    private fun readRawChapter(ctx: Context, req: FixRequest, index: Int, authorNotes: Boolean): String? {
        val loaded = ctx.readDownloadedChapter(req.apiName, req.author, req.name, index) ?: return null
        return TTSHelper.preParseHtml(loaded.html, authorNotes).takeIf { it.isNotBlank() }
    }

    private fun buildPrevContext(ctx: Context, req: FixRequest, index: Int, authorNotes: Boolean): String {
        if (req.prevChapters <= 0) return ""
        val sb = StringBuilder()
        for (i in maxOf(0, index - req.prevChapters) until index) {
            readRawChapter(ctx, req, i, authorNotes)?.let { sb.append(it.takeLast(1500)).append("\n\n") }
        }
        return sb.toString().takeLast(3000)
    }

    private suspend fun waitIfPaused(ctx: Context, req: FixRequest, done: Int, total: Int): Boolean {
        when (consumeAction(req.key)) {
            DownloadActionType.Stop -> return false
            DownloadActionType.Pause -> {
                emit(ctx, req, DownloadState.IsPaused, done, total)
                while (true) {
                    delay(300)
                    when (consumeAction(req.key)) {
                        DownloadActionType.Resume -> { emit(ctx, req, DownloadState.IsDownloading, done, total); return true }
                        DownloadActionType.Stop -> return false
                        else -> {}
                    }
                }
            }
            else -> {}
        }
        return true
    }

    private fun emit(ctx: Context, req: FixRequest, state: DownloadState, done: Int, total: Int) {
        val key = req.key
        val p = DownloadProgressState(state, done.toLong(), done.toLong(), total.toLong(), System.currentTimeMillis(), null)
        synchronized(lock) { progress[key] = p }
        progressChanged.invoke(key to p)
        val bytes = FixedTextCache.bytes(ctx, req.bookIdStr, req.modelId, req.promptVersion)
        val rec = LlmFixRecord(
            req.bookId, req.apiName, req.author, req.name, req.posterUrl, req.modelId, req.promptVersion,
            req.rangeStart, req.rangeEnd, done, total, bytes, System.currentTimeMillis(),
        )
        synchronized(lock) { records[key] = rec }
        runCatching { setKey(LLM_FIX_FOLDER, key, rec) }
        recordChanged.invoke(key to rec)
        runCatching { LlmFixNotifications.update(ctx, req, state, done, total) }
    }

    fun reconcileFromDisk(context: Context) {
        val ctx = context.applicationContext
        val keys = getKeys(LLM_FIX_FOLDER) ?: emptyList()
        synchronized(lock) { records.clear() }
        for (fullKey in keys) {
            val rec = runCatching { getKey<LlmFixRecord>(fullKey) }.getOrNull() ?: continue
            val bytes = FixedTextCache.bytes(ctx, "b${rec.bookId}", rec.modelId, rec.promptVersion)
            val fixed = FixedTextCache.fixedChapterCount(ctx, "b${rec.bookId}", rec.modelId, rec.promptVersion)
            synchronized(lock) { records[rec.key] = rec.copy(bytes = bytes, fixedChapters = maxOf(rec.fixedChapters, fixed)) }
        }
        refreshed.invoke(0)
    }

    fun records(): List<LlmFixRecord> = synchronized(lock) { records.values.sortedBy { it.name } }

    fun deleteAll(context: Context, key: String) {
        val rec = synchronized(lock) { records[key] } ?: return
        FixedTextCache.deleteBook(context.applicationContext, "b${rec.bookId}")
        synchronized(lock) { records.remove(key); progress.remove(key) }
        runCatching { removeKey(LLM_FIX_FOLDER, key) }
        removed.invoke(key)
    }
}
