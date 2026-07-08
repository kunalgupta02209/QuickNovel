package com.lagradost.quicknovel.tts

import android.content.Context
import com.lagradost.quicknovel.AbstractBook
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.QuickBook
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.ui.download.DownloadFragment
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * On-disk cache of synthesized on-device TTS audio, shared by the live reader engine
 * ([OnDeviceTtsEngine]) as a read-through/write-through cache AND by the background pre-generator
 * ([TtsChapterSynthesizer]). Sole owner of the cache format so the two producers can never disagree.
 *
 * Layout: filesDir/tts-cache/<bookId>/<modelId>/s<sid>/c<chapterIndex>/<sha1(speakOutMsg)[:24]>.wav
 * Files are TRIMMED 16-bit PCM mono WAV. The key excludes speed/pitch/gap (applied at playback), so
 * cached audio is reusable across every playback setting.
 */
object TtsAudioCache {
    private const val ROOT = "tts-cache"
    const val SILENCE_THRESHOLD = 0.01f // identical to the live engine's trim

    // ---- identity ----

    /**
     * Cache identity for a book. Downloaded stream books (QuickBook) use the download identity
     * "b<generateId(apiName,author,name)>". An EPUB opened in the reader (RegularBook) has no
     * provider name, so we match it back to a downloaded book by title (+author) and reuse that
     * download identity — this is what lets pre-generated audio be reused when reading the EPUB, not
     * just when stream-reading. Falls back to a title hash when there is no matching download.
     */
    fun bookIdFor(book: AbstractBook): String = when (book) {
        is QuickBook -> quickBookId(book.data.meta.apiName, book.data.meta.author, book.data.meta.name)
        else -> resolveDownloadedBookId(book) ?: ("h" + book.title().hashCode())
    }

    fun quickBookId(apiName: String, author: String?, name: String): String =
        "b" + BookDownloader2Helper.generateId(apiName, author, name)

    /**
     * Match an EPUB/imported book back to a downloaded book by name (and author when available), so
     * its cache id equals the download identity the pre-generator used. Prefers a name+author match;
     * falls back to name-only. Runs once per playback start, so a linear scan of the library is fine.
     */
    private fun resolveDownloadedBookId(book: AbstractBook): String? {
        val title = book.title().trim()
        if (title.isBlank()) return null
        val author = book.author()?.trim()?.takeIf { it.isNotBlank() }
        val keys = getKeys(DOWNLOAD_FOLDER) ?: return null
        var titleOnlyMatch: DownloadFragment.DownloadData? = null
        for (key in keys) {
            val d = runCatching { getKey<DownloadFragment.DownloadData>(key) }.getOrNull() ?: continue
            if (!d.name.trim().equals(title, ignoreCase = true)) continue
            if (author != null && d.author?.trim()?.equals(author, ignoreCase = true) == true) {
                return quickBookId(d.apiName, d.author, d.name) // best: name + author
            }
            if (titleOnlyMatch == null) titleOnlyMatch = d
        }
        return titleOnlyMatch?.let { quickBookId(it.apiName, it.author, it.name) }
    }

    // ---- paths ----

    private fun root(ctx: Context): File = File(ctx.filesDir, ROOT)
    fun bookDir(ctx: Context, bookId: String): File = File(root(ctx), bookId)

    fun chapterDir(ctx: Context, bookId: String, modelId: String, sid: Int, chapterIndex: Int): File =
        File(bookDir(ctx, bookId), "$modelId/s$sid/c$chapterIndex")

    /**
     * Per-sentence WAV. [variant] distinguishes post-processing that changes the bytes on disk —
     * "" = raw model output (what the pre-generator/prefetch write, backward-compatible), ".dn1" =
     * GTCRN-denoised (written only by live playback when the denoiser is on). This makes the denoise
     * toggle actually affect cached audio instead of silently reusing a raw cache hit.
     */
    fun fileFor(ctx: Context, bookId: String, modelId: String, sid: Int, line: TTSHelper.TTSLine, variant: String = ""): File =
        File(chapterDir(ctx, bookId, modelId, sid, line.index), keyFor(line) + variant + ".wav")

    /** The content hash a sentence's WAV is filed under — shared with the server so its output is
     *  byte-placeable into this cache. Must stay in lockstep with [fileFor]. */
    fun keyFor(line: TTSHelper.TTSLine): String = sha1Hex(line.speakOutMsg).take(24)

    /** Text-keyed variant for cue rendering (P5): the EFFECTIVE synthesized text (e.g. with a
     *  Supertonic <laugh> tag) can differ from the display line, and the voice can be a cast sid. */
    fun fileForText(
        ctx: Context, bookId: String, modelId: String, sid: Int, chapterIndex: Int,
        text: String, variant: String = "",
    ): File = File(chapterDir(ctx, bookId, modelId, sid, chapterIndex), sha1Hex(text).take(24) + variant + ".wav")

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---- trim (moved from OnDeviceTtsEngine so cache == live audio) ----

    fun trimSilence(pcm: FloatArray): FloatArray {
        if (pcm.isEmpty()) return pcm
        var start = 0
        while (start < pcm.size && kotlin.math.abs(pcm[start]) < SILENCE_THRESHOLD) start++
        var end = pcm.size
        while (end > start && kotlin.math.abs(pcm[end - 1]) < SILENCE_THRESHOLD) end--
        if (start >= end) return FloatArray(0)
        return if (start == 0 && end == pcm.size) pcm else pcm.copyOfRange(start, end)
    }

    // ---- WAV round-trip (16-bit PCM mono; our own fixed 44-byte header) ----

    fun save(dest: File, trimmed: FloatArray, sampleRate: Int): Boolean = runCatching {
        dest.parentFile?.mkdirs()
        val dataSize = trimmed.size * 2
        val bb = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)); bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)); bb.putInt(16)
        bb.putShort(1); bb.putShort(1); bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)); bb.putInt(dataSize)
        for (s in trimmed) bb.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        // Unique temp name: multiple pool worker threads (and the live engine) may write the same
        // sentence concurrently; a fixed ".part" would collide. Last writer wins (bytes identical).
        val tmp = File(dest.parentFile, "${dest.name}.${Thread.currentThread().id}.${System.nanoTime()}.part")
        FileOutputStream(tmp).use { it.write(bb.array()) }
        tmp.renameTo(dest) // atomic-ish: never leave a half-written .wav
    }.getOrElse { logError(it); false }

    fun load(src: File): FloatArray? = runCatching {
        val bytes = src.readBytes()
        if (bytes.size <= 44) return@runCatching null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataSize = bb.getInt(40)
        val n = minOf(dataSize, bytes.size - 44) / 2
        val out = FloatArray(n)
        bb.position(44)
        for (i in 0 until n) out[i] = bb.short / 32768f
        out
    }.getOrNull()

    // ---- chapter completion + metrics ----

    private fun doneMarker(ctx: Context, bookId: String, modelId: String, sid: Int, chapterIndex: Int): File =
        File(chapterDir(ctx, bookId, modelId, sid, chapterIndex), ".done")

    fun markChapterDone(ctx: Context, bookId: String, modelId: String, sid: Int, chapterIndex: Int) {
        runCatching { doneMarker(ctx, bookId, modelId, sid, chapterIndex).writeText("ok") }
    }

    fun isChapterDone(ctx: Context, bookId: String, modelId: String, sid: Int, chapterIndex: Int): Boolean =
        doneMarker(ctx, bookId, modelId, sid, chapterIndex).exists()

    private fun dirBytes(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun voiceBytes(ctx: Context, bookId: String, modelId: String, sid: Int): Long =
        dirBytes(File(bookDir(ctx, bookId), "$modelId/s$sid"))

    /** Number of chapters with a .done marker under this voice. */
    fun doneChapterCount(ctx: Context, bookId: String, modelId: String, sid: Int): Int {
        val voiceDir = File(bookDir(ctx, bookId), "$modelId/s$sid")
        if (!voiceDir.isDirectory) return 0
        return voiceDir.listFiles { f -> f.isDirectory && f.name.startsWith("c") }
            ?.count { File(it, ".done").exists() } ?: 0
    }

    /** One populated voice directory in the cache (for telemetry: which books have TTS audio). */
    data class VoiceRef(
        val bookId: String,
        val modelId: String,
        val sid: Int,
        val chaptersDone: Int,
        val bytes: Long,
    )

    /** Walk the whole cache tree (3 shallow levels) — every book/model/voice with audio on disk. */
    fun allVoices(ctx: Context): List<VoiceRef> = runCatching {
        val out = ArrayList<VoiceRef>()
        val rootDir = root(ctx)
        rootDir.listFiles { f -> f.isDirectory }?.forEach { book ->
            book.listFiles { f -> f.isDirectory }?.forEach { model ->
                model.listFiles { f -> f.isDirectory && f.name.startsWith("s") }?.forEach { voice ->
                    val sid = voice.name.removePrefix("s").toIntOrNull() ?: return@forEach
                    out.add(
                        VoiceRef(
                            bookId = book.name, modelId = model.name, sid = sid,
                            chaptersDone = voice.listFiles { f -> f.isDirectory && f.name.startsWith("c") }
                                ?.count { File(it, ".done").exists() } ?: 0,
                            bytes = dirBytes(voice),
                        )
                    )
                }
            }
        }
        out
    }.getOrElse { emptyList() }

    // ---- deletion ----

    fun deleteChapter(ctx: Context, bookId: String, modelId: String, sid: Int, chapterIndex: Int) {
        runCatching { chapterDir(ctx, bookId, modelId, sid, chapterIndex).deleteRecursively() }
    }

    fun deleteVoice(ctx: Context, bookId: String, modelId: String, sid: Int) {
        runCatching { File(bookDir(ctx, bookId), "$modelId/s$sid").deleteRecursively() }
    }

    fun deleteBook(ctx: Context, bookId: String) {
        runCatching { bookDir(ctx, bookId).deleteRecursively() }
    }
}
