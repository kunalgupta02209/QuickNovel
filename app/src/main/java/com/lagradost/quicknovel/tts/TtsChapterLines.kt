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

    /** @return the chapter's spoken lines, or null if the chapter isn't downloaded. */
    fun build(
        context: Context,
        apiName: String,
        author: String?,
        name: String,
        chapterIndex: Int,
        authorNotes: Boolean,
    ): List<TTSHelper.TTSLine>? {
        val loaded = context.readDownloadedChapter(apiName, author, name, chapterIndex) ?: return null
        val rawText = TTSHelper.preParseHtml(loaded.html, authorNotes)
        val rendered = TTSHelper.render(rawText, markwon(context))
        val lines = TTSHelper.ttsParseText(rendered.substring(0, rendered.length), chapterIndex)
        val spokenTitle = loaded.title.trim()
        if (spokenTitle.isNotBlank()) {
            lines.add(0, TTSHelper.TTSLine(spokenTitle, startChar = 0, endChar = 0, index = chapterIndex))
        }
        return lines
    }
}
