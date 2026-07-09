package com.lagradost.quicknovel.util

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/**
 * Lightweight process-CPU sampler for diagnosing read-aloud battery/CPU cost, especially with the
 * screen OFF (where synthesis running in the background would drain the battery). While active it
 * logs one line every [intervalMs] under tag "PerfMon":
 *
 *   PerfMon: cpu=8% screen=off threads=41 heap=126MB synth=1 (rendering)  playback-only cache hits
 *
 * cpu% is THIS process across all cores since the last sample (so >100% is possible on multi-core
 * synthesis). synth = live TTS renders in flight (0 = pure cache-hit playback, cheap). Opt-in — the
 * reader starts/stops it around a TTS session only when the pref is on, so there is zero overhead
 * normally. Pull with:  adb logcat -s PerfMon
 */
object PerfMonitor {
    private const val TAG = "PerfMon"
    private val clkTck: Long = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
        .takeIf { it > 0 } ?: 100L

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(context: Context, intervalMs: Long = 10_000L) {
        if (running) return
        running = true
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        thread = Thread {
            var lastTicks = readCpuTicks()
            var lastWall = SystemClock.elapsedRealtime()
            Log.i(TAG, "start (interval=${intervalMs}ms, hz=$clkTck)")
            while (running) {
                try {
                    Thread.sleep(intervalMs)
                } catch (e: InterruptedException) {
                    break
                }
                if (!running) break
                val ticks = readCpuTicks()
                val wall = SystemClock.elapsedRealtime()
                val dtSec = (wall - lastWall) / 1000.0
                val cpuPct = if (dtSec > 0 && ticks >= lastTicks)
                    (ticks - lastTicks).toDouble() / clkTck / dtSec * 100.0 else 0.0
                lastTicks = ticks; lastWall = wall

                val screen = if (pm?.isInteractive == false) "off" else "on"
                val synth = com.lagradost.quicknovel.tts.OnDeviceTtsEngine.activeRenders
                val rt = Runtime.getRuntime()
                val heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                Log.i(
                    TAG,
                    "cpu=%.0f%% screen=%s threads=%d heap=%dMB synth=%d %s".format(
                        cpuPct, screen, readNumThreads(), heapMb, synth,
                        if (synth > 0) "(synthesizing)" else "(cache-hit playback / idle)",
                    ),
                )
            }
            Log.i(TAG, "stop")
        }.apply { name = "PerfMon"; isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** utime + stime (fields 14+15 of /proc/self/stat), in clock ticks. Robust to spaces in comm. */
    private fun readCpuTicks(): Long = runCatching {
        val stat = File("/proc/self/stat").readText()
        val after = stat.substring(stat.lastIndexOf(')') + 2) // skip "pid (comm) " — comm may hold spaces/parens
        val f = after.split(" ")
        // after ')' the fields start at index 0 = state (field 3); utime=14 -> idx 11, stime=15 -> idx 12
        (f[11].toLong() + f[12].toLong())
    }.getOrDefault(0L)

    /** num_threads = field 20 of /proc/self/stat (idx 17 after the comm split). */
    private fun readNumThreads(): Int = runCatching {
        val stat = File("/proc/self/stat").readText()
        val after = stat.substring(stat.lastIndexOf(')') + 2)
        after.split(" ")[17].toInt()
    }.getOrDefault(Thread.activeCount())
}
