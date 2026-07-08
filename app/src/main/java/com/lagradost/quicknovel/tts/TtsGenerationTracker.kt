package com.lagradost.quicknovel.tts

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lagradost.quicknovel.TTSHelper

/**
 * Process-global publisher of which sentences are CURRENTLY being synthesized by any background
 * producer (prefetch-on-open + whole-book pre-gen; later: remote). The reader observes [snapshot],
 * filters to the active book/voice, and draws a thin pulsating underline under those on-screen
 * sentences ("audio being prepared in advance here").
 *
 * Refcounted: prefetch and pre-gen can synth the same sentence concurrently, so a sentence stays
 * in flight until the LAST worker finishes it. markStart/markDone bracket the generate site in
 * [TtsChapterSynthesizer.synthLines] via try/finally, so the set auto-drains on stop/failure.
 */
object TtsGenerationTracker {
    data class InFlight(
        val bookId: String,
        val modelId: String,
        val sid: Int,
        val index: Int,
        val startChar: Int,
        val endChar: Int,
    )

    private val lock = Any()
    private val counts = HashMap<InFlight, Int>()
    private val _snapshot = MutableLiveData<Set<InFlight>>(emptySet())
    val snapshot: LiveData<Set<InFlight>> = _snapshot

    private fun publish() {
        _snapshot.postValue(synchronized(lock) { counts.keys.toHashSet() })
    }

    fun markStart(bookId: String, modelId: String, sid: Int, line: TTSHelper.TTSLine) {
        val k = InFlight(bookId, modelId, sid, line.index, line.startChar, line.endChar)
        val wasNew = synchronized(lock) {
            val c = counts.getOrDefault(k, 0)
            counts[k] = c + 1
            c == 0
        }
        if (wasNew) publish()
    }

    fun markDone(bookId: String, modelId: String, sid: Int, line: TTSHelper.TTSLine) {
        val k = InFlight(bookId, modelId, sid, line.index, line.startChar, line.endChar)
        val removed = synchronized(lock) {
            when (val c = counts.getOrDefault(k, 0)) {
                0 -> false
                1 -> { counts.remove(k); true }
                else -> { counts[k] = c - 1; false }
            }
        }
        if (removed) publish()
    }

    /** Drop every in-flight entry for a book/voice (defensive teardown on job end). */
    fun clear(bookId: String, modelId: String, sid: Int) {
        val changed = synchronized(lock) {
            val it = counts.keys.iterator()
            var any = false
            while (it.hasNext()) {
                val k = it.next()
                if (k.bookId == bookId && k.modelId == modelId && k.sid == sid) { it.remove(); any = true }
            }
            any
        }
        if (changed) publish()
    }
}
