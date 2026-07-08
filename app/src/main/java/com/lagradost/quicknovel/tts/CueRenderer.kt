package com.lagradost.quicknovel.tts

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.DataStore.mapper
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.llm.FixedTextCache
import com.lagradost.quicknovel.mvvm.logError
import java.io.File

/**
 * QN-Cue rendering (P5): turns a performance ScriptDoc + the server character-map casting into
 * per-line synthesis directives. This is a CUE-ABSTRACTION layer (research-verified): the sherpa
 * API only offers sid + speed, so events become pauses (except Supertonic's working <laugh> tag),
 * deliveries become speed + DSP presets, and speakers become per-line sid switches.
 */
object CueRenderer {

    /** Everything the synthesizer needs to render one line in character. */
    data class CueDirective(
        val sid: Int? = null,          // per-line voice (casting); null = the user's chosen voice
        val speed: Float = 1.0f,       // per-character pace multiplier
        val pauseBeforeMs: Int = 0,
        val delivery: String = "neutral",
        val effectiveText: String,     // what is actually synthesized (Supertonic tag injection)
    ) {
        /** Cache identity differs from the raw line when voice/text/delivery-speed differ. */
        val cacheText: String get() = effectiveText
    }

    private const val EVENT_PAUSE_MS = 350

    // ---- on-device charmap cache ------------------------------------------------------------

    private fun castFile(ctx: Context, bookId: String): File =
        File(File(ctx.filesDir, "charmap"), "$bookId.json")

    /** Refresh the local copy of the server map (fire-and-forget; blocking -> IO). */
    fun syncMap(ctx: Context, serverUrl: String, bookId: String) {
        runCatching {
            val node = com.lagradost.quicknovel.llm.CharMapClient.map(serverUrl, bookId) ?: return
            val f = castFile(ctx, bookId)
            f.parentFile?.mkdirs()
            f.writeText(node.toString())
        }.onFailure { logError(it) }
    }

    fun loadMap(ctx: Context, bookId: String): JsonNode? =
        castFile(ctx, bookId).takeIf { it.exists() }
            ?.let { f -> runCatching { mapper.readTree(f.readText()) }.getOrNull() }

    // ---- alignment ---------------------------------------------------------------------------

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Build a resolver for one chapter: TTSLine -> CueDirective. Returns null when there is nothing
     * to apply (no ScriptDoc, casting off and no cues). [modelId]/[defaultSid] describe the ACTIVE
     * voice; cast sids are honored only when the map's casting model matches the active model.
     */
    fun resolverFor(
        ctx: Context,
        bookId: String,
        modelId: String,
        llmModel: String,
        promptVersion: Int,
        chapterIndex: Int,
        castingEnabled: Boolean,
    ): ((TTSHelper.TTSLine) -> CueDirective?)? {
        val doc = FixedTextCache.loadDoc(
            ctx, bookId, llmModel, promptVersion, chapterIndex,
            com.lagradost.quicknovel.llm.ScriptType.PERFORMANCE,
        ) ?: return null

        // speaker "char:<id>" -> (sid, speed) from the cached map casting
        val castBySpeaker = HashMap<String, Pair<Int?, Float>>()
        var narratorCast: Pair<Int?, Float> = null to 1.0f
        if (castingEnabled) {
            val m = loadMap(ctx, bookId)
            m?.get("characters")?.forEach { c ->
                val id = c.get("id")?.asText() ?: return@forEach
                val cast = c.get("casting") ?: return@forEach
                val sid = cast.get("sid")?.asInt()?.takeIf { cast.get("model")?.asText() == modelId }
                castBySpeaker["char:$id"] = sid to (cast.get("speed")?.floatValue() ?: 1.0f)
            }
            m?.get("narration")?.get("casting")?.let { cast ->
                narratorCast = cast.get("sid")?.asInt()
                    ?.takeIf { cast.get("model")?.asText() == modelId } to
                        (cast.get("speed")?.floatValue() ?: 1.0f)
            }
        }

        val supertonic = modelId == "supertonic"
        data class SpanEntry(val normText: String, val directive: CueDirective)

        val entries = ArrayList<SpanEntry>()
        for (p in doc) {
            for (span in p.spans) {
                val (sid, speed) = if (span.speaker == "narrator") narratorCast
                else castBySpeaker[span.speaker] ?: narratorCast
                var pause = 0
                var text = span.text
                for (e in span.events) {
                    // The ONLY verified working inline tag is Supertonic's <laugh>; every other
                    // event on every engine becomes a dramatic beat (pause).
                    if (supertonic && (e.tag == "laugh" || e.tag == "chuckle")) {
                        text = "<laugh> $text"
                    } else {
                        pause += EVENT_PAUSE_MS
                    }
                }
                entries.add(
                    SpanEntry(
                        norm(span.text),
                        CueDirective(
                            sid = sid, speed = speed, pauseBeforeMs = pause,
                            delivery = span.delivery, effectiveText = text,
                        ),
                    )
                )
            }
        }
        if (entries.isEmpty()) return null

        return fun(line: TTSHelper.TTSLine): CueDirective? {
            val n = norm(line.speakOutMsg)
            if (n.isEmpty()) return null
            val hit = entries.firstOrNull { it.normText == n }
                ?: entries.firstOrNull { it.normText.contains(n) }
                ?: return null
            val d = hit.directive
            // A line is usually a SENTENCE within the span: only inject the Supertonic tag/pause on
            // the span's first sentence (identified by prefix match), else strip them.
            val isSpanStart = hit.normText.startsWith(n) || hit.normText == n
            return if (isSpanStart) {
                d.copy(effectiveText = if (d.effectiveText.startsWith("<laugh>"))
                    "<laugh> ${line.speakOutMsg}" else line.speakOutMsg)
            } else {
                d.copy(pauseBeforeMs = 0, effectiveText = line.speakOutMsg)
            }
        }
    }
}
