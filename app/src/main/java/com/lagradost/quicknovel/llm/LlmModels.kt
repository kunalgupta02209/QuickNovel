package com.lagradost.quicknovel.llm

import android.app.ActivityManager
import android.content.Context
import com.lagradost.quicknovel.mvvm.logError
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Registry + on-disk management of the on-device LLM "prose fixer" models (Qwen2.5 GGUF, consumed by
 * llama.cpp). Sole owner of the model layout under filesDir/llm-models/<id>/, mirroring [com.lagradost
 * .quicknovel.tts.TtsModels] but simpler: each model is a SINGLE non-archive .gguf downloaded straight
 * to disk with a `.ready` marker. Qwen2.5 is chosen for its native Chinese/Korean/Japanese training —
 * the key property for repairing CJK-origin machine translation (gender-ambiguous pronouns, honorifics).
 */
object LlmModels {
    /** llama.cpp context window. Chapters are chunked to fit this alongside the prompt + generated
     *  output; 4096 comfortably holds one ~2800-char chunk + system prompt + memory + the rewrite. */
    const val CTX_LEN = 4096

    data class ModelDef(
        val id: String,
        val displayName: String,
        val fileName: String,   // the .gguf file name inside the model dir
        val url: String,        // HuggingFace resolve URL
        val approxSizeMb: Int,
        val minRamGb: Int,      // recommended minimum TOTAL device RAM
        val note: String = "",
    )

    val ALL: List<ModelDef> = listOf(
        ModelDef(
            id = "qwen2.5-0.5b",
            displayName = "Qwen2.5 0.5B Instruct (int4)",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            approxSizeMb = 398, minRamGb = 3,
            note = "Fastest, lowest RAM. Best-effort smoothing; still CJK-aware.",
        ),
        ModelDef(
            id = "qwen2.5-1.5b",
            displayName = "Qwen2.5 1.5B Instruct (int4)",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            approxSizeMb = 1120, minRamGb = 4,
            note = "Recommended. Strong grammar / pronoun-gender / honorific fixing.",
        ),
        ModelDef(
            id = "qwen2.5-3b",
            displayName = "Qwen2.5 3B Instruct (int4)",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            approxSizeMb = 2000, minRamGb = 6,
            note = "Best quality. Flagship only (needs ~6 GB RAM).",
        ),
    )

    private const val ROOT = "llm-models"

    fun byId(id: String?): ModelDef = ALL.firstOrNull { it.id == id } ?: ALL[1] // default = 1.5B

    fun modelDir(context: Context, def: ModelDef): File = File(File(context.filesDir, ROOT), def.id)
    fun modelFile(context: Context, def: ModelDef): File = File(modelDir(context, def), def.fileName)
    private fun readyMarker(context: Context, def: ModelDef): File = File(modelDir(context, def), ".ready")

    fun isReady(context: Context, def: ModelDef): Boolean =
        readyMarker(context, def).exists() && modelFile(context, def).let { it.exists() && it.length() > 0 }

    fun isReady(context: Context, id: String?): Boolean = isReady(context, byId(id))

    /** Total device RAM in GB (rounded), used to recommend/gate models. */
    fun totalRamGb(context: Context): Int = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        Math.round(mi.totalMem / (1024.0 * 1024.0 * 1024.0)).toInt()
    } catch (t: Throwable) {
        logError(t); 4
    }

    /** RAM-based recommended model id: >=6 GB -> 3B, >=4 GB -> 1.5B, else 0.5B. */
    fun recommendedId(context: Context): String = when {
        totalRamGb(context) >= 6 -> "qwen2.5-3b"
        totalRamGb(context) >= 4 -> "qwen2.5-1.5b"
        else -> "qwen2.5-0.5b"
    }

    /** Stream the single .gguf into the model dir with progress, then drop a `.ready` marker. Heavy
     *  (0.4–2 GB network) — caller must be off the main thread. */
    fun downloadModel(context: Context, def: ModelDef, onProgress: (Float) -> Unit) {
        val dir = modelDir(context, def); dir.mkdirs()
        val dest = modelFile(context, def)
        val tmp = File(dir, def.fileName + ".part")
        var conn = URL(def.url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30000; conn.readTimeout = 60000
        conn.connect()
        // HttpURLConnection won't follow an https->https redirect to a different host in all cases; do it manually.
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30000; conn.readTimeout = 60000
            conn.connect(); redirects++
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (def.approxSizeMb * 1024L * 1024L)
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var readBytes = 0L
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    out.write(buf, 0, n); readBytes += n
                    onProgress((readBytes.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        conn.disconnect()
        if (!tmp.renameTo(dest)) throw java.io.IOException("Could not finalize model file")
        readyMarker(context, def).writeText("ok")
    }

    fun delete(context: Context, def: ModelDef) {
        runCatching { modelDir(context, def).deleteRecursively() }
    }
}
