package com.lagradost.quicknovel.llm

import android.content.Context
import com.lagradost.quicknovel.AbstractBook
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.tts.TtsAudioCache
import java.io.File

/**
 * On-disk cache of LLM-fixed chapter text. Written ONCE by the fixer (live or background) and read by
 * BOTH the reader ([com.lagradost.quicknovel.ReadActivityViewModel] chapter load) AND the TTS
 * synthesizer, so the two never feed different text into `ttsParseText` (which would thrash the
 * sha1(speakOutMsg) audio cache). Reuses [TtsAudioCache.bookIdFor] so a fixed chapter is found whether
 * the book is opened via stream-read (QuickBook) or Read-epub (RegularBook).
 *
 * Layout: filesDir/llm-fixed/<bookId>/<modelId>-v<promptVersion>/c<chapterIndex>.txt
 * modelId + promptVersion are in the path so switching model or editing the system prompt invalidates
 * cleanly (old fixes remain on disk until deleted, new ones live under a new sub-dir).
 */
object FixedTextCache {
    private const val ROOT = "llm-fixed"

    fun bookIdFor(book: AbstractBook): String = TtsAudioCache.bookIdFor(book)

    private fun root(ctx: Context): File = File(ctx.filesDir, ROOT)
    fun bookDir(ctx: Context, bookId: String): File = File(root(ctx), bookId)
    private fun variantDir(ctx: Context, bookId: String, modelId: String, promptVersion: Int): File =
        File(bookDir(ctx, bookId), "$modelId-v$promptVersion")

    fun chapterFile(ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int): File =
        File(variantDir(ctx, bookId, modelId, promptVersion), "c$chapterIndex.txt")

    fun isFixed(ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int): Boolean =
        chapterFile(ctx, bookId, modelId, promptVersion, chapterIndex).let { it.exists() && it.length() > 0 }

    fun load(ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int): String? =
        chapterFile(ctx, bookId, modelId, promptVersion, chapterIndex).takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }

    fun save(ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int, text: String): Boolean =
        runCatching {
            val dest = chapterFile(ctx, bookId, modelId, promptVersion, chapterIndex)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.writeText(text)
            tmp.renameTo(dest) // atomic-ish: never leave a half-written fix
        }.getOrElse { logError(it); false }

    // ---- metrics + deletion ----

    private fun dirBytes(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun bytes(ctx: Context, bookId: String, modelId: String, promptVersion: Int): Long =
        dirBytes(variantDir(ctx, bookId, modelId, promptVersion))

    fun fixedChapterCount(ctx: Context, bookId: String, modelId: String, promptVersion: Int): Int =
        variantDir(ctx, bookId, modelId, promptVersion).listFiles { f -> f.isFile && f.name.startsWith("c") && f.name.endsWith(".txt") }?.size ?: 0

    fun deleteChapter(ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int) {
        runCatching { chapterFile(ctx, bookId, modelId, promptVersion, chapterIndex).delete() }
    }

    fun deleteBook(ctx: Context, bookId: String) {
        runCatching { bookDir(ctx, bookId).deleteRecursively() }
    }
}
