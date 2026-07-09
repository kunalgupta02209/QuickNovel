package com.lagradost.quicknovel.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.lagradost.quicknovel.BaseApplication.Companion.getKeyClass
import com.lagradost.quicknovel.BaseApplication.Companion.setKeyClass
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.BuildConfig
import com.lagradost.quicknovel.DataStore
import com.lagradost.quicknovel.LLM_FIX_SERVER_URL
import com.lagradost.quicknovel.TELEMETRY_DEVICE_ID
import com.lagradost.quicknovel.TELEMETRY_ENABLED
import com.lagradost.quicknovel.llm.LlmFixManager
import com.lagradost.quicknovel.tts.RemoteTtsManager
import com.lagradost.quicknovel.tts.TtsAudioCache
import com.lagradost.quicknovel.tts.TtsPregenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device -> server telemetry: throttled state snapshots (downloads, TTS cache fill, active
 * pregen/remote/fix jobs) POSTed to the fix server's /telemetry/device so the web dashboard can show
 * per-device sync progress. Fire-and-forget: silent on failure, never blocks UX. Opt-out via
 * TELEMETRY_ENABLED; only active when a server URL is configured (the URL is the opt-in).
 */
object TelemetryManager {
    private const val MIN_INTERVAL_MS = 10_000L   // event-driven sends, at most every 10s
    private const val HEARTBEAT_MS = 60_000L      // foreground keep-alive
    private const val IDLE_STOP_MS = 5 * 60_000L  // loop self-terminates after idle+background

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dirty = AtomicBoolean(false)
    private val foregroundCount = AtomicInteger(0)
    private var loop: Job? = null
    private val loopRunning = AtomicBoolean(false)
    private var appContext: Context? = null
    private var lastSent = 0L
    private var backoffMs = 0L
    private var seq = 0

    fun init(context: Context) {
        appContext = context.applicationContext
        // Any progress anywhere -> mark dirty; the loop coalesces + throttles.
        BookDownloader2.downloadProgressChanged += { markDirty() }
        BookDownloader2.downloadDataChanged += { markDirty() }
        LlmFixManager.progressChanged += { markDirty() }
        TtsPregenManager.pregenProgressChanged += { markDirty() }
        RemoteTtsManager.remoteProgressChanged += { markDirty() }
        markDirty() // initial snapshot shortly after startup
    }

    fun onForeground(delta: Int) {
        foregroundCount.addAndGet(delta)
        if (delta > 0) markDirty()
    }

    private fun markDirty() {
        dirty.set(true)
        ensureLoop()
    }

    private fun serverUrl(): String =
        runCatching { getKeyClass(LLM_FIX_SERVER_URL, String::class.java) }.getOrNull() ?: ""

    private fun enabled(): Boolean =
        (runCatching { getKeyClass(TELEMETRY_ENABLED, Boolean::class.javaObjectType) }.getOrNull() != false) &&
                serverUrl().isNotBlank()

    private fun ensureLoop() {
        if (!loopRunning.compareAndSet(false, true)) return
        loop = scope.launch {
            var idleSince = System.currentTimeMillis()
            try {
                while (true) {
                    delay(1000)
                    val now = System.currentTimeMillis()
                    val heartbeatDue = foregroundCount.get() > 0 && now - lastSent >= HEARTBEAT_MS
                    val eventDue = dirty.get() && now - lastSent >= maxOf(MIN_INTERVAL_MS, backoffMs)
                    if ((eventDue || heartbeatDue) && enabled()) {
                        dirty.set(false)
                        val body = runCatching {
                            TelemetryClient.postSnapshot(serverUrl(), buildSnapshotJson())
                        }.getOrNull()
                        lastSent = now
                        backoffMs = if (body != null) 0L else minOf(maxOf(backoffMs * 2, 10_000L), 300_000L)
                        if (body != null) runCatching { handleCommands(body) }
                        idleSince = now
                    }
                    if (dirty.get() || foregroundCount.get() > 0) idleSince = now
                    if (now - idleSince > IDLE_STOP_MS) return@launch // restarted by next markDirty
                }
            } finally {
                loopRunning.set(false)
            }
        }
    }

    fun deviceIdentity(ctx: Context): Pair<String, String> =
        deviceId(ctx) to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    /** Server->device commands piggybacked on the telemetry response (queued from the dashboard). */
    private fun handleCommands(responseBody: String) {
        val ctx = appContext ?: return
        val commands = runCatching {
            DataStore.mapper.readTree(responseBody).get("commands")
        }.getOrNull() ?: return
        for (cmd in commands) {
            when (cmd.get("type")?.asText()) {
                // "Sync chapters" button on the dashboard device card: upload every downloaded
                // book's chapters + queue TTS via the standard download-complete trigger path.
                "sync_books" -> runCatching {
                    android.util.Log.i("Telemetry", "server command: sync_books")
                    com.lagradost.quicknovel.BaseApplication.getKeys(com.lagradost.quicknovel.DOWNLOAD_FOLDER)
                        ?.forEach { key ->
                            val d = com.lagradost.quicknovel.BaseApplication.getKeyClass(
                                key, com.lagradost.quicknovel.ui.download.DownloadFragment.DownloadData::class.java
                            ) ?: return@forEach
                            com.lagradost.quicknovel.tts.RemoteTtsManager.maybeAutoQueueFromDownload(
                                ctx, d.apiName, d.author, d.name, d.posterUrl,
                            )
                        }
                }
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun deviceId(ctx: Context): String =
        runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { getKeyClass(TELEMETRY_DEVICE_ID, String::class.java) }.getOrNull()
            ?: UUID.randomUUID().toString().also {
                runCatching { setKeyClass(TELEMETRY_DEVICE_ID, it) }
            }

    private fun buildSnapshotJson(): String {
        val ctx = appContext ?: return "{}"
        seq += 1

        val progress = synchronized(BookDownloader2.downloadProgress) { HashMap(BookDownloader2.downloadProgress) }
        // In-memory downloadData is empty until the downloads UI loads; merge the PERSISTED records
        // (downloads_data/<id>) so names resolve even right after a cold start.
        val data = synchronized(BookDownloader2.downloadData) { HashMap(BookDownloader2.downloadData) }
        runCatching {
            com.lagradost.quicknovel.BaseApplication.getKeys(com.lagradost.quicknovel.DOWNLOAD_FOLDER)
                ?.forEach { fullKey ->
                    val id = fullKey.substringAfterLast('/').toIntOrNull() ?: return@forEach
                    if (!data.containsKey(id)) {
                        com.lagradost.quicknovel.BaseApplication.getKeyClass(
                            fullKey, com.lagradost.quicknovel.ui.download.DownloadFragment.DownloadData::class.java
                        )?.let { data[id] = it }
                    }
                }
        }
        val books = data.entries.take(300).map { (id, d) ->
            val p = progress[id]
            mapOf(
                "id" to id, "name" to d.name, "api" to d.apiName,
                "downloaded" to (p?.downloaded ?: 0), "progress" to (p?.progress ?: 0),
                "total" to (p?.total ?: 0), "state" to (p?.state?.name ?: "Nothing"),
                "eta_ms" to p?.etaMs,
            )
        }
        val activeDownloads = progress.entries
            .filter { it.value.state.name in setOf("IsDownloading", "IsPending", "IsPaused") }
            .take(50)
            .map { (id, p) ->
                mapOf("id" to id, "state" to p.state.name, "progress" to p.progress,
                      "downloaded" to p.downloaded, "total" to p.total)
            }
        // Resolve opaque book ids to display names for the dashboard ("b<id>" and "<id>|model|sid").
        // Fallback chain: in-memory/persisted records -> the download FILESYSTEM itself (an old
        // install can lose the records while files/<api>/<author>/<book>/N.txt remain; ids are
        // generateId(api, author, book) over those exact directory names, so the scan is exact).
        val idToName = HashMap<String, String>()
        data.forEach { (id, d) -> idToName[id.toString()] = d.name }
        runCatching {
            val skip = setOf("tts-cache", "rList")
            ctx.filesDir.listFiles { f -> f.isDirectory && f.name !in skip }?.forEach { api ->
                api.listFiles { f -> f.isDirectory }?.forEach { author ->
                    author.listFiles { f -> f.isDirectory }?.forEach { bookDir ->
                        if (bookDir.listFiles()?.any { it.extension == "txt" } == true) {
                            val id = com.lagradost.quicknovel.BookDownloader2Helper
                                .generateId(api.name, author.name, bookDir.name).toString()
                            idToName.putIfAbsent(id, bookDir.name)
                        }
                    }
                }
            }
        }
        fun nameOfBookId(bookId: String): String? = idToName[bookId.removePrefix("b")]
        fun nameOfKey(key: String): String? = idToName[key.substringBefore("|")]

        val ttsCache = TtsAudioCache.allVoices(ctx).take(300).map { v ->
            mapOf("book" to v.bookId, "name" to nameOfBookId(v.bookId), "model" to v.modelId, "sid" to v.sid,
                  "chapters_done" to v.chaptersDone, "total" to v.chaptersDone, "bytes" to v.bytes)
        }
        val pregen = TtsPregenManager.pregenProgress.let { synchronized(it) { HashMap(it) } }
            .filter { it.value.state.name in setOf("IsDownloading", "IsPending", "IsPaused") }
            .map { (k, p) -> mapOf("key" to k, "name" to nameOfKey(k), "state" to p.state.name,
                                   "done" to p.downloaded, "total" to p.total) }
        val remote = RemoteTtsManager.remoteProgressSnapshot()
            .map { mapOf("key" to it.key, "name" to nameOfKey(it.key), "job_id" to it.jobId,
                         "status" to it.status, "done" to it.done, "total" to it.total) }
        val fixes = LlmFixManager.progress.let { synchronized(it) { HashMap(it) } }
            .filter { it.value.state.name in setOf("IsDownloading", "IsPending", "IsPaused") }
            .map { (k, p) -> mapOf("key" to k, "name" to nameOfKey(k), "state" to p.state.name,
                                   "done" to p.downloaded, "total" to p.total) }

        return DataStore.mapper.writeValueAsString(
            mapOf(
                "device_id" to deviceId(ctx),
                "device_name" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "app_version" to BuildConfig.VERSION_NAME,
                "ts" to System.currentTimeMillis(),
                "seq" to seq,
                "foreground" to (foregroundCount.get() > 0),
                "books" to books,
                "tts_cache" to ttsCache,
                "active" to mapOf(
                    "downloads" to activeDownloads,
                    "tts_pregen" to pregen,
                    "tts_remote" to remote,
                    "llm_fix" to fixes,
                ),
            )
        )
    }
}
