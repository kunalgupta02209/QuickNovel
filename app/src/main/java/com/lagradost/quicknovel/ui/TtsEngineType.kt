package com.lagradost.quicknovel.ui

import androidx.annotation.StringRes
import com.lagradost.quicknovel.R

/**
 * Which Read-Aloud engine the reader uses. [SYSTEM] is the default (Android platform TextToSpeech);
 * [ON_DEVICE] is the embedded sherpa-onnx neural engine (opt-in, requires a downloaded model).
 *
 * Mirrors [ReadingType].
 */
enum class TtsEngineType(val prefValue: Int, @StringRes val stringRes: Int) {
    SYSTEM(0, R.string.tts_engine_system),
    ON_DEVICE(1, R.string.tts_engine_on_device);

    companion object {
        fun fromSpinner(position: Int?) = values().find { value -> value.prefValue == position } ?: SYSTEM
    }
}
