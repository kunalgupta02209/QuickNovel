package com.lagradost.quicknovel.tts

import android.content.Context
import com.lagradost.quicknovel.BookDownloader2Helper.readDownloadedChapter
import com.lagradost.quicknovel.TTSHelper
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.html.HtmlPlugin

/**
 * Builds a downloaded chapter's spoken [TTSHelper.TTSLine]s WITHOUT any OfflineTts — the single
 * source of truth for the text pipeline used by live playback, on-device pre-gen, AND the server
 * offload. Reproducing it exactly (preParseHtml -> markwon -> ttsParseText + a prepended title line)
 * is what makes the content-hash cache keys line up byte-for-byte, so server-synthesized audio drops
 * straight into the cache.
 */
object TtsChapterLines {
    // One Markwon per app context (the key is the singleton applicationContext).
    private val markwonCache = HashMap<Context, Markwon>()

    private fun markwon(context: Context): Markwon = synchronized(markwonCache) {
        markwonCache.getOrPut(context.applicationContext) {
            Markwon.builder(context.applicationContext)
                .usePlugin(HtmlPlugin.create { it.excludeDefaults(false) })
                .usePlugin(SoftBreakAddsNewLinePlugin.create())
                .build()
        }
    }

    /** The generated-script substitution live playback applies (ReadActivityViewModel.maybeFixedText)
     *  — MUST mirror it exactly or pre-gen/remote audio keys miss the live cache. Reads the prefs
     *  statically (no ViewModel). */
    private fun maybeScriptHtml(context: Context, bookId: String?, chapterIndex: Int, rawHtml: String): String {
        bookId ?: return rawHtml
        val mode = runCatching {
            com.lagradost.quicknovel.BaseApplication.getKeyClass(
                com.lagradost.quicknovel.LLM_FIX_SCRIPT_MODE, Int::class.javaObjectType
            )
        }.getOrNull().takeIf { it != null && it >= 0 } ?: runCatching {
            com.lagradost.quicknovel.BaseApplication.getKeyClass(
                com.lagradost.quicknovel.LLM_FIX_SHOW_FIXED, Boolean::class.javaObjectType
            )
        }.getOrNull().let { if (it == true) 1 else 0 }
        val script = com.lagradost.quicknovel.llm.ScriptType.fromReaderMode(mode) ?: return rawHtml
        val model = runCatching {
            com.lagradost.quicknovel.BaseApplication.getKeyClass(
                com.lagradost.quicknovel.LLM_FIX_MODEL, String::class.java
            )
        }.getOrNull() ?: "qwen2.5-1.5b"
        val ver = runCatching {
            com.lagradost.quicknovel.BaseApplication.getKeyClass(
                com.lagradost.quicknovel.LLM_FIX_PROMPT_VERSION, Int::class.javaObjectType
            )
        }.getOrNull() ?: 1
        val fixed = com.lagradost.quicknovel.llm.FixedTextCache
            .load(context, bookId, model, ver, chapterIndex, script) ?: return rawHtml
        return fixed.split(Regex("\n{2,}")).filter { it.isNotBlank() }
            .joinToString("\n") { "<p>" + it.trim().replace("\n", " ") + "</p>" }
    }

    /** @return the chapter's spoken lines, or null if the chapter isn't downloaded. [bookId] enables
     *  the script-mode substitution (pass it from synth/pre-gen callers; null = raw text). */
    fun build(
        context: Context,
        apiName: String,
        author: String?,
        name: String,
        chapterIndex: Int,
        authorNotes: Boolean,
        bookId: String? = null,
    ): List<TTSHelper.TTSLine>? {
        val loaded = context.readDownloadedChapter(apiName, author, name, chapterIndex) ?: return null
        val html = maybeScriptHtml(context, bookId, chapterIndex, loaded.html)
        val rawText = TTSHelper.preParseHtml(html, authorNotes)
        val rendered = TTSHelper.render(rawText, markwon(context))
        val lines = TTSHelper.ttsParseText(rendered.substring(0, rendered.length), chapterIndex)
        val spokenTitle = loaded.title.trim()
        if (spokenTitle.isNotBlank()) {
            lines.add(0, TTSHelper.TTSLine(spokenTitle, startChar = 0, endChar = 0, index = chapterIndex))
        }
        return lines
    }
}
