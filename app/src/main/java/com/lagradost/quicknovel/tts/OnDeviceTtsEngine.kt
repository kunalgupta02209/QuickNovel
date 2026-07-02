package com.lagradost.quicknovel.tts

import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.receivers.BecomingNoisyReceiver
import com.lagradost.quicknovel.util.UIHelper.requestAudioFocus
import com.lagradost.quicknovel.util.UIHelper.unRequestAudioFocus
import kotlinx.coroutines.delay

/**
 * On-device neural TTS engine (embedded sherpa-onnx `OfflineTts`).
 *
 * The model is non-streaming per sentence (it fully synthesizes a sentence before any audio), so to
 * get gapless playback we decouple synthesis from playback with a producer/consumer over a
 * never-stopped [AudioTrack] (MODE_STREAM, PCM_FLOAT):
 *
 *  - a **producer** thread renders the next few sentences (the look-ahead window) into memory while
 *    the current one plays;
 *  - a **consumer** thread writes rendered PCM back-to-back into the track — consecutive writes into
 *    a never-stopped stream track are gapless.
 *
 * The reader loop ([com.lagradost.quicknovel.ReadActivityViewModel.startTTSThread]) drives this
 * through the same [TtsEngine] contract as the system engine: [speak] enqueues `line` + its
 * `upcoming` window and returns a monotonic id; [waitForOr] blocks until that id has been played.
 * Skip/pause arrive as [interruptTTS] (flush) + a re-`speak`, exactly like the system path.
 */
class OnDeviceTtsEngine(
    context: Context,
    modelId: String,
    voiceId: String,
    lookahead: Int,
    private val event: (TTSHelper.TTSActionType) -> Boolean,
) : TtsEngine {
    private val appContext = context.applicationContext
    private val def = TtsModels.byId(modelId)
    private val sid: Int = (TtsModels.parseVoice(voiceId)?.second ?: 0)
        .coerceIn(0, (def.speakerCount - 1).coerceAtLeast(0))

    @Volatile private var lookahead: Int = lookahead.coerceIn(1, MAX_LOOKAHEAD)
    @Volatile private var speed: Float = 1.0f

    /** Posted (from the audio thread) when a sentence actually starts playing → drives the highlight. */
    var onAudibleLine: ((TTSHelper.TTSLine) -> Unit)? = null

    override val supportsPitch: Boolean get() = false
    override val supportsSystemLanguagePicker: Boolean get() = false
    override val drivesOwnHighlight: Boolean get() = true

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    private var sampleRate = 24000

    // ---- audio focus + ducking + becoming-noisy (headphone unplug) ----
    private var focusRequest: AudioFocusRequest? = null
    private val noisyReceiver = BecomingNoisyReceiver()
    private val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    @Volatile private var focusRegistered = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                event(TTSHelper.TTSActionType.Pause)
            // A self-managed AudioTrack does not auto-duck — lower the volume ourselves.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                runCatching { track?.setVolume(DUCK_VOLUME) }
            AudioManager.AUDIOFOCUS_GAIN ->
                runCatching { track?.setVolume(1.0f) }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnAudioFocusChangeListener(focusListener)
                build()
            }
        }
    }

    private class Slot(val id: Int, val line: TTSHelper.TTSLine) {
        @Volatile var pcm: FloatArray? = null
        @Volatile var rendered = false
        @Volatile var failed = false
        @Volatile var cancelled = false
    }

    // All slot/cursor mutation is guarded by [lock]; lock.wait/notify coordinates producer+consumer.
    private val lock = Object()
    private val slots = ArrayList<Slot>()
    private var playIndex = 0            // consumer-owned; the slot currently playing
    private var nextId = 1
    @Volatile private var endId = 0      // highest id that has finished playing
    @Volatile private var running = false
    private var producer: Thread? = null
    private var consumer: Thread? = null

    override fun isValidTTS(): Boolean = tts != null && track != null
    override fun ttsInitialized(): Boolean = tts != null

    override fun register() {
        if (running) return
        // Build the model (heavy int8 ONNX load) — register() runs on the reader's IO coroutine.
        val config = TtsModels.resolveConfig(appContext, def)
        if (config != null) {
            tts = try {
                OfflineTts(assetManager = null, config = config)
            } catch (t: Throwable) {
                logError(t); null
            }
        }
        sampleRate = tts?.sampleRate() ?: 24000

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)
        // ~0.5 s of headroom (float mono: sampleRate*4 bytes = 1 s). A larger track buffer rides out
        // audio-HAL stalls (esp. the emulator, which underruns/glitches easily) at the cost of a
        // little highlight lead. Synthesis is far faster than real-time so PCM is always ready.
        val bufBytes = maxOf(minBuf, sampleRate * 2)
        track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { applySpeed(it); it.play() }
        } catch (t: Throwable) {
            logError(t); null
        }

        if (!focusRegistered) {
            focusRegistered = true
            appContext.requestAudioFocus(focusRequest)
            runCatching { appContext.registerReceiver(noisyReceiver, noisyFilter) }
        }

        running = true
        consumer = Thread({ consumeLoop() }, "tts-audio").apply { isDaemon = true; start() }
        producer = Thread({ produceLoop() }, "tts-synth").apply { isDaemon = true; start() }
    }

    override fun unregister() {
        if (!focusRegistered) return
        focusRegistered = false
        appContext.unRequestAudioFocus(focusRequest)
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
    }

    override fun release() {
        running = false
        unregister()
        synchronized(lock) {
            slots.forEach { it.cancelled = true }
            slots.clear(); playIndex = 0
            lock.notifyAll()
        }
        producer?.interrupt(); consumer?.interrupt()
        producer = null; consumer = null
        track?.let { t -> runCatching { t.pause() }; runCatching { t.flush() }; runCatching { t.stop() }; runCatching { t.release() } }
        track = null
        tts?.let { runCatching { it.release() } }
        tts = null
    }

    override fun setSpeed(speed: Float) {
        this.speed = if (speed > 0f) speed else 1.0f
        track?.let { applySpeed(it) }
    }

    override fun setPitch(pitch: Float) { /* neural models have no pitch control */ }

    /** Live buffer-depth change (no rebuild needed). */
    fun updateLookahead(n: Int) { lookahead = n.coerceIn(1, MAX_LOOKAHEAD) }

    private fun applySpeed(t: AudioTrack) {
        runCatching {
            val p = t.playbackParams
            p.speed = speed.coerceIn(0.25f, 4.0f)
            t.playbackParams = p
        }
    }

    override suspend fun speak(
        line: TTSHelper.TTSLine,
        upcoming: List<TTSHelper.TTSLine>,
        action: () -> Boolean,
    ): Int? {
        if (tts == null) return null
        synchronized(lock) {
            var idx = slots.indexOfFirst { it.line == line && !it.cancelled }
            if (idx == -1) {
                // Discontinuity (fresh start or skip target): rebuild the timeline from this line.
                slots.forEach { it.cancelled = true }
                slots.clear()
                playIndex = 0
                slots.add(Slot(nextId++, line))
                idx = 0
                flushTrack()
            }
            // Append the look-ahead window right after the current line (dedup by identity).
            var insertAt = idx + 1
            for (u in upcoming.take(lookahead)) {
                if (slots.none { it.line == u && !it.cancelled }) {
                    slots.add(insertAt.coerceAtMost(slots.size), Slot(nextId++, u))
                    insertAt++
                }
            }
            lock.notifyAll()
            return slots[idx].id
        }
    }

    override suspend fun waitForOr(id: Int?, action: () -> Boolean, then: () -> Unit) {
        if (id == null) return
        while (id > endId) {
            delay(50)
            if (action()) {
                interruptTTS()
                then()
                break
            }
        }
    }

    override fun interruptTTS() {
        synchronized(lock) {
            slots.forEach { it.cancelled = true }
            slots.clear()
            playIndex = 0
            lock.notifyAll()
        }
        flushTrack()
    }

    private fun flushTrack() {
        track?.let { t -> runCatching { t.pause(); t.flush(); t.play() } }
    }

    // ---- producer: render the look-ahead window ahead of playback ----
    private fun produceLoop() {
        while (running) {
            val slot: Slot? = synchronized(lock) {
                val end = minOf(slots.size, playIndex + lookahead + 1)
                var found: Slot? = null
                for (i in playIndex until end) {
                    val s = slots.getOrNull(i) ?: continue
                    if (!s.rendered && !s.failed && !s.cancelled) { found = s; break }
                }
                if (found == null) runCatching { lock.wait(200) }
                found
            }
            if (!running) break
            if (slot != null) render(slot)
        }
    }

    private fun render(slot: Slot) {
        val engine = tts ?: run { slot.failed = true; return }
        val chunks = ArrayList<FloatArray>()
        var total = 0
        val sink: (FloatArray) -> Int = cb@{ samples ->
            if (!running || slot.cancelled) return@cb 0
            val c = samples.copyOf() // native buffer is reused/aliased — copy it
            chunks.add(c); total += c.size
            1
        }
        val t0 = System.currentTimeMillis()
        try {
            val gen = TtsModels.resolveGenerationConfig(appContext, def, sid, 1.0f)
            if (gen != null) {
                engine.generateWithConfigAndCallback(text = slot.line.speakOutMsg, config = gen, callback = sink)
            } else {
                // Keep model speed at 1.0 so cached PCM stays valid; user speed is applied on the track.
                engine.generateWithCallback(text = slot.line.speakOutMsg, sid = sid, speed = 1.0f, callback = sink)
            }
        } catch (t: Throwable) {
            logError(t); slot.failed = true
            synchronized(lock) { lock.notifyAll() }
            return
        }
        if (slot.cancelled) return
        val raw = FloatArray(total)
        var o = 0
        for (c in chunks) { System.arraycopy(c, 0, raw, o, c.size); o += c.size }
        val out = trimSilence(raw)
        val synthMs = System.currentTimeMillis() - t0
        val audioMs = if (sampleRate > 0) out.size * 1000L / sampleRate else 0L
        // rtf > 1.0 means synthesis is SLOWER than real-time → buffer can't fully hide the gap.
        Log.d(
            TAG,
            "render sid=$sid synth=${synthMs}ms audio=${audioMs}ms rtf=" +
                    (if (audioMs > 0) "%.2f".format(synthMs.toFloat() / audioMs) else "?")
        )
        synchronized(lock) {
            slot.pcm = out
            slot.rendered = true
            lock.notifyAll()
        }
    }

    /** Trim leading/trailing near-silence the model bakes into each sentence (a big inter-sentence
     *  gap source), keeping a short consistent tail so sentences don't run together unnaturally. */
    private fun trimSilence(pcm: FloatArray): FloatArray {
        if (pcm.isEmpty()) return pcm
        var start = 0
        while (start < pcm.size && kotlin.math.abs(pcm[start]) < SILENCE_THRESHOLD) start++
        var end = pcm.size
        while (end > start && kotlin.math.abs(pcm[end - 1]) < SILENCE_THRESHOLD) end--
        if (start >= end) return FloatArray(0)
        val pad = (sampleRate * INTER_SENTENCE_PAD_SEC).toInt()
        val e = minOf(pcm.size, end + pad)
        return if (start == 0 && e == pcm.size) pcm else pcm.copyOfRange(start, e)
    }

    // ---- consumer: play rendered slots back-to-back into the never-stopped track ----
    private fun consumeLoop() {
        while (running) {
            val slot: Slot = synchronized(lock) {
                while (running && playIndex >= slots.size) runCatching { lock.wait(200) }
                if (!running) return
                slots[playIndex]
            }
            synchronized(lock) {
                while (running && !slot.rendered && !slot.failed && !slot.cancelled) runCatching { lock.wait(100) }
            }
            if (!running) return
            val pcm = slot.pcm
            if (!slot.cancelled && !slot.failed && pcm != null) {
                onAudibleLine?.invoke(slot.line)
                val t = track
                var off = 0
                while (running && !slot.cancelled && t != null && off < pcm.size) {
                    val n = t.write(pcm, off, minOf(WRITE_CHUNK, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    off += n
                }
            }
            endId = maxOf(endId, slot.id)
            advance(slot)
        }
    }

    private fun advance(slot: Slot) {
        synchronized(lock) {
            if (playIndex < slots.size && slots[playIndex] === slot) playIndex++
            // Bound memory: keep at most BEHIND already-played slots for instant back-skip.
            while (playIndex > BEHIND && slots.isNotEmpty()) { slots.removeAt(0); playIndex-- }
            lock.notifyAll()
        }
    }

    companion object {
        private const val TAG = "OnDeviceTts"
        private const val BEHIND = 2
        private const val MAX_LOOKAHEAD = 6
        private const val WRITE_CHUNK = 4096 // floats per AudioTrack.write
        private const val DUCK_VOLUME = 0.3f // volume while ducking under a transient focus loss
        private const val SILENCE_THRESHOLD = 0.01f // |sample| below this counts as silence (~ -40 dB)
        private const val INTER_SENTENCE_PAD_SEC = 0.06f // natural gap kept after trimming
    }
}
