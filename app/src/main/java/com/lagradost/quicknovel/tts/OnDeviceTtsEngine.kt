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
 * Design (resilient, highlight-driven queue):
 *  - **Generation** is independent: a `tts-synth` thread renders the look-ahead window of sentences
 *    into memory, nearest-first.
 *  - **Playback** is a strict sequential queue tied to the highlighter: a `tts-audio` thread plays
 *    the current sentence, highlights it the moment its audio starts, and when it finishes it moves
 *    to the next sentence — if that sentence's audio isn't generated yet it *waits* for it, it never
 *    resets or reorders. Consecutive writes into a never-stopped `AudioTrack(MODE_STREAM)` are gapless.
 *
 * Queue **entries** are kept for the whole play segment (identified by monotonic `seq`), so the
 * driver loop can always find the line it hands to [speak] and simply catch up — this avoids the
 * flush+re-render "jump" that happened when short sentences let playback outrun the loop. Only the
 * heavy **PCM** of far-behind sentences is freed. A skip/pause/stop clears the segment via
 * [interruptTTS]; that is the *only* thing that flushes audio.
 */
class OnDeviceTtsEngine(
    context: Context,
    modelId: String,
    voiceId: String,
    lookahead: Int,
    gapMs: Int,
    private val event: (TTSHelper.TTSActionType) -> Boolean,
) : TtsEngine {
    private val appContext = context.applicationContext
    private val def = TtsModels.byId(modelId)
    private val sid: Int = (TtsModels.parseVoice(voiceId)?.second ?: 0)
        .coerceIn(0, (def.speakerCount - 1).coerceAtLeast(0))

    @Volatile private var lookahead: Int = lookahead.coerceIn(1, MAX_LOOKAHEAD)
    @Volatile private var speed: Float = 1.0f
    @Volatile private var pitch: Float = 1.0f
    @Volatile private var gapMs: Int = gapMs.coerceIn(0, MAX_GAP_MS)

    /** Clean up raspy/clipped audio before playback (see [AudioPostProcessor]). Live-toggleable. */
    @Volatile var enhanceAudio: Boolean = true
    fun updateEnhance(on: Boolean) { enhanceAudio = on }

    /** Optional GTCRN neural denoiser (heavier; applied in the producer). Off by default. */
    @Volatile var denoise: Boolean = false
    fun updateDenoise(on: Boolean) { denoise = on }

    /** Fired (audio thread) the moment a sentence starts playing: (current, upcoming) → drives the
     *  highlight AND the media-notification now-playing text, audio-synced. */
    var onAudibleLine: ((TTSHelper.TTSLine, TTSHelper.TTSLine?) -> Unit)? = null

    /** Fired from speak() when a sentence becomes the play target: (line, pending). `pending=true`
     *  means its audio isn't ready yet (a skip beyond the synthesized look-ahead) — the UI can jump
     *  the highlight there immediately and flicker it until [onAudibleLine] fires (audio starts). */
    var onLineTarget: ((TTSHelper.TTSLine, Boolean) -> Unit)? = null

    /** Local pause: the consumer holds before playing the next sentence's audio (so a skip during a
     *  pause updates the highlight/flicker but doesn't auto-play until resumed). */
    @Volatile private var paused = false
    fun setPaused(on: Boolean) { synchronized(lock) { paused = on; lock.notifyAll() } }

    /** When non-null, [render] reads/writes a per-sentence disk cache under this book id, so
     *  re-listening and background pre-generation share byte-identical audio (see [TtsAudioCache]). */
    @Volatile var cacheBookId: String? = null

    override val supportsPitch: Boolean get() = true
    override val supportsSystemLanguagePicker: Boolean get() = false
    override val drivesOwnHighlight: Boolean get() = true

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    private var sampleRate = 24000
    private val silenceChunk = FloatArray(WRITE_CHUNK) // zeros, reused for writing inter-sentence gaps

    // ---- audio focus + ducking + becoming-noisy (headphone unplug) ----
    private var focusRequest: AudioFocusRequest? = null
    private val noisyReceiver = BecomingNoisyReceiver()
    private val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    @Volatile private var focusRegistered = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                event(TTSHelper.TTSActionType.Pause)
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

    // ---- the queue ----
    private class Item(val seq: Int, val line: TTSHelper.TTSLine) {
        @Volatile var pcm: FloatArray? = null   // null = not generated (yet) / freed
        @Volatile var failed = false
        @Volatile var cancelled = false
    }

    private val lock = Object()
    private val queue = ArrayList<Item>()                    // ordered by seq; entries kept for the segment
    private val byLine = HashMap<TTSHelper.TTSLine, Item>()  // identity -> item, for O(1) lookup
    private var playPos = 0                                  // consumer index into queue
    private var nextSeq = 1                                  // monotonic; never resets (so waitForOr stays valid)
    @Volatile private var endSeq = 0                         // highest seq that finished playing
    @Volatile private var running = false
    private var producer: Thread? = null
    private var consumer: Thread? = null

    override fun isValidTTS(): Boolean = tts != null && track != null
    override fun ttsInitialized(): Boolean = tts != null

    // --- lifecycle ---

    override fun register() {
        if (running) return
        TtsModels.resolveConfig(appContext, def)?.let { config ->
            tts = try { OfflineTts(assetManager = null, config = config) } catch (t: Throwable) { logError(t); null }
        }
        sampleRate = tts?.sampleRate() ?: 24000

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)
        // ~0.5 s of headroom to ride out audio-HAL stalls (esp. the emulator).
        val bufBytes = maxOf(minBuf, sampleRate * 2)
        track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { applyParams(it); it.play() }
        } catch (t: Throwable) { logError(t); null }

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
            queue.forEach { it.cancelled = true }
            queue.clear(); byLine.clear(); playPos = 0
            lock.notifyAll()
        }
        val p = producer; val c = consumer
        producer = null; consumer = null
        p?.interrupt(); c?.interrupt()
        // The native OfflineTts.generate ignores thread interrupts, so we must WAIT for the producer
        // to leave render() before freeing the native object. Its sink returns 0 once running=false,
        // so the in-flight generate aborts within a chunk. Freeing tts mid-generate locks a destroyed
        // mutex -> SIGABRT (crash in the tts-synth thread).
        runCatching { p?.join(2000) }
        runCatching { c?.join(500) }
        track?.let { t -> runCatching { t.pause() }; runCatching { t.flush() }; runCatching { t.stop() }; runCatching { t.release() } }
        track = null
        if (p?.isAlive == true) {
            // Still synthesizing after the grace period — leak the native object rather than free it
            // out from under the generate (a small one-off leak beats a hard crash).
            Log.w(TAG, "producer still synthesizing after join; leaking OfflineTts to avoid use-after-free")
        } else {
            tts?.let { runCatching { it.release() } }
        }
        tts = null
    }

    override fun setSpeed(speed: Float) {
        this.speed = if (speed > 0f) speed else 1.0f
        track?.let { applyParams(it) }
    }

    override fun setPitch(pitch: Float) {
        this.pitch = if (pitch > 0f) pitch else 1.0f
        track?.let { applyParams(it) }
    }

    /** Live buffer-depth change (no rebuild needed). */
    fun updateLookahead(n: Int) { lookahead = n.coerceIn(1, MAX_LOOKAHEAD) }

    /** Live inter-sentence gap change (ms). */
    fun updateGapMs(ms: Int) { gapMs = ms.coerceIn(0, MAX_GAP_MS) }

    // Neural models have no native pitch control, but AudioTrack.PlaybackParams applies a pitch
    // shift on playback (independent of speed/tempo).
    private fun applyParams(t: AudioTrack) {
        runCatching {
            val p = t.playbackParams
            p.speed = speed.coerceIn(0.25f, 4.0f)
            p.pitch = pitch.coerceIn(0.25f, 4.0f)
            t.playbackParams = p
        }
    }

    // --- TtsEngine contract driven by the reader loop ---

    override suspend fun speak(
        line: TTSHelper.TTSLine,
        upcoming: List<TTSHelper.TTSLine>,
        action: () -> Boolean,
    ): Int? {
        if (tts == null) return null
        val pending: Boolean
        val seq: Int
        synchronized(lock) {
            val existing = byLine[line]
            val idx = if (existing != null && !existing.cancelled) queue.indexOf(existing) else -1
            val item: Item
            if (idx >= 0) {
                // Target is already in the pre-synthesized window (e.g. a skip landing on a look-ahead
                // sentence). Jump the play cursor straight to it — its audio is ready, so it plays with
                // no re-synthesis gap. Cancel the sentences we skipped over and flush the current audio.
                if (idx != playPos) {
                    if (idx > playPos) {
                        // forward skip: drop the sentences we jump over
                        for (i in playPos until idx) queue.getOrNull(i)?.let { it.cancelled = true }
                    } else {
                        // backward skip: replay from the target, so un-cancel up to the old cursor
                        for (i in idx until playPos) queue.getOrNull(i)?.let { it.cancelled = false }
                    }
                    playPos = idx
                    flushTrack()
                }
                val target = existing!!
                target.cancelled = false
                item = target
            } else {
                // Fresh start, or a jump beyond the synthesized window — rebuild the segment.
                clearSegmentLocked()
                item = enqueueLocked(line)
                flushTrack()
            }
            for (u in upcoming.take(lookahead)) if (byLine[u] == null) enqueueLocked(u)
            lock.notifyAll()
            // `pending` = audio not ready yet (a skip beyond the rendered look-ahead).
            pending = item.pcm == null && !item.failed
            seq = item.seq
        }
        // Report the target OUTSIDE the lock so the UI jumps the highlight there + flickers until
        // audio starts (onAudibleLine then clears it). Callback just posts LiveData.
        onLineTarget?.invoke(line, pending)
        return seq
    }

    override suspend fun waitForOr(id: Int?, action: () -> Boolean, then: () -> Unit) {
        if (id == null) return
        while (id > endSeq) {
            delay(50)
            if (action()) {
                // Stop the current audio but KEEP the pre-synthesized look-ahead: the next speak()
                // either jumps the cursor to the (already synthesized) skip target — instant, no gap —
                // or rebuilds the segment. interruptTTS() here would wipe the look-ahead and force a
                // re-synthesis on every skip. Stop still clears the segment in the loop's finally.
                flushTrack()
                then()
                break
            }
        }
    }

    override fun interruptTTS() {
        synchronized(lock) { clearSegmentLocked() }
        flushTrack()
    }

    private fun enqueueLocked(line: TTSHelper.TTSLine): Item {
        val item = Item(nextSeq++, line)
        queue.add(item); byLine[line] = item
        return item
    }

    private fun clearSegmentLocked() {
        queue.forEach { it.cancelled = true }
        queue.clear(); byLine.clear(); playPos = 0
        lock.notifyAll()
    }

    private fun flushTrack() {
        track?.let { t -> runCatching { t.pause(); t.flush(); t.play() } }
    }

    // --- producer: render the look-ahead window ahead of the play cursor ---
    private fun produceLoop() {
        while (running) {
            val item: Item? = synchronized(lock) {
                val end = minOf(queue.size, playPos + lookahead + 1)
                var found: Item? = null
                for (i in playPos until end) {
                    val it = queue.getOrNull(i) ?: continue
                    if (it.pcm == null && !it.failed && !it.cancelled) { found = it; break }
                }
                if (found == null) runCatching { lock.wait(200) }
                found
            }
            if (!running) break
            if (item != null) render(item)
        }
    }

    private fun render(item: Item) {
        val engine = tts ?: run { item.failed = true; return }

        // READ-THROUGH: a cache hit is a ~ms disk read instead of real-time synthesis.
        val cacheFile = cacheBookId?.let { TtsAudioCache.fileFor(appContext, it, def.id, sid, item.line) }
        if (cacheFile != null && cacheFile.exists()) {
            val cached = TtsAudioCache.load(cacheFile)
            if (cached != null) {
                Log.d(TAG, "render CACHE HIT sid=$sid samples=${cached.size}")
                synchronized(lock) { item.pcm = cached; lock.notifyAll() }
                return
            }
        }

        val chunks = ArrayList<FloatArray>()
        var total = 0
        val sink: (FloatArray) -> Int = cb@{ samples ->
            if (!running || item.cancelled) return@cb 0
            val c = samples.copyOf() // native buffer is reused/aliased — copy it
            chunks.add(c); total += c.size
            1
        }
        val t0 = System.currentTimeMillis()
        try {
            val gen = TtsModels.resolveGenerationConfig(appContext, def, sid, 1.0f)
            if (gen != null) engine.generateWithConfigAndCallback(text = item.line.speakOutMsg, config = gen, callback = sink)
            else engine.generateWithCallback(text = item.line.speakOutMsg, sid = sid, speed = 1.0f, callback = sink)
        } catch (t: Throwable) {
            logError(t); item.failed = true
            synchronized(lock) { lock.notifyAll() }
            return
        }
        if (item.cancelled) return
        val raw = FloatArray(total)
        var o = 0
        for (c in chunks) { System.arraycopy(c, 0, raw, o, c.size); o += c.size }
        val trimmed = TtsAudioCache.trimSilence(raw)
        // Optional neural denoise (ahead of playback, in the producer, so it never stalls the consumer).
        val out = if (denoise) TtsDenoiser.process(appContext, trimmed, sampleRate, sampleRate) else trimmed
        // WRITE-THROUGH: populate the cache so re-listen / pre-generation share byte-identical audio.
        if (cacheFile != null) runCatching { TtsAudioCache.save(cacheFile, out, sampleRate) }
        val synthMs = System.currentTimeMillis() - t0
        val audioMs = if (sampleRate > 0) out.size * 1000L / sampleRate else 0L
        Log.d(TAG, "render sid=$sid synth=${synthMs}ms audio=${audioMs}ms rtf=" +
                (if (audioMs > 0) "%.2f".format(synthMs.toFloat() / audioMs) else "?"))
        synchronized(lock) { item.pcm = out; lock.notifyAll() }
    }

    // --- consumer: play the queue strictly in order, tied to the highlight ---
    private fun consumeLoop() {
        while (running) {
            val item: Item = synchronized(lock) {
                while (running && playPos >= queue.size) runCatching { lock.wait(200) }
                if (!running) return
                queue[playPos]
            }
            // Wait until THIS sentence's audio is generated (or failed / cancelled).
            synchronized(lock) {
                while (running && item.pcm == null && !item.failed && !item.cancelled) runCatching { lock.wait(100) }
            }
            if (!running) return
            // Hold while locally paused: a skip during a pause updates the highlight (via speak ->
            // onLineTarget) but must not auto-play until the user resumes. release() flips running.
            synchronized(lock) {
                while (running && paused && !item.cancelled) runCatching { lock.wait(100) }
            }
            if (!running) return
            // Clean up the raw model audio (de-clip / de-ess / normalize) right before playback.
            val pcm = item.pcm?.let { if (enhanceAudio) AudioPostProcessor.process(it, sampleRate) else it }
            if (!item.cancelled && !item.failed && pcm != null) {
                val next: TTSHelper.TTSLine? = synchronized(lock) { queue.getOrNull(playPos + 1)?.line }
                onAudibleLine?.invoke(item.line, next) // highlight + notification, audio-synced
                val t = track
                var off = 0
                while (running && !item.cancelled && t != null && off < pcm.size) {
                    val n = t.write(pcm, off, minOf(WRITE_CHUNK, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    off += n
                }
                // configurable inter-sentence gap: write gapMs of silence before the next sentence
                var gap = (sampleRate.toLong() * gapMs / 1000L).toInt()
                while (running && !item.cancelled && t != null && gap > 0) {
                    val w = minOf(silenceChunk.size, gap)
                    val n = t.write(silenceChunk, 0, w, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    gap -= n
                }
            }
            if (!item.cancelled) endSeq = maxOf(endSeq, item.seq)
            synchronized(lock) {
                // Only advance if we're still on the same item (interruptTTS may have reset the segment).
                if (playPos < queue.size && queue[playPos] === item) {
                    playPos++
                    // Free the PCM of a sentence that has dropped out of the back-skip window (keeps
                    // the lightweight entry so the loop can still find it — just frees the audio).
                    val freeIdx = playPos - 1 - PCM_BEHIND
                    if (freeIdx in queue.indices) queue[freeIdx].pcm = null
                }
                lock.notifyAll()
            }
        }
    }

    companion object {
        private const val TAG = "OnDeviceTts"
        private const val PCM_BEHIND = 3   // played sentences whose PCM is kept for instant back-skip
        private const val MAX_LOOKAHEAD = 6
        private const val WRITE_CHUNK = 4096
        private const val DUCK_VOLUME = 0.3f
        private const val MAX_GAP_MS = 2000
    }
}
