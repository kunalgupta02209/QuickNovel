package com.lagradost.quicknovel.tts

import android.content.Context
import com.lagradost.quicknovel.TTSHelper
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Feature 2: opportunistic, superseding TTS prefetch — on chapter open, cache the current visible
 * section + the next chapter (default voice) so a later Play / skip is an instant cache hit. Thin
 * driver over [TtsSynthPool]; the newest request supersedes the previous one by bumping [generation]
 * (in-flight workers finish their current sentence, see the stale generation, and exit — SIGABRT-safe).
 */
object TtsPrefetchManager {
    @Volatile
    private var generation = 0

    /** Cancel any in-flight prefetch (e.g. engine recreated / reader closed). */
    fun cancelAll() {
        synchronized(this) { generation++ }
    }

    /** Start prefetching [batches] of lines; supersedes any previous prefetch. */
    fun prefetch(
        ctx: Context,
        bookId: String,
        def: TtsModels.ModelDef,
        sid: Int,
        batches: List<List<TTSHelper.TTSLine>>,
    ) {
        if (!com.lagradost.quicknovel.util.DeviceGenGate.allowed(ctx)) return
        if (batches.isEmpty()) return
        val myGen = synchronized(this) { ++generation }
        Thread({
            val q = ConcurrentLinkedQueue(batches)
            // Prefetch is opportunistic — keep it light (≤2 workers) so it never starves the reader.
            val sizing = TtsSynthPool.sizing(ctx, def).let { it.copy(workers = minOf(it.workers, 2)) }
            TtsSynthPool.run<List<TTSHelper.TTSLine>>(
                ctx.applicationContext, def, sid, bookId, sizing,
                stop = { generation != myGen },
                next = { q.poll() },
                body = { synth, batch ->
                    synth.synthLines(batch, shouldStop = { generation != myGen })
                    true
                },
            )
        }, "tts-prefetch").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 2
        }.start()
    }
}
