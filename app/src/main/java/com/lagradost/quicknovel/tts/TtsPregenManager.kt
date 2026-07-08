package com.lagradost.quicknovel.tts

import android.content.Context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.DownloadActionType
import com.lagradost.quicknovel.DownloadProgressState
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.EPUB_AUTHOR_NOTES
import com.lagradost.quicknovel.EPUB_CURRENT_POSITION
import com.lagradost.quicknovel.TTS_PREGEN_FOLDER
import com.lagradost.quicknovel.TtsPregenWorkManager
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.util.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestration singleton for background on-device TTS pre-generation — a trimmed clone of
 * BookDownloader2's state machine, keyed by a composite String "<bookId>|<modelId>|<sid>" instead of
 * an Int. Holds in-memory progress + persisted records, publishes changes via [Event]s (the pattern
 * DownloadViewModel subscribes to), and runs the actual synthesis loop in [runJob] (called from the
 * WorkManager worker on the application context). Cancellation (pause/resume/stop) is cooperative.
 */
object TtsPregenManager {

    /** WorkManager payload (passed via the worker static map, never serialized into Data). */
    data class PregenRequest(
        val bookId: Int,
        val apiName: String,
        val author: String?,
        val name: String,
        val posterUrl: String?,
        val modelId: String,
        val sid: Int,
        val rangeStart: Int, // inclusive 0-based chapter index
        val rangeEnd: Int,   // inclusive 0-based chapter index
    ) {
        val key: String get() = "$bookId|$modelId|$sid"
        // XOR-namespaced so a pregen notification never collides with a download notification (bare bookId).
        val notifId: Int get() = key.hashCode() xor 0x77150000.toInt()
    }

    /** Persisted record (value of [TTS_PREGEN_FOLDER], sub-keyed by [key]). Jackson-serializable. */
    data class TtsPregenRecord(
        val bookId: Int = 0,
        val apiName: String = "",
        val author: String? = null,
        val name: String = "",
        val posterUrl: String? = null,
        val modelId: String = "",
        val sid: Int = 0,
        val rangeStart: Int = 0,
        val rangeEnd: Int = 0,
        val generatedChapters: Int = 0,
        val totalChapters: Int = 0,
        val bytes: Long = 0,
        val lastUpdated: Long = 0,
    ) {
        val key: String get() = "$bookId|$modelId|$sid"
    }

    private val lock = Any()
    val pregenProgress = HashMap<String, DownloadProgressState>()
    val pregenRecords = HashMap<String, TtsPregenRecord>()
    private val currentJobs = HashSet<String>()
    private val pendingAction = HashMap<String, DownloadActionType>()

    val pregenProgressChanged = Event<Pair<String, DownloadProgressState>>()
    val pregenRecordChanged = Event<Pair<String, TtsPregenRecord>>()
    val pregenRemoved = Event<String>()
    val pregenRefreshed = Event<Int>()

    // ---- external control (notification buttons / management UI) ----

    fun addPendingAction(key: String, action: DownloadActionType) {
        synchronized(lock) {
            if (!currentJobs.contains(key)) return
            pendingAction[key] = action
        }
    }

    private fun consumeAction(key: String): DownloadActionType? =
        synchronized(lock) { pendingAction.remove(key) }

    private fun peekStop(key: String): Boolean =
        synchronized(lock) { pendingAction[key] == DownloadActionType.Stop }

    fun isRunning(key: String): Boolean = synchronized(lock) { currentJobs.contains(key) }

    // ---- enqueue ----

    /** Seed a pending record + row and hand the request to the serial WorkManager chain. */
    fun enqueue(context: Context, req: PregenRequest) {
        val total = req.rangeEnd - req.rangeStart + 1
        emit(context.applicationContext, req, DownloadState.IsPending, 0, total)
        TtsPregenWorkManager.enqueue(context, req)
    }

    // ---- the job ----

    suspend fun runJob(context: Context, req: PregenRequest): Unit = coroutineScope {
        val key = req.key
        synchronized(lock) { if (!currentJobs.add(key)) return@coroutineScope }

        val ctx = context.applicationContext
        val bookIdStr = "b${req.bookId}"
        val def = TtsModels.byId(req.modelId)
        val authorNotes = getKey<Boolean>(EPUB_AUTHOR_NOTES, true) ?: true
        val total = req.rangeEnd - req.rangeStart + 1
        val done = AtomicInteger(0)
        val stopped = AtomicBoolean(false)
        val pausedByUser = AtomicBoolean(false)
        var finalState = DownloadState.IsDone

        try {
            if (!TtsModels.isReady(ctx, def)) {
                finalState = DownloadState.IsFailed
            } else {
                emit(ctx, req, DownloadState.IsDownloading, 0, total)
                // Single emitter: only the coordinator writes progress/notification/record (workers
                // never call emit -> no concurrent setKey). It also drives pause/resume/stop.
                val coordinator = launch(Dispatchers.IO) {
                    while (isActive && !stopped.get()) {
                        when (consumeAction(key)) {
                            DownloadActionType.Stop -> stopped.set(true)
                            DownloadActionType.Pause -> {
                                pausedByUser.set(true); emit(ctx, req, DownloadState.IsPaused, done.get(), total)
                            }
                            DownloadActionType.Resume -> {
                                pausedByUser.set(false); emit(ctx, req, DownloadState.IsDownloading, done.get(), total)
                            }
                            else -> {}
                        }
                        if (!pausedByUser.get() && !stopped.get()) {
                            emit(ctx, req, DownloadState.IsDownloading, done.get(), total)
                        }
                        delay(300)
                    }
                }

                val cursor = AtomicInteger(req.rangeStart)
                val ok = withContext(Dispatchers.IO) {
                    TtsSynthPool.run<Int>(
                        ctx, def, req.sid, bookIdStr, TtsSynthPool.sizing(ctx, def),
                        stop = { stopped.get() },
                        paused = { pausedByUser.get() },
                        next = { cursor.getAndIncrement().takeIf { it <= req.rangeEnd } },
                        body = { synth, index ->
                            val failed = if (!TtsAudioCache.isChapterDone(ctx, bookIdStr, def.id, req.sid, index)) {
                                val r = synth.synthChapter(
                                    req.apiName, req.author, req.name, index, authorNotes,
                                    onProgress = { _, _ -> },
                                    shouldStop = { stopped.get() },
                                    awaitResume = { TtsPlaybackGate.awaitClear({ pausedByUser.get() }, { stopped.get() }) },
                                )
                                r < 0 && !stopped.get()
                            } else false
                            if (!failed) done.incrementAndGet()
                            !failed
                        },
                    )
                }
                coordinator.cancel()
                finalState = when {
                    stopped.get() -> DownloadState.IsStopped
                    !ok -> DownloadState.IsFailed
                    else -> DownloadState.IsDone
                }
            }
        } catch (t: Throwable) {
            logError(t); finalState = DownloadState.IsFailed
        } finally {
            TtsGenerationTracker.clear(bookIdStr, def.id, req.sid) // drop any lingering "generating" marks
            emit(ctx, req, finalState, done.get(), total)
            synchronized(lock) { currentJobs.remove(key); pendingAction.remove(key) }
        }
    }

    /**
     * Auto-start whole-book pre-generation on book open (Feature 3). No-op if already running or the
     * whole range is already cached. Enqueues the same serial WorkManager chain as manual pre-gen.
     */
    fun ensureAutoPregen(context: Context, req: PregenRequest) {
        if (isRunning(req.key)) return
        val ctx = context.applicationContext
        val total = req.rangeEnd - req.rangeStart + 1
        if (total <= 0) return
        if (TtsAudioCache.doneChapterCount(ctx, "b${req.bookId}", req.modelId, req.sid) >= total) return
        enqueue(context, req)
    }

    /** Update in-memory progress + notification + the persisted record in one shot. */
    private fun emit(ctx: Context, req: PregenRequest, state: DownloadState, done: Int, total: Int) {
        val key = req.key
        val progress = DownloadProgressState(
            state = state,
            progress = done.toLong(),
            downloaded = done.toLong(),
            total = total.toLong(),
            lastUpdatedMs = System.currentTimeMillis(),
            etaMs = null,
        )
        synchronized(lock) { pregenProgress[key] = progress }
        pregenProgressChanged.invoke(key to progress)

        val bytes = TtsAudioCache.voiceBytes(ctx, "b${req.bookId}", req.modelId, req.sid)
        val record = TtsPregenRecord(
            bookId = req.bookId, apiName = req.apiName, author = req.author, name = req.name,
            posterUrl = req.posterUrl, modelId = req.modelId, sid = req.sid,
            rangeStart = req.rangeStart, rangeEnd = req.rangeEnd,
            generatedChapters = done, totalChapters = total, bytes = bytes,
            lastUpdated = System.currentTimeMillis(),
        )
        synchronized(lock) { pregenRecords[key] = record }
        runCatching { setKey(TTS_PREGEN_FOLDER, key, record) }
        pregenRecordChanged.invoke(key to record)

        runCatching { TtsPregenNotifications.update(ctx, req, state, done, total) }
    }

    // ---- management ----

    /** Load persisted records into memory + recompute their on-disk byte size. */
    fun reconcileFromDisk(context: Context) {
        val ctx = context.applicationContext
        val keys = getKeys(TTS_PREGEN_FOLDER) ?: emptyList()
        synchronized(lock) { pregenRecords.clear() }
        for (fullKey in keys) {
            val rec = runCatching { getKey<TtsPregenRecord>(fullKey) }.getOrNull() ?: continue
            val bytes = TtsAudioCache.voiceBytes(ctx, "b${rec.bookId}", rec.modelId, rec.sid)
            val generated = TtsAudioCache.doneChapterCount(ctx, "b${rec.bookId}", rec.modelId, rec.sid)
            val fixed = rec.copy(bytes = bytes, generatedChapters = maxOf(rec.generatedChapters, generated))
            synchronized(lock) { pregenRecords[fixed.key] = fixed }
        }
        pregenRefreshed.invoke(0)
    }

    fun records(): List<TtsPregenRecord> = synchronized(lock) { pregenRecords.values.sortedBy { it.name } }

    /** Delete every cached chapter before the current reading position (keyed by book name). */
    fun deleteReadChapters(context: Context, key: String) {
        val rec = synchronized(lock) { pregenRecords[key] } ?: return
        val ctx = context.applicationContext
        val readIndex = getKey<Int>(EPUB_CURRENT_POSITION, rec.name) ?: 0
        for (i in rec.rangeStart until readIndex) {
            TtsAudioCache.deleteChapter(ctx, "b${rec.bookId}", rec.modelId, rec.sid, i)
        }
        val bytes = TtsAudioCache.voiceBytes(ctx, "b${rec.bookId}", rec.modelId, rec.sid)
        val generated = TtsAudioCache.doneChapterCount(ctx, "b${rec.bookId}", rec.modelId, rec.sid)
        val fixed = rec.copy(bytes = bytes, generatedChapters = generated)
        synchronized(lock) { pregenRecords[key] = fixed }
        runCatching { setKey(TTS_PREGEN_FOLDER, key, fixed) }
        pregenRecordChanged.invoke(key to fixed)
    }

    /** Extend generation up to the latest downloaded chapter (exclusive count -> inclusive index). */
    fun sync(context: Context, key: String, latestDownloadedCount: Int) {
        val rec = synchronized(lock) { pregenRecords[key] } ?: return
        val newEnd = latestDownloadedCount - 1
        if (newEnd <= rec.rangeEnd) return
        enqueue(
            context,
            PregenRequest(
                bookId = rec.bookId, apiName = rec.apiName, author = rec.author, name = rec.name,
                posterUrl = rec.posterUrl, modelId = rec.modelId, sid = rec.sid,
                rangeStart = rec.rangeEnd + 1, rangeEnd = newEnd,
            ),
        )
    }

    fun deleteAll(context: Context, key: String) {
        val rec = synchronized(lock) { pregenRecords[key] } ?: return
        TtsAudioCache.deleteVoice(context.applicationContext, "b${rec.bookId}", rec.modelId, rec.sid)
        synchronized(lock) { pregenRecords.remove(key); pregenProgress.remove(key) }
        runCatching { removeKey(TTS_PREGEN_FOLDER, key) }
        pregenRemoved.invoke(key)
    }
}
