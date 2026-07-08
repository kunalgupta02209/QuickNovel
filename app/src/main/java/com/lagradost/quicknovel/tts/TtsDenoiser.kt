package com.lagradost.quicknovel.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.lagradost.quicknovel.mvvm.logError
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional NEURAL speech-enhancement pass for the on-device TTS output, using sherpa-onnx's GTCRN
 * denoiser — which ships in the SAME vendored sherpa-onnx.aar we already use for TTS (no new native
 * lib, just a ~7 MB model download). GTCRN removes the grainy/raspy quantization noise that DSP alone
 * can only mask. It runs at 16 kHz, so the output is linearly resampled back to the TTS sample rate.
 *
 * Heavier than [AudioPostProcessor] (adds ~0.1-0.3x real-time), so it is OFF by default and applied in
 * the PRODUCER (render), ahead of playback, to avoid stalling the audio thread.
 */
object TtsDenoiser {
    const val FILE = "gtcrn_simple.onnx"
    private const val URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx"
    const val APPROX_MB = 7

    private fun dir(ctx: Context) = File(ctx.filesDir, "tts-denoiser")
    fun modelFile(ctx: Context) = File(dir(ctx), FILE)
    private fun ready(ctx: Context) = File(dir(ctx), ".ready")

    fun isReady(ctx: Context): Boolean =
        ready(ctx).exists() && modelFile(ctx).let { it.exists() && it.length() > 0 }

    /** Download the GTCRN model to filesDir (single file). Blocking — call off the main thread. */
    fun downloadModel(ctx: Context, onProgress: (Float) -> Unit = {}) {
        val d = dir(ctx); d.mkdirs()
        val dest = modelFile(ctx)
        val tmp = File(d, "$FILE.part")
        var conn = URL(URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connect()
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect(); conn = URL(loc).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true; conn.connect(); redirects++
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (APPROX_MB * 1024L * 1024L)
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var read = 0L; var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    out.write(buf, 0, n); read += n; onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        conn.disconnect()
        if (!tmp.renameTo(dest)) throw java.io.IOException("Could not finalize denoiser model")
        ready(ctx).writeText("ok")
    }

    @Volatile private var denoiser: OfflineSpeechDenoiser? = null
    private val lock = Any()

    private fun ensure(ctx: Context): OfflineSpeechDenoiser? = synchronized(lock) {
        denoiser?.let { return it }
        if (!isReady(ctx)) return null
        return try {
            val config = OfflineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = modelFile(ctx).absolutePath),
                    numThreads = 1,
                    provider = "cpu",
                )
            )
            OfflineSpeechDenoiser(assetManager = null, config = config).also { denoiser = it }
        } catch (t: Throwable) {
            logError(t); null
        }
    }

    /** Denoise [pcm] (at [inRate]) and resample the result to [outRate]. Returns [pcm] if unavailable. */
    fun process(ctx: Context, pcm: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (pcm.size < 16) return pcm
        val d = ensure(ctx.applicationContext) ?: run {
            android.util.Log.w("TtsDenoiser", "NOT READY -> passthrough (raw) n=${pcm.size}"); return pcm
        }
        return try {
            val out = synchronized(lock) { d.run(pcm, inRate) }
            // A/B diagnostic: out.sampleRate == 16000 proves GTCRN band-limits to 8 kHz (the muffling).
            android.util.Log.i("TtsDenoiser", "in ${pcm.size}@$inRate -> out ${out.samples.size}@${out.sampleRate} -> resample $outRate")
            resample(out.samples, out.sampleRate, outRate)
        } catch (t: Throwable) {
            logError(t); pcm
        }
    }

    fun release() = synchronized(lock) { runCatching { denoiser?.release() }; denoiser = null }

    /** Linear resampler (fine for speech); GTCRN outputs 16 kHz, TTS plays at 24 kHz. */
    private fun resample(x: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate || x.isEmpty()) return x
        val ratio = outRate.toDouble() / inRate
        val n = (x.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        val last = x.size - 1
        for (i in 0 until n) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt()
            val frac = (srcPos - i0).toFloat()
            val a = x[i0.coerceIn(0, last)]
            val b = x[(i0 + 1).coerceIn(0, last)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
