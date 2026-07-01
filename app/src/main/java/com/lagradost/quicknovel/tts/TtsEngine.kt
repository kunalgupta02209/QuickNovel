package com.lagradost.quicknovel.tts

import com.lagradost.quicknovel.TTSHelper

/**
 * The reader's Read-Aloud playback loop (`ReadActivityViewModel.startTTSThread`) talks to its TTS
 * engine only through this small surface. [com.lagradost.quicknovel.TTSSession] (the Android
 * platform `TextToSpeech`) is the default implementation; an on-device neural engine
 * (sherpa-onnx) is added as a second implementation.
 *
 * System-only concepts (device voices, `Locale`, `requireTTS`) are intentionally NOT on this
 * interface — call sites reach them via `as? TTSSession`.
 */
interface TtsEngine {
    fun register()
    fun unregister()
    fun release()
    fun interruptTTS()
    fun isValidTTS(): Boolean
    fun ttsInitialized(): Boolean
    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)

    /**
     * Speak [line] now and pre-queue as many of [upcoming] as the engine buffers ahead (the system
     * engine uses only the first; a neural engine renders the whole window). Returns a monotonic
     * utterance id for [waitForOr], or null if the engine could not start.
     */
    suspend fun speak(
        line: TTSHelper.TTSLine,
        upcoming: List<TTSHelper.TTSLine>,
        action: () -> Boolean
    ): Int?

    /** Suspends until utterance [id] has finished, or [action] becomes true (then interrupts + runs [then]). */
    suspend fun waitForOr(id: Int?, action: () -> Boolean, then: () -> Unit)

    // Capability flags used by the reader UI / loop.
    /** Neural models have no pitch control → hide the pitch slider. */
    val supportsPitch: Boolean get() = true

    /** Only the system engine exposes device voices/languages. */
    val supportsSystemLanguagePicker: Boolean get() = true

    /** When true, the engine posts the highlighted line itself (audio-synced) instead of the loop. */
    val drivesOwnHighlight: Boolean get() = false
}
