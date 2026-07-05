package com.lagradost.quicknovel.llm

/**
 * Engine seam for the on-device prose fixer, mirroring the `TtsEngine` abstraction so the reader,
 * background worker, and memory-graph extractor never touch JNI directly. The concrete implementation
 * ([LlamaCppProseFixer]) wraps llama.cpp; this interface keeps everything above it engine-agnostic.
 */
interface ProseFixerEngine {
    /** Load the GGUF model (heavy, blocking C++). Returns true on success. */
    suspend fun load(): Boolean

    /** Run one completion for [prompt] (already ChatML-formatted) and return the full generated text.
     *  [onToken] is invoked for each streamed token, for live UI display. */
    suspend fun generate(prompt: String, onToken: ((String) -> Unit)? = null): String

    fun close()

    val isLoaded: Boolean
}

/**
 * Builds the ChatML prompts for the two LLM passes (fix + character extraction). Kept separate from the
 * engine so prompt engineering is testable and the default system prompt lives in one place.
 */
object ProseFixPrompt {

    /** The built-in default system prompt (editable in Settings; stored blank = use this). */
    const val DEFAULT_SYSTEM_PROMPT: String = """You are a literary editor who repairs machine-translated web-novel chapters so they read like fluent, natural English written by a native author, and so they sound good when read aloud by a text-to-speech voice. The chapter below was originally written in Chinese, Korean, or Japanese and machine-translated, so expect wrong-gender pronouns, literal honorific calques, stiff or inverted word order, missing articles, tense drift, and awkward rhythm.

YOUR TASK: Rewrite the CHAPTER TO REWRITE below into clean, natural English. Fix grammar, pronoun gender, tense consistency, articles, prepositions, and unnatural phrasing, and smooth the sentence rhythm for spoken delivery. Do NOT summarize, shorten, expand, reorder, or invent any event, line of dialogue, or description. Preserve the author's meaning and every plot beat exactly.

HARD RULES:
1. Preserve VERBATIM every proper noun: character names, place names, sect / clan / guild / kingdom names, cultivation or power ranks, and technique / skill / item names. Never translate or localize a proper noun. If a name is spelled inconsistently in the source, use the spelling given in CHARACTER MEMORY.
2. Use CHARACTER MEMORY to keep continuity: apply each character's correct pronouns, do not swap a "she" to "he" or vice-versa against the memory, and keep relationships consistent with it.
3. Honorifics: convert kinship or status honorifics into natural English. Render "gege / oppa / onii-san" as the relationship or the person's name; render "-nim / -ssi / -sama / -shixiong" as a respectful English form or drop it. Keep an honorific only when the scene's meaning depends on it.
4. Output PLAIN PROSE ONLY. No Markdown, HTML, headings, bullet or numbered lists, asterisks, tables, code fences, translator notes, "TL:" lines, or author's-note blocks. Remove anything that is not the story itself.
5. Write out every number, ordinal, date, currency amount, and abbreviation in words (for example "3rd" becomes "third", "5,000 spirit stones" becomes "five thousand spirit stones", "Dr." becomes "Doctor"), so the voice never mis-reads them.
6. Do not use em dashes or en dashes; use commas, periods, or "and" / "but". Do not use "..." for pauses; end the sentence or use a comma. Attribute dialogue in standard English order: "Come with me," she said.
7. Keep paragraphs short, three to five sentences each, separated by a single blank line. Keep most sentences under about twenty-five words.
8. Output ONLY the rewritten chapter. No preamble, title, explanation, or closing remark.
9. Never repeat a sentence or a paragraph. Rewrite each part of the text exactly once, then stop; do not pad, summarize, or loop."""

    /** One extra line appended to the system prompt when the active TTS voice is Supertonic. */
    const val SUPERTONIC_LINE: String =
        "\nYou may place exactly one of <laugh>, <breath>, or <sigh> on its own line between sentences, and only where the text explicitly describes that action. Use no other angle-bracket tags."

    /** Grammar-free JSON extraction system prompt (we parse defensively since the binding has no GBNF). */
    const val EXTRACT_SYSTEM_PROMPT: String =
        "You extract characters from a web-novel chapter. Output ONLY a JSON array (no prose, no code fences) " +
        "of objects with keys: name (canonical string), aliases (string array), gender (\"male\"|\"female\"|\"unknown\"), " +
        "pronouns (e.g. \"he/him\"), traits (string array, max 4), relationships (array of {target, type}). " +
        "Reuse the exact spelling of any name given in KNOWN NAMES. Include only people who appear in this chapter."

    private fun chatml(system: String, user: String): String =
        "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    /**
     * Build the fix prompt. [systemPrompt] is the (possibly user-edited) system prompt; [previousChapters]
     * is a plain-text tail of the last N fixed/raw chapters (may be blank); [characterMemory] is the
     * formatted memory block (may be blank); [chapterText] is the raw chapter body to rewrite.
     */
    fun buildFixPrompt(
        systemPrompt: String,
        previousChapters: String,
        characterMemory: String,
        chapterText: String,
        supertonic: Boolean,
    ): String {
        val sys = (systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }) + (if (supertonic) SUPERTONIC_LINE else "")
        val user = buildString {
            append("RECENT STORY SO FAR (context only — do NOT copy any of this into your output):\n")
            append(previousChapters.ifBlank { "(none)" }).append("\n\n")
            append("CHARACTER MEMORY (context only — names, pronouns, relationships to keep consistent; do NOT copy into your output):\n")
            append(characterMemory.ifBlank { "(none)" }).append("\n\n")
            append("CHAPTER TO REWRITE:\n").append(chapterText)
        }
        return chatml(sys, user)
    }

    fun buildExtractPrompt(knownNames: List<String>, chapterText: String): String {
        val user = buildString {
            append("KNOWN NAMES: ").append(if (knownNames.isEmpty()) "(none yet)" else knownNames.joinToString(", ")).append("\n\n")
            append("CHAPTER:\n").append(chapterText)
        }
        return chatml(EXTRACT_SYSTEM_PROMPT, user)
    }

    /** Setting summary system prompt — one short paragraph of genre / world / era / tone. */
    const val SETTING_SYSTEM_PROMPT: String =
        "You summarize the SETTING of a web novel from a chapter. Reply with ONE or TWO plain sentences only " +
        "(no lists, no preamble, no headings) covering: the genre (for example cultivation / xianxia / wuxia, " +
        "system or LitRPG, romance, modern), the world and era, and the overall tone. Be concise and factual."

    fun buildSettingPrompt(chapterText: String): String =
        chatml(SETTING_SYSTEM_PROMPT, "CHAPTER:\n" + chapterText.take(4000))
}
