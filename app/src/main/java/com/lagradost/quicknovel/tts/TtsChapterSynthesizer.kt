package com.lagradost.quicknovel.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.lagradost.quicknovel.BookDownloader2Helper.readDownloadedChapter
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.mvvm.logError
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.html.HtmlPlugin

/**
 * Headless per-chapter on-device TTS synthesis into [TtsAudioCache], used by the background
 * pre-generator. Owns its OWN [OfflineTts] (never shares the live playback engine's) and has no
 * AudioTrack / audio focus / queue — it is a pure generate -> trim -> save loop.
 *
 * It reproduces the reader's EXACT text pipeline (preParseHtml -> markwon -> ttsParseText + a
 * prepended title line) so the content-hash cache keys line up byte-for-byte with live playback,
 * and reading a pre-generated chapter is an instant cache hit. Images don't produce spoken words,
 * so a lightweight Markwon (no Coil image plugin) yields identical spoken text.
 */
class TtsChapterSynthesizer(
    private val context: Context,
    private val def: TtsModels.ModelDef,
    private val sid: Int,
    private val bookId: String,
) {
    private var tts: OfflineTts? = null
    private var sampleRate: Int = 24000

    private val markwon: Markwon = Markwon.builder(context)
        .usePlugin(HtmlPlugin.create { it.excludeDefaults(false) })
        .usePlugin(SoftBreakAddsNewLinePlugin.create())
        .build()

    fun open(numThreads: Int = TtsModels.defaultInferenceThreads): Boolean {
        val config = TtsModels.resolveConfig(context, def, numThreads) ?: return false
        return try {
            val t = OfflineTts(assetManager = null, config = config)
            sampleRate = t.sampleRate()
            tts = t
            true
        } catch (t: Throwable) {
            logError(t); false
        }
    }

    fun close() {
        runCatching { tts?.release() }
        tts = null
    }

    /**
     * Synthesize every not-yet-cached sentence of one downloaded chapter into the cache.
     * @return number of sentences in the chapter (>=0), 0 if the chapter isn't downloaded, or -1 on
     *         failure / stop. Sentences already on disk are skipped (resume-friendly).
     */
    /**
     * Synthesize a batch of [lines] into the cache (default voice); already-cached sentences are
     * skipped. [awaitResume] runs before each sentence to yield to the live reader engine;
     * [shouldStop] aborts (the native generate stops cleanly the moment the sink returns 0).
     * @return sentences processed (>=0), or -1 on stop / failure.
     */
    fun synthLines(
        lines: List<TTSHelper.TTSLine>,
        shouldStop: () -> Boolean,
        awaitResume: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val engine = tts ?: return -1
        val gen = TtsModels.resolveGenerationConfig(context, def, sid, 1.0f)
        var done = 0
        for (line in lines) {
            awaitResume()
            if (shouldStop()) return -1
            val f = TtsAudioCache.fileFor(context, bookId, def.id, sid, line)
            if (!f.exists()) {
                // Publish this sentence as "generating" (reader underline). try/finally drains it even
                // on the early return -1 paths below.
                TtsGenerationTracker.markStart(bookId, def.id, sid, line)
                try {
                    val chunks = ArrayList<FloatArray>()
                    var n = 0
                    val sink: (FloatArray) -> Int = cb@{ s ->
                        if (shouldStop()) return@cb 0
                        val c = s.copyOf(); chunks.add(c); n += c.size; 1
                    }
                    try {
                        if (gen != null)
                            engine.generateWithConfigAndCallback(text = line.speakOutMsg, config = gen, callback = sink)
                        else
                            engine.generateWithCallback(text = line.speakOutMsg, sid = sid, speed = 1.0f, callback = sink)
                    } catch (t: Throwable) {
                        logError(t); return -1
                    }
                    if (shouldStop()) return -1
                    val raw = FloatArray(n)
                    var o = 0
                    for (c in chunks) { System.arraycopy(c, 0, raw, o, c.size); o += c.size }
                    TtsAudioCache.save(f, TtsAudioCache.trimSilence(raw), sampleRate)
                } finally {
                    TtsGenerationTracker.markDone(bookId, def.id, sid, line)
                }
            }
            done++
            onProgress(done, lines.size)
        }
        return done
    }

    /**
     * Synthesize every not-yet-cached sentence of one downloaded chapter into the cache.
     * @return number of sentences (>=0), 0 if the chapter isn't downloaded, or -1 on failure / stop.
     */
    fun synthChapter(
        apiName: String,
        author: String?,
        name: String,
        chapterIndex: Int,
        authorNotes: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
        shouldStop: () -> Boolean,
        awaitResume: () -> Unit = {},
    ): Int {
        if (tts == null) return -1
        val loaded = context.readDownloadedChapter(apiName, author, name, chapterIndex) ?: return 0

        val rawText = TTSHelper.preParseHtml(loaded.html, authorNotes)
        val rendered = TTSHelper.render(rawText, markwon)
        val lines = TTSHelper.ttsParseText(rendered.substring(0, rendered.length), chapterIndex)
        // Prepend the chapter title line exactly as LiveChapterData.ttsLines does.
        val spokenTitle = loaded.title.trim()
        if (spokenTitle.isNotBlank()) {
            lines.add(0, TTSHelper.TTSLine(spokenTitle, startChar = 0, endChar = 0, index = chapterIndex))
        }
        return synthLines(lines, shouldStop, awaitResume, onProgress).also {
            if (it >= 0) TtsAudioCache.markChapterDone(context, bookId, def.id, sid, chapterIndex)
        }
    }
}
