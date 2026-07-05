package com.lagradost.quicknovel.tts

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Lightweight, real-time audio clean-up for the on-device neural TTS output (per sentence), applied
 * just before playback. Small int8 models like Kitten sound raspy for two main reasons: (1) occasional
 * inter-sample peaks above 1.0 that the float AudioTrack HARD-CLIPS into harsh distortion, and (2)
 * grainy high-frequency quantization noise. The chain follows the well-established SSDRC recipe (high-
 * pass + dynamic-range compression) plus a de-ess-style high-shelf cut and peak normalization:
 *
 *   1. High-pass biquad (~80 Hz)   — remove DC offset / low rumble.
 *   2. High-shelf cut (~6 kHz, -5 dB) — tame the raspy/sibilant top end (de-ess) without muffling.
 *   3. Gentle compressor            — even out dynamics so quiet syllables stay intelligible.
 *   4. Peak-normalize + soft-limit  — consistent level with headroom; tanh soft-clip replaces the
 *                                     harsh hard clip so nothing ever distorts.
 *
 * Pure math on a FloatArray (a few ms per sentence), no dependencies. Toggleable so it can be A/B'd.
 */
object AudioPostProcessor {

    fun process(input: FloatArray, sampleRate: Int): FloatArray {
        if (input.size < 8 || sampleRate <= 0) return input
        val x = input.copyOf()
        highPass(x, sampleRate, 80f)
        highShelf(x, sampleRate, 6000f, -5f)
        compress(x, sampleRate)
        normalizeAndLimit(x, target = 0.89f, maxGain = 6f)
        return x
    }

    // ---- RBJ biquad, applied in place as a single direct-form-I pass ----

    private fun biquad(x: FloatArray, b0: Float, b1: Float, b2: Float, a1: Float, a2: Float) {
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val xn = x[i]
            val yn = b0 * xn + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xn; y2 = y1; y1 = yn
            x[i] = yn
        }
    }

    private fun highPass(x: FloatArray, fs: Int, fc: Float, q: Float = 0.707f) {
        val w0 = 2.0 * Math.PI * fc / fs
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2 * q)
        val a0 = 1 + alpha
        biquad(
            x,
            b0 = (((1 + cw) / 2) / a0).toFloat(),
            b1 = ((-(1 + cw)) / a0).toFloat(),
            b2 = (((1 + cw) / 2) / a0).toFloat(),
            a1 = ((-2 * cw) / a0).toFloat(),
            a2 = ((1 - alpha) / a0).toFloat(),
        )
    }

    private fun highShelf(x: FloatArray, fs: Int, fc: Float, gainDb: Float, q: Float = 0.707f) {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fc / fs
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / 2.0 * sqrt((a + 1 / a) * (1 / q - 1) + 2)
        val sqrtA = sqrt(a)
        val a0 = (a + 1) - (a - 1) * cw + 2 * sqrtA * alpha
        biquad(
            x,
            b0 = (a * ((a + 1) + (a - 1) * cw + 2 * sqrtA * alpha) / a0).toFloat(),
            b1 = (-2 * a * ((a - 1) + (a + 1) * cw) / a0).toFloat(),
            b2 = (a * ((a + 1) + (a - 1) * cw - 2 * sqrtA * alpha) / a0).toFloat(),
            a1 = (2 * ((a - 1) - (a + 1) * cw) / a0).toFloat(),
            a2 = (((a + 1) - (a - 1) * cw - 2 * sqrtA * alpha) / a0).toFloat(),
        )
    }

    // ---- gentle feed-forward compressor with makeup gain ----

    private fun compress(x: FloatArray, fs: Int) {
        val threshold = 0.18f              // ~ -15 dBFS
        val ratio = 2.5f
        val makeup = 1.4f
        val attack = exp(-1.0 / (fs * 0.005)).toFloat()   // 5 ms
        val release = exp(-1.0 / (fs * 0.090)).toFloat()  // 90 ms
        var env = 0f
        for (i in x.indices) {
            val a = abs(x[i])
            env = if (a > env) attack * env + (1 - attack) * a else release * env + (1 - release) * a
            val gain = if (env > threshold) (threshold + (env - threshold) / ratio) / env else 1f
            x[i] = x[i] * gain * makeup
        }
    }

    // ---- peak normalize (with a gain cap so we don't amplify the noise floor) + tanh soft-clip ----

    private fun normalizeAndLimit(x: FloatArray, target: Float, maxGain: Float) {
        var peak = 0f
        for (v in x) { val a = abs(v); if (a > peak) peak = a }
        if (peak < 1e-6f) return
        val gain = minOf(target / peak, maxGain)
        for (i in x.indices) {
            val s = x[i] * gain
            x[i] = when {
                s.isNaN() || s.isInfinite() -> 0f
                s > target || s < -target -> target * tanh(s / target)
                else -> s
            }
        }
    }
}
