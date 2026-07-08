package com.lagradost.quicknovel.llm

/**
 * The two generated-script kinds. Reader display mode is a tri-state pref (0=original, 1=grammar,
 * 2=performance); ScriptType covers the generated two. dirSuffix keeps grammar's legacy cache dir
 * byte-compatible ("" = the pre-script-mode layout) while performance lives beside it.
 */
enum class ScriptType(val apiValue: String, val dirSuffix: String) {
    GRAMMAR("grammar", ""),
    PERFORMANCE("performance", "-perf");

    companion object {
        /** Reader-mode pref (0/1/2) -> the script it selects, null for 0=original. */
        fun fromReaderMode(mode: Int): ScriptType? = when (mode) {
            1 -> GRAMMAR
            2 -> PERFORMANCE
            else -> null
        }
    }
}
