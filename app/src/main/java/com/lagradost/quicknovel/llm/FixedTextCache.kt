package com.lagradost.quicknovel.llm

import android.content.Context
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.AbstractBook
import com.lagradost.quicknovel.DataStore
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.tts.TtsAudioCache
import java.io.File

/**
 * On-disk cache of LLM-generated chapter scripts. Written ONCE by the fixer (live or background) and
 * read by BOTH the reader AND the TTS synthesizer, so the two never feed different text into
 * `ttsParseText` (which would thrash the sha1(speakOutMsg) audio cache).
 *
 * Layout: filesDir/llm-fixed/<bookId>/<modelId>-v<promptVersion><dirSuffix>/c<chapterIndex>.txt
 * GRAMMAR keeps the legacy dir name ("" suffix) so pre-script-mode caches stay valid; PERFORMANCE
 * lives beside it ("-perf") with an additional c<idx>.json ScriptDoc (per-paragraph cue spans).
 * Both scripts coexist per chapter; switching modes never destroys the other.
 */
object FixedTextCache {
    private const val ROOT = "llm-fixed"

    /** One paragraph's cue-annotated spans (QN-Cue v1); mirrors the server's span JSON. */
    data class CueEvent(val tag: String = "", val pos: String = "before")
    data class Span(
        val text: String = "",
        val speaker: String = "narrator",
        val delivery: String = "neutral",
        val events: List<CueEvent> = emptyList(),
    )
    data class Paragraph(val i: Int = 0, val src: String = "llm", val spans: List<Span> = emptyList())
    data class ScriptDoc(
        val v: Int = 2,
        val scriptType: String = ScriptType.PERFORMANCE.apiValue,
        val paragraphs: List<Paragraph> = emptyList(),
    )

    fun bookIdFor(book: AbstractBook): String = TtsAudioCache.bookIdFor(book)

    private fun root(ctx: Context): File = File(ctx.filesDir, ROOT)
    fun bookDir(ctx: Context, bookId: String): File = File(root(ctx), bookId)
    private fun variantDir(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int,
        script: ScriptType = ScriptType.GRAMMAR,
    ): File = File(bookDir(ctx, bookId), "$modelId-v$promptVersion${script.dirSuffix}")

    fun chapterFile(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int,
        script: ScriptType = ScriptType.GRAMMAR,
    ): File = File(variantDir(ctx, bookId, modelId, promptVersion, script), "c$chapterIndex.txt")

    private fun docFile(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int,
        script: ScriptType,
    ): File = File(variantDir(ctx, bookId, modelId, promptVersion, script), "c$chapterIndex.json")

    fun isFixed(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int,
        script: ScriptType = ScriptType.GRAMMAR,
    ): Boolean = chapterFile(ctx, bookId, modelId, promptVersion, chapterIndex, script)
        .let { it.exists() && it.length() > 0 }

    fun load(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int,
        script: ScriptType = ScriptType.GRAMMAR,
    ): String? = chapterFile(ctx, bookId, modelId, promptVersion, chapterIndex, script)
        .takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun save(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int,
        text: String, script: ScriptType = ScriptType.GRAMMAR,
    ): Boolean = runCatching {
        val dest = chapterFile(ctx, bookId, modelId, promptVersion, chapterIndex, script)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.writeText(text)
        tmp.renameTo(dest) // atomic-ish: never leave a half-written fix
    }.getOrElse { logError(it); false }

    /** Persist the performance ScriptDoc (cue spans) beside the display text. */
    fun saveDoc(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int,
        script: ScriptType, paragraphsJson: String,
    ): Boolean = runCatching {
        val dest = docFile(ctx, bookId, modelId, promptVersion, chapterIndex, script)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.writeText(paragraphsJson)
        tmp.renameTo(dest)
    }.getOrElse { logError(it); false }

    fun loadDoc(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int,
        script: ScriptType,
    ): List<Paragraph>? = docFile(ctx, bookId, modelId, promptVersion, chapterIndex, script)
        .takeIf { it.exists() }
        ?.let { f ->
            runCatching { DataStore.mapper.readValue<List<Paragraph>>(f.readText()) }.getOrNull()
        }

    // ---- metrics + deletion ----

    private fun dirBytes(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun bytes(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int,
        script: ScriptType = ScriptType.GRAMMAR,
    ): Long = dirBytes(variantDir(ctx, bookId, modelId, promptVersion, script))

    fun fixedChapterCount(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int,
        script: ScriptType = ScriptType.GRAMMAR,
    ): Int = variantDir(ctx, bookId, modelId, promptVersion, script)
        .listFiles { f -> f.isFile && f.name.startsWith("c") && f.name.endsWith(".txt") }?.size ?: 0

    fun deleteChapter(
        ctx: Context, bookId: String, modelId: String, promptVersion: Int, chapterIndex: Int,
        script: ScriptType = ScriptType.GRAMMAR,
    ) {
        runCatching { chapterFile(ctx, bookId, modelId, promptVersion, chapterIndex, script).delete() }
        runCatching { docFile(ctx, bookId, modelId, promptVersion, chapterIndex, script).delete() }
    }

    fun deleteBook(ctx: Context, bookId: String) {
        runCatching { bookDir(ctx, bookId).deleteRecursively() }
    }
}
