package com.lagradost.quicknovel.tts

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared multi-threaded TTS synthesis service used by both the whole-book background pre-generator
 * and the prefetch-on-open feature (and, by filling the cache faster, it shortens the skip-flicker).
 *
 * SAFETY (the sherpa OfflineTts is NOT safe for concurrent generate, and freeing one mid-generate
 * crashes): each worker thread owns EXACTLY ONE [TtsChapterSynthesizer] (its own OfflineTts),
 * generates only from that thread, and frees it in its own finally AFTER its loop exits. [run] joins
 * every worker before returning, so a caller can never free anything mid-generate. Stop is
 * cooperative — the sink returns 0, which aborts the native generate cleanly (never release()).
 */
object TtsSynthPool {
    data class Sizing(val workers: Int, val threadsPerWorker: Int)

    /** Pick a worker count + per-worker thread count from cores + available RAM (and the model size). */
    fun sizing(ctx: Context, def: TtsModels.ModelDef): Sizing {
        val cores = Runtime.getRuntime().availableProcessors()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Sizing(1, TtsModels.defaultInferenceThreads)
        if (am.isLowRamDevice) return Sizing(1, TtsModels.defaultInferenceThreads)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val byCpu = when {
            cores >= 8 -> 3
            cores >= 5 -> 2
            else -> 1
        }
        // Each worker loads a full model copy; budget ~1/3 of available RAM for them.
        val budgetMb = (mi.availMem / (1024 * 1024)) / 3
        val byRam = (budgetMb / def.approxSizeMb.coerceAtLeast(16)).toInt().coerceAtLeast(1)
        val workers = minOf(byCpu, byRam).coerceIn(1, 3)
        return Sizing(workers, (cores / workers).coerceIn(1, 4))
    }

    /**
     * Blocking work-stealing run. Spawns [sizing].workers threads; each builds its own synthesizer.
     * A worker pulls items from [next] (MUST be thread-safe; null = exhausted), waits for the reader
     * to be idle via [TtsPlaybackGate.awaitClear] before each item, then runs [body]. Joins all
     * workers before returning.
     * @return true if fully drained; false if [stop] fired or any [body] returned false.
     */
    fun <T> run(
        ctx: Context,
        def: TtsModels.ModelDef,
        sid: Int,
        bookId: String,
        sizing: Sizing,
        stop: () -> Boolean,
        paused: () -> Boolean = { false },
        next: () -> T?,
        body: (synth: TtsChapterSynthesizer, item: T) -> Boolean,
    ): Boolean {
        val failed = AtomicBoolean(false)
        val workers = (0 until sizing.workers).map { w ->
            Thread({
                val synth = TtsChapterSynthesizer(ctx, def, sid, bookId)
                if (!synth.open(sizing.threadsPerWorker)) {
                    failed.set(true); return@Thread
                }
                try {
                    while (!stop() && !failed.get()) {
                        val item = next() ?: break
                        if (!TtsPlaybackGate.awaitClear(paused, stop)) break
                        if (!body(synth, item)) { failed.set(true); break }
                    }
                } finally {
                    synth.close() // frees ONLY this worker's OfflineTts, after its generate returned
                }
            }, "tts-pool-$w").apply {
                priority = Thread.NORM_PRIORITY - 2
                isDaemon = true
            }
        }
        workers.forEach { it.start() }
        workers.forEach { runCatching { it.join() } } // never return while any generate is in flight
        return !stop() && !failed.get()
    }
}
