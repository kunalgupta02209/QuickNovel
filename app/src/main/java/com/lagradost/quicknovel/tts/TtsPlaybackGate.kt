package com.lagradost.quicknovel.tts

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-global "the reader is actively listening" flag. Background TTS synthesis (prefetch +
 * whole-book pre-generation) yields the CPU/model to the live playback engine via [awaitClear], so
 * foreground playback never stutters. This is an OPTIMISATION for smoothness — it is NOT the
 * use-after-free safety mechanism (that is per-worker OfflineTts ownership + join-before-free).
 */
object TtsPlaybackGate {
    private val listening = AtomicBoolean(false)

    val isListening: Boolean get() = listening.get()

    fun setListening(on: Boolean) {
        listening.set(on)
    }

    /**
     * Block the calling background worker while the reader is listening (or the job is locally
     * [paused]). Polls every 150 ms. Returns false if [stop] fires (caller should abort its loop).
     * Never interrupts an in-flight generate — it is only called BETWEEN sentences.
     */
    fun awaitClear(paused: () -> Boolean, stop: () -> Boolean): Boolean {
        while ((isListening || paused()) && !stop()) {
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return !stop()
    }
}
