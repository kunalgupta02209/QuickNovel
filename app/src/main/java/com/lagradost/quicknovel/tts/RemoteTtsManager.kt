package com.lagradost.quicknovel.tts

import android.content.Context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.EPUB_AUTHOR_NOTES
import com.lagradost.quicknovel.TTS_REMOTE_FOLDER
import com.lagradost.quicknovel.mvvm.logError
import kotlinx.coroutines.delay
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Server-offloaded analogue of [TtsPregenManager]: on book download/open, submit the book's uncached
 * sentences to the /tts server, poll the job, and pull each ready chapter's ZIP straight into
 * [TtsAudioCache] (same voice + keys, so playback is an instant cache hit). Shares the cache + key
 * space with on-device pre-gen, so either producer resumes the other's partial work.
 */
object RemoteTtsManager {
    private const val TAG = "RemoteTts"

    data class RemoteTtsRequest(
        val bookId: Int,
        val apiName: String,
        val author: String?,
        val name: String,
        val posterUrl: String?,
        val modelId: String,
        val sid: Int,
        val sampleRate: Int,
        val rangeStart: Int,
        val rangeEnd: Int,
        val serverUrl: String,
        val textOnly: Boolean = false,   // upload chapter texts only, NO audio generation
        val forceAudio: Boolean = false, // explicit user action (Edit-with-AI) -> generate audio
    ) {
        val key: String get() = "$bookId|$modelId|$sid"
        val notifId: Int get() = key.hashCode() xor 0x77260000.toInt()
    }

    private val lock = Any()
    private val currentJobs = HashSet<String>()
    private val stopRequested = HashSet<String>()
    private val pendingAction = HashMap<String, com.lagradost.quicknovel.DownloadActionType>()

    /** Notification action buttons (pause/resume/stop) land here, keyed like the job. */
    fun addPendingAction(key: String, action: com.lagradost.quicknovel.DownloadActionType) {
        synchronized(lock) {
            if (!currentJobs.contains(key)) return
            if (action == com.lagradost.quicknovel.DownloadActionType.Stop) stopRequested.add(key)
            pendingAction[key] = action
        }
    }

    private fun consumeAction(key: String): com.lagradost.quicknovel.DownloadActionType? =
        synchronized(lock) { pendingAction.remove(key) }

    /** Live progress of a remote (server) TTS job — telemetry/dashboard visibility. */
    data class RemoteProgress(
        val key: String = "",
        val status: String = "", // building | polling | done | failed | stopped
        val jobId: String? = null,
        val done: Int = 0,
        val total: Int = 0,
    )

    private val remoteProgress = HashMap<String, RemoteProgress>()
    val remoteProgressChanged = com.lagradost.quicknovel.util.Event<Pair<String, RemoteProgress>>()

    fun remoteProgressSnapshot(): List<RemoteProgress> =
        synchronized(lock) { remoteProgress.values.toList() }

    private fun emitProgress(p: RemoteProgress) {
        synchronized(lock) {
            if (p.status == "done" || p.status == "failed" || p.status == "stopped") remoteProgress.remove(p.key)
            else remoteProgress[p.key] = p
        }
        remoteProgressChanged.invoke(p.key to p)
    }

    fun isRunning(key: String): Boolean = synchronized(lock) { currentJobs.contains(key) }
    fun requestStop(key: String) = synchronized(lock) { if (currentJobs.contains(key)) stopRequested.add(key) }
    private fun shouldStop(key: String): Boolean = synchronized(lock) { stopRequested.contains(key) }

    /**
     * G1: fire-on-download-complete. Reads the autogen prefs statically (no ViewModel), applies the
     * same gates as maybeStartAutoPregen, and hands off to [onBookReady]. Call from BookDownloader2
     * when a book's chapter download finishes.
     */
    fun maybeAutoQueueFromDownload(
        context: Context,
        apiName: String,
        author: String?,
        name: String,
        posterUrl: String?,
    ) {
        val ctx = context.applicationContext
        runCatching {
            val autogen = com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass(
                com.lagradost.quicknovel.EPUB_TTS_OD_AUTOGEN, Boolean::class.javaObjectType
            ) == true
            val serverAutogen = com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass(
                com.lagradost.quicknovel.EPUB_TTS_SERVER_AUTOGEN, Boolean::class.javaObjectType
            ) == true
            if (!autogen && !serverAutogen) return
            val engine = com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass(
                com.lagradost.quicknovel.EPUB_TTS_ENGINE, Int::class.javaObjectType
            ) ?: 0
            if (engine != 1) { // ON_DEVICE only (system TTS can't play the cached WAVs)
                android.util.Log.i(TAG, "download-complete autogen skipped: engine != ON_DEVICE"); return
            }
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
            val modelId = com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass(
                com.lagradost.quicknovel.EPUB_TTS_OD_MODEL, String::class.java
            ) ?: "kitten"
            val def = TtsModels.byId(modelId)
            if (!TtsModels.isReady(ctx, def)) {
                android.util.Log.i(TAG, "download-complete autogen skipped: model ${def.id} not downloaded"); return
            }
            val voice = com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass(
                com.lagradost.quicknovel.EPUB_TTS_OD_VOICE, String::class.java
            )
            val sid = TtsModels.parseVoice(voice)?.second ?: 0
            val total = com.lagradost.quicknovel.BookDownloader2Helper
                .downloadInfo(ctx, author ?: "", name, apiName)?.total?.toInt() ?: return
            if (total <= 0) return
            val serverUrl = if (serverAutogen) {
                com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass(
                    com.lagradost.quicknovel.LLM_FIX_SERVER_URL, String::class.java
                ) ?: ""
            } else ""
            android.util.Log.i(TAG, "download-complete -> autogen trigger '$name' total=$total")
            onBookReady(
                ctx,
                RemoteTtsRequest(
                    bookId = com.lagradost.quicknovel.BookDownloader2Helper.generateId(apiName, author ?: "", name),
                    apiName = apiName, author = author ?: "", name = name, posterUrl = posterUrl,
                    modelId = def.id, sid = sid, sampleRate = def.sampleRate,
                    rangeStart = 0, rangeEnd = total - 1, serverUrl = serverUrl,
                ),
            )
        }.onFailure { logError(it) }
    }

    /**
     * The single entry point used by both triggers (book download-complete + book open). Picks the
     * server when a URL is set + reachable, else falls back to on-device pre-gen. No-op if already
     * running (either producer) or fully cached.
     */
    fun onBookReady(context: Context, req: RemoteTtsRequest) {
        val key = req.key
        if (TtsPregenManager.isRunning(key) || isRunning(key)) {
            android.util.Log.i(TAG, "onBookReady skipped: already running key=$key")
            return
        }
        val ctx = context.applicationContext
        val bookIdStr = "b${req.bookId}"
        val total = req.rangeEnd - req.rangeStart + 1
        if (total <= 0) {
            android.util.Log.i(TAG, "onBookReady skipped: no chapters key=$key")
            return
        }
        if (TtsAudioCache.doneChapterCount(ctx, bookIdStr, req.modelId, req.sid) >= total) {
            android.util.Log.i(TAG, "onBookReady skipped: fully cached key=$key total=$total")
            return
        }

        if (req.serverUrl.isNotBlank() && RemoteTtsClient.reachable(req.serverUrl)) {
            // Character-voices flow: opening a book must NOT trigger audio generation — only a
            // text upload (feeds charmaps/scripts). Audio waits for the user's explicit
            // Edit-with-AI action (forceAudio) — user requirement.
            val castingOn = runCatching {
                com.lagradost.quicknovel.BaseApplication.getKeyClass(
                    com.lagradost.quicknovel.EPUB_TTS_CASTING, Boolean::class.javaObjectType
                )
            }.getOrNull() != false
            val effective = if (castingOn && !req.forceAudio) req.copy(textOnly = true) else req
            android.util.Log.i(TAG, "onBookReady -> SERVER queue key=$key textOnly=${effective.textOnly}")
            if (!effective.textOnly) {
                com.lagradost.quicknovel.CommonActivity.showToast(com.lagradost.quicknovel.R.string.sent_tts_to_server)
            }
            com.lagradost.quicknovel.RemoteTtsWorkManager.enqueue(ctx, effective) // server-offloaded
        } else {
            android.util.Log.i(
                TAG,
                "onBookReady -> on-device fallback key=$key (serverUrl=${req.serverUrl.ifBlank { "unset" }}, reachable=false)"
            )
            // On-device fallback (same key space -> shared dedupe) — unless globally disabled.
            if (!com.lagradost.quicknovel.util.DeviceGenGate.allowed(ctx)) {
                android.util.Log.i(TAG, "on-device fallback skipped: generation disabled (key=$key)")
                return
            }
            TtsPregenManager.ensureAutoPregen(
                ctx,
                TtsPregenManager.PregenRequest(
                    bookId = req.bookId, apiName = req.apiName, author = req.author, name = req.name,
                    posterUrl = req.posterUrl, modelId = req.modelId, sid = req.sid,
                    rangeStart = req.rangeStart, rangeEnd = req.rangeEnd,
                ),
            )
        }
    }

    // Chapters per submit cycle. A 1219-chapter book built + submitted as ONE batch spent 10+ silent
    // minutes parsing every chapter on the phone and then had to upload a tens-of-MB JSON body — the
    // "stuck at 0/N, job …" symptom. Slices keep each build+POST seconds-sized, deliver audio for the
    // first chapters within minutes, and emit visible progress between slices.
    private const val SLICE_CHAPTERS = 25

    suspend fun runJob(context: Context, req: RemoteTtsRequest) {
        val key = req.key
        synchronized(lock) { if (!currentJobs.add(key)) return; stopRequested.remove(key) }
        val ctx = context.applicationContext
        val bookIdStr = "b${req.bookId}"
        val def = TtsModels.byId(req.modelId)
        val authorNotes = getKey<Boolean>(EPUB_AUTHOR_NOTES, true) ?: true
        val total = req.rangeEnd - req.rangeStart + 1
        emitProgress(RemoteProgress(key, "building", null, 0, total))
        var lastStatus = "failed" // overwritten on success paths; the finally emits the terminal state
        var doneCount = 0
        // Cast-aware sync: with the reader in PERFORMANCE mode, ship each line's CAST rendition
        // (voice sid + effective text) so the SERVER generates the multi-voice audio and the phone
        // only downloads it — required for cast playback with on-device generation disabled.
        val scriptMode = runCatching {
            com.lagradost.quicknovel.BaseApplication.getKeyClass(
                com.lagradost.quicknovel.LLM_FIX_SCRIPT_MODE, Int::class.javaObjectType
            )
        }.getOrNull() ?: -1
        val perfMode = scriptMode == 2
        val castingOn = runCatching {
            com.lagradost.quicknovel.BaseApplication.getKeyClass(
                com.lagradost.quicknovel.EPUB_TTS_CASTING, Boolean::class.javaObjectType
            )
        }.getOrNull() != false
        val llmModel = runCatching {
            com.lagradost.quicknovel.BaseApplication.getKeyClass(
                com.lagradost.quicknovel.LLM_FIX_MODEL, String::class.java
            )
        }.getOrNull() ?: "qwen2.5-1.5b"
        val promptV = runCatching {
            com.lagradost.quicknovel.BaseApplication.getKeyClass(
                com.lagradost.quicknovel.LLM_FIX_PROMPT_VERSION, Int::class.javaObjectType
            )
        }.getOrNull() ?: 1
        if (perfMode) CueRenderer.syncMap(ctx, req.serverUrl, bookIdStr)
        val keyToSid = HashMap<String, Int>() // cast keys -> the sid dir their WAVs unpack into
        try {
            // TEXT-ONLY sync: upload chapters (store_only), no audio job, no polling. Feeds the
            // server's charmap/script pipeline; audio comes later from an explicit user action.
            if (req.textOnly) {
                var sliceStart0 = req.rangeStart
                while (sliceStart0 <= req.rangeEnd) {
                    if (shouldStop(key)) { lastStatus = "stopped"; return }
                    val sliceEnd0 = minOf(sliceStart0 + SLICE_CHAPTERS - 1, req.rangeEnd)
                    val chapters = ArrayList<RemoteTtsClient.ChapterReq>()
                    for (index in sliceStart0..sliceEnd0) {
                        val lines = TtsChapterLines.build(ctx, req.apiName, req.author, req.name, index, authorNotes, bookIdStr) ?: continue
                        val sentences = lines.map { RemoteTtsClient.Sentence(TtsAudioCache.keyFor(it), it.speakOutMsg) }
                        val chapterName = lines.firstOrNull()
                            ?.takeIf { it.startChar == 0 && it.endChar == 0 }?.speakOutMsg ?: "Chapter ${index + 1}"
                        if (sentences.isNotEmpty()) chapters.add(RemoteTtsClient.ChapterReq(index, sentences, chapterName))
                    }
                    if (chapters.isNotEmpty()) {
                        val (devId, devName) = com.lagradost.quicknovel.telemetry.TelemetryManager.deviceIdentity(ctx)
                        RemoteTtsClient.submitBatch(
                            req.serverUrl, bookIdStr, def.id, req.sid, req.sampleRate, chapters,
                            bookName = req.name, deviceId = devId, deviceName = devName,
                            apiName = req.apiName, author = req.author ?: "", storeOnly = true,
                        ) ?: return
                    }
                    doneCount = minOf(sliceEnd0 - req.rangeStart + 1, total)
                    emitProgress(RemoteProgress(key, "storing", null, doneCount, total))
                    sliceStart0 = sliceEnd0 + 1
                }
                lastStatus = "done"
                android.util.Log.i(TAG, "text-only sync complete key=$key ($total chapters)")
                return
            }
            var sliceStart = req.rangeStart
            while (sliceStart <= req.rangeEnd) {
                val sliceEnd = minOf(sliceStart + SLICE_CHAPTERS - 1, req.rangeEnd)

                // 1) Work-list for THIS slice only (skip .done chapters + on-disk WAVs).
                val chapters = ArrayList<RemoteTtsClient.ChapterReq>()
                for (index in sliceStart..sliceEnd) {
                    if (shouldStop(key)) { lastStatus = "stopped"; return }
                    if (TtsAudioCache.isChapterDone(ctx, bookIdStr, def.id, req.sid, index)) { doneCount++; continue }
                    val lines = TtsChapterLines.build(ctx, req.apiName, req.author, req.name, index, authorNotes, bookIdStr) ?: continue
                    val resolver = if (perfMode) {
                        CueRenderer.ensureDoc(ctx, req.serverUrl, bookIdStr, llmModel, promptV, index)
                        CueRenderer.resolverFor(ctx, bookIdStr, def.id, llmModel, promptV, index, castingOn)
                    } else null
                    val sentences = lines.mapNotNull { line ->
                        val cue = resolver?.invoke(line)
                        if (cue != null) {
                            val castSid = cue.sid ?: req.sid
                            if (TtsAudioCache.fileForText(ctx, bookIdStr, def.id, castSid, index, cue.effectiveText).exists()) null
                            else {
                                val k = TtsAudioCache.keyForText(cue.effectiveText)
                                keyToSid[k] = castSid
                                RemoteTtsClient.Sentence(k, cue.effectiveText, castSid, cue.speed.takeIf { sp -> sp != 1.0f })
                            }
                        } else {
                            if (TtsAudioCache.fileFor(ctx, bookIdStr, def.id, req.sid, line).exists()) null
                            else RemoteTtsClient.Sentence(TtsAudioCache.keyFor(line), line.speakOutMsg)
                        }
                    }
                    // The prepended title line (startChar==endChar==0) doubles as the chapter's display name.
                    val chapterName = lines.firstOrNull()
                        ?.takeIf { it.startChar == 0 && it.endChar == 0 }?.speakOutMsg ?: "Chapter ${index + 1}"
                    if (sentences.isNotEmpty()) chapters.add(RemoteTtsClient.ChapterReq(index, sentences, chapterName))
                    else { doneCount++; TtsAudioCache.markChapterDone(ctx, bookIdStr, def.id, req.sid, index) }
                }
                sliceStart = sliceEnd + 1
                emitProgress(RemoteProgress(key, "building", null, doneCount, total))
                if (chapters.isEmpty()) continue // slice fully cached/undownloaded -> next slice

                // 2) Resume a still-live server job, else submit THIS slice.
                val prevJobId = getKey<String>(TTS_REMOTE_FOLDER, key)
                var jobId = prevJobId?.takeIf {
                    RemoteTtsClient.getJob(req.serverUrl, it)?.status in setOf("running", "queued")
                }
                if (jobId == null) {
                    val (devId, devName) = com.lagradost.quicknovel.telemetry.TelemetryManager.deviceIdentity(ctx)
                    jobId = RemoteTtsClient.submitBatch(
                        req.serverUrl, bookIdStr, def.id, req.sid, req.sampleRate, chapters,
                        bookName = req.name, deviceId = devId, deviceName = devName,
                        apiName = req.apiName, author = req.author ?: "",
                    ) ?: return // unreachable/failed -> WorkManager result is success; re-open re-triggers
                }
                runCatching { setKey(TTS_REMOTE_FOLDER, key, jobId) }
                android.util.Log.i(TAG, "server job $jobId for key=$key (slice ${chapters.size} ch, $doneCount/$total done)")
                emitProgress(RemoteProgress(key, "polling", jobId, doneCount, total))

                // 3) Poll + pull each ready chapter of this slice into the cache.
                val fetched = HashSet<Int>()
                var pollFailures = 0
                pollLoop@ while (true) {
                    if (shouldStop(key)) {
                        lastStatus = "stopped"
                        RemoteTtsClient.cancelJob(req.serverUrl, jobId); return
                    }
                    if (consumeAction(key) == com.lagradost.quicknovel.DownloadActionType.Pause) {
                        // Pause the SERVER job too (stops burning its CPU), then idle until resume/stop.
                        RemoteTtsClient.pauseJob(req.serverUrl, jobId)
                        emitProgress(RemoteProgress(key, "paused", jobId, doneCount + fetched.size, total))
                        pauseWait@ while (true) {
                            delay(500)
                            when (consumeAction(key)) {
                                com.lagradost.quicknovel.DownloadActionType.Resume -> {
                                    RemoteTtsClient.resumeJob(req.serverUrl, jobId)
                                    emitProgress(RemoteProgress(key, "polling", jobId, doneCount + fetched.size, total))
                                    break@pauseWait
                                }
                                com.lagradost.quicknovel.DownloadActionType.Stop -> {
                                    lastStatus = "stopped"
                                    RemoteTtsClient.cancelJob(req.serverUrl, jobId); return
                                }
                                else -> if (shouldStop(key)) {
                                    lastStatus = "stopped"
                                    RemoteTtsClient.cancelJob(req.serverUrl, jobId); return
                                }
                            }
                        }
                    }
                    val job = RemoteTtsClient.getJob(req.serverUrl, jobId)
                    if (job == null) {
                        // Distinguish a network blip (retry, keep the persisted jobId so we resume)
                        // from the job being GONE on a reachable server (e.g. --reload wiped it):
                        // then drop the stale id so the next trigger resubmits the uncached remainder.
                        pollFailures++
                        if (pollFailures < 5) { delay(5000); continue }
                        if (RemoteTtsClient.reachable(req.serverUrl)) {
                            android.util.Log.w(TAG, "job $jobId gone on reachable server; clearing for resubmit (key=$key)")
                            runCatching { removeKey(TTS_REMOTE_FOLDER, key) }
                        } else {
                            android.util.Log.w(TAG, "server unreachable after $pollFailures polls; keeping jobId for resume (key=$key)")
                        }
                        return
                    }
                    pollFailures = 0
                    for (idx in job.readyChapters) {
                        if (idx in fetched) continue
                        if (fetchChapter(ctx, req, def, idx, keyToSid)) {
                            TtsAudioCache.markChapterDone(ctx, bookIdStr, def.id, req.sid, idx)
                            fetched.add(idx)
                            RemoteTtsNotifications.update(ctx, req, doneCount + fetched.size, total, finished = false)
                            emitProgress(RemoteProgress(key, "polling", jobId, doneCount + fetched.size, total))
                        }
                    }
                    when (job.status) {
                        "done" -> break@pollLoop
                        "error", "cancelled" -> break@pollLoop
                        else -> delay(2000)
                    }
                }
                doneCount += fetched.size
                runCatching { removeKey(TTS_REMOTE_FOLDER, key) } // slice complete; next slice = fresh job
                RemoteTtsNotifications.update(ctx, req, doneCount, total, finished = false)
            }
            lastStatus = "done"
            RemoteTtsNotifications.update(ctx, req, doneCount, total, finished = true)
        } catch (t: Throwable) {
            logError(t)
        } finally {
            emitProgress(RemoteProgress(key, lastStatus, null, 0, total)) // terminal -> removes the entry
            synchronized(lock) { currentJobs.remove(key); stopRequested.remove(key) }
        }
    }

    /** Download one chapter's ZIP and drop each <key>.wav straight into the cache dir (no re-encode). */
    private fun fetchChapter(
        ctx: Context, req: RemoteTtsRequest, def: TtsModels.ModelDef, index: Int,
        keyToSid: Map<String, Int> = emptyMap(),
    ): Boolean {
        val bookIdStr = "b${req.bookId}"
        // Cast sentences live under their CAST voice's dir on BOTH sides now — pull the job sid's
        // chapter ZIP plus one ZIP per distinct cast sid, each unpacking straight into its own dir.
        val sids = (keyToSid.values.toSet() + req.sid)
        var any = false
        for (sid in sids) {
            val dir = TtsAudioCache.chapterDir(ctx, bookIdStr, def.id, sid, index)
            dir.mkdirs()
            val stream = RemoteTtsClient.openChapterZip(req.serverUrl, bookIdStr, def.id, sid, index)
                ?: continue // a sid with no audio for this chapter is fine
            try {
                ZipInputStream(stream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = File(entry.name).name // entry is already "<key>.wav"
                        if (name.endsWith(".wav")) {
                            val dest = File(dir, name)
                            val tmp = File(dir, "$name.${Thread.currentThread().id}.${System.nanoTime()}.part")
                            tmp.outputStream().use { zip.copyTo(it) }
                            tmp.renameTo(dest)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                any = true
            } catch (t: Throwable) {
                logError(t)
            }
        }
        return any
    }
}
