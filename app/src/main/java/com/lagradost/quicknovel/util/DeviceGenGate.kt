package com.lagradost.quicknovel.util

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Global kill-switch for ALL on-device generation (TTS synthesis + on-device LLM fixing) — the
 * battery/heat guard. When off, the device only plays back cached audio and only uses the server
 * for fixes: live TTS skips uncached lines, pre-gen/prefetch refuse to start, the remote-TTS
 * unreachable-fallback stays remote, and the LLM fixer never falls back to llama.cpp.
 * Toggle: Settings -> "Allow on-device generation" (default ON = original behavior).
 */
object DeviceGenGate {
    const val KEY = "on_device_generation"

    fun allowed(context: Context): Boolean =
        runCatching {
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .getBoolean(KEY, true)
        }.getOrDefault(true)
}
