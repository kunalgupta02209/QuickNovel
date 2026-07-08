package com.lagradost.quicknovel.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Registry + provisioning for the on-device sherpa-onnx neural TTS models.
 *
 * Every model is downloaded on first use into `filesDir` and cached (a ".ready" marker per model).
 * Models are NOT bundled in the APK. Readiness and config are derived purely from files on disk, so
 * they survive process death.
 *
 * Ported near-verbatim from the sibling HTTTS project.
 */
object TtsModels {
    private const val TAG = "TtsModels"

    private const val TTS_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    private const val VOCODER_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models"

    enum class Kind { KOKORO, KITTEN, SUPERTONIC, ZIPVOICE, UNSUPPORTED }

    /** One downloadable file. [archive]=true → .tar.bz2 extracted into filesDir. */
    data class Asset(val url: String, val archive: Boolean, val fileName: String = "")

    data class ModelDef(
        val id: String,
        val displayName: String,
        val kind: Kind,
        val dirName: String,        // extracted top-level dir under filesDir
        val approxSizeMb: Int,
        val speakerCount: Int,      // 1 => no speaker slider
        val assets: List<Asset>,
        val note: String = "",
        val supported: Boolean = true,
        /** ISO-639-1 language the model speaks; "" = multilingual / skip the language guard. */
        val lang: String = "en",
    )

    val ALL: List<ModelDef> = listOf(
        // Kitten is listed first: it is the smallest/fastest and the recommended first download.
        ModelDef(
            id = "kitten",
            displayName = "Kitten Nano int8 (English, 24 kHz)",
            kind = Kind.KITTEN,
            dirName = "kitten-nano-en-v0_8-int8",
            approxSizeMb = 31,
            speakerCount = 8,
            assets = listOf(Asset("$TTS_BASE/kitten-nano-en-v0_8-int8.tar.bz2", archive = true)),
            note = "Tiny, fast. Multiple expressive reference voices.",
        ),
        ModelDef(
            id = "kokoro",
            displayName = "Kokoro int8 (English, 24 kHz)",
            kind = Kind.KOKORO,
            dirName = "kokoro-int8-en-v0_19",
            approxSizeMb = 103,
            speakerCount = 11,
            assets = listOf(Asset("$TTS_BASE/kokoro-int8-en-v0_19.tar.bz2", archive = true)),
            note = "Best quality, natural. Distilled StyleTTS2 model.",
        ),
        ModelDef(
            id = "supertonic",
            displayName = "Supertonic v3 int8 (expressive)",
            kind = Kind.SUPERTONIC,
            dirName = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
            approxSizeMb = 129,
            speakerCount = 1,
            assets = listOf(Asset("$TTS_BASE/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2", archive = true)),
            note = "Supports inline prosody tags like <laugh>, <sigh>, <breath>.",
        ),
        ModelDef(
            id = "zipvoice",
            displayName = "ZipVoice distill int8 (voice cloning)",
            kind = Kind.ZIPVOICE,
            dirName = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia",
            approxSizeMb = 163, // 109 archive + 54 vocoder
            speakerCount = 1,
            assets = listOf(
                Asset("$TTS_BASE/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2", archive = true),
                Asset("$VOCODER_BASE/vocos_24khz.onnx", archive = false, fileName = "vocos_24khz.onnx"),
            ),
            note = "Zero-shot cloning. Uses a bundled reference voice (zh-en).",
            lang = "", // multilingual (zh-en) — skip the English-only guard
        ),
        ModelDef(
            id = "stylettes2",
            displayName = "StyleTTS2 (not supported)",
            kind = Kind.UNSUPPORTED,
            dirName = "stylettes2",
            approxSizeMb = 0,
            speakerCount = 1,
            assets = emptyList(),
            supported = false,
            note = "StyleTTS2 has no sherpa-onnx runtime. Kokoro is its distilled ONNX derivative — select Kokoro instead.",
        ),
    )

    fun byId(id: String?): ModelDef = ALL.firstOrNull { it.id == id } ?: ALL.first()

    // ---------------------------------------------------------------------------------------------
    // Voice naming — a stable "<modelId>:<speakerIndex>" id, parsed back at synthesis time.
    // ---------------------------------------------------------------------------------------------

    fun voiceName(def: ModelDef, sid: Int): String = "${def.id}:$sid"

    /** Parse a "<modelId>:<sid>" voice name back to a supported model + valid speaker index. */
    fun parseVoice(name: String?): Pair<ModelDef, Int>? {
        if (name.isNullOrBlank()) return null
        val idx = name.lastIndexOf(':')
        if (idx <= 0 || idx == name.length - 1) return null
        val def = ALL.firstOrNull { it.id == name.substring(0, idx) && it.supported } ?: return null
        val sid = name.substring(idx + 1).toIntOrNull() ?: return null
        if (sid < 0 || sid >= def.speakerCount.coerceAtLeast(1)) return null
        return def to sid
    }

    // ---------------------------------------------------------------------------------------------
    // Friendly per-speaker labels (display only — the stored id stays "<modelId>:<sid>")
    // ---------------------------------------------------------------------------------------------

    /** Friendly display label for one neural voice. Empty [desc] => single-line fallback row. */
    data class VoiceLabel(val name: String, val desc: String)

    /**
     * Curated per-speaker labels keyed by [ModelDef.id]; list index == sid. Kitten's 8 voices are
     * the official KittenML names in embedding order (m,f alternating); gender is from the voice-id
     * suffix, the tone adjectives are the documented KittenML split. Uncurated models fall back to
     * "Voice N". English-only, alongside the existing English [ModelDef.displayName]/[ModelDef.note].
     */
    private val CURATED_VOICES: Map<String, List<VoiceLabel>> = mapOf(
        "kitten" to listOf(
            VoiceLabel("Jasper", "Male · deep, steady — authoritative narration"),
            VoiceLabel("Bella", "Female · warm, soft — cozy, gentle reading"),
            VoiceLabel("Bruno", "Male · full, grounded — firm, confident narration"),
            VoiceLabel("Luna", "Female · warm, mellow — calm, soothing reads"),
            VoiceLabel("Hugo", "Male · bright, articulate — clear, upbeat narration"),
            VoiceLabel("Rosie", "Female · bright, lively — expressive, upbeat reading"),
            VoiceLabel("Leo", "Male · balanced, natural — all-round default narrator"),
            VoiceLabel("Kiki", "Female · light, warm — friendly, intimate reading"),
        ),
        "kokoro" to listOf(
            VoiceLabel("Ava", "American female · warm, natural — balanced default narrator"),
            VoiceLabel("Bella", "American female · warm, rich — expressive, engaging narration"),
            VoiceLabel("Nicole", "American female · soft, breathy — intimate, ASMR-style reading"),
            VoiceLabel("Sarah", "American female · clear, even — steady everyday narration"),
            VoiceLabel("Sky", "American female · light, youthful — bright, casual reading"),
            VoiceLabel("Adam", "American male · deep, heavy — bold male narration"),
            VoiceLabel("Michael", "American male · warm, steady — natural male narration"),
            VoiceLabel("Emma", "British female · warm, refined — polished British narration"),
            VoiceLabel("Isabella", "British female · smooth, measured — calm British reading"),
            VoiceLabel("George", "British male · mellow, mature — relaxed British narration"),
            VoiceLabel("Lewis", "British male · deep, mellow — low, unhurried narration"),
        ),
        "supertonic" to listOf(
            VoiceLabel("Milo", "Male · lively, upbeat — expressive, reads inline <laugh>/<sigh>/<breath> tags"),
        ),
        "zipvoice" to listOf(
            VoiceLabel("Nova", "Female · crisp, newsreader tone — zero-shot clone of the bundled zh-en reference"),
        ),
    )

    /** Labels for every speaker of [def]; length always == speakerCount. Falls back to
     *  "<fallbackPrefix> N" for uncurated ids or when speakerCount exceeds the curated list. */
    fun voiceLabels(def: ModelDef, fallbackPrefix: String): List<VoiceLabel> {
        val curated = CURATED_VOICES[def.id].orEmpty()
        return (0 until def.speakerCount.coerceAtLeast(1)).map { sid ->
            curated.getOrNull(sid) ?: VoiceLabel("$fallbackPrefix ${sid + 1}", "")
        }
    }

    /** Single-sid label (for read-only summaries like the pre-gen dialog). */
    fun voiceLabel(def: ModelDef, sid: Int, fallbackPrefix: String): VoiceLabel =
        CURATED_VOICES[def.id]?.getOrNull(sid) ?: VoiceLabel("$fallbackPrefix ${sid + 1}", "")

    /** ONNX inference threads. More threads = faster synthesis on multi-core phones. */
    private val inferenceThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** The default per-engine ONNX thread count (exposed so a multi-worker pool can divide cores). */
    val defaultInferenceThreads: Int get() = inferenceThreads

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun modelDir(context: Context, def: ModelDef): File = File(context.filesDir, def.dirName)

    private fun readyMarker(context: Context, def: ModelDef): File = File(modelDir(context, def), ".ready")

    fun isReady(context: Context, def: ModelDef): Boolean =
        def.supported && readyMarker(context, def).exists()

    // ---------------------------------------------------------------------------------------------
    // Config resolution (per model kind)
    // ---------------------------------------------------------------------------------------------

    fun resolveConfig(context: Context, def: ModelDef, numThreads: Int = inferenceThreads): OfflineTtsConfig? {
        val dir = modelDir(context, def)
        if (!dir.isDirectory) return null
        fun p(name: String): String = File(dir, name).absolutePath
        fun has(name: String): Boolean = File(dir, name).exists()

        return when (def.kind) {
            Kind.KOKORO -> {
                if (!has("model.int8.onnx") || !has("voices.bin") || !has("tokens.txt")) return null
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = p("model.int8.onnx"),
                            voices = p("voices.bin"),
                            tokens = p("tokens.txt"),
                            dataDir = p("espeak-ng-data"),
                        ),
                        numThreads = numThreads, provider = "cpu",
                    ),
                )
            }
            Kind.KITTEN -> {
                if (!has("model.int8.onnx") || !has("voices.bin") || !has("tokens.txt")) return null
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kitten = OfflineTtsKittenModelConfig(
                            model = p("model.int8.onnx"),
                            voices = p("voices.bin"),
                            tokens = p("tokens.txt"),
                            dataDir = p("espeak-ng-data"),
                        ),
                        numThreads = numThreads, provider = "cpu",
                    ),
                )
            }
            Kind.SUPERTONIC -> {
                if (!has("text_encoder.int8.onnx") || !has("vocoder.int8.onnx") || !has("tts.json")) return null
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        supertonic = OfflineTtsSupertonicModelConfig(
                            durationPredictor = p("duration_predictor.int8.onnx"),
                            textEncoder = p("text_encoder.int8.onnx"),
                            vectorEstimator = p("vector_estimator.int8.onnx"),
                            vocoder = p("vocoder.int8.onnx"),
                            ttsJson = p("tts.json"),
                            unicodeIndexer = p("unicode_indexer.bin"),
                            voiceStyle = p("voice.bin"),
                        ),
                        numThreads = numThreads, provider = "cpu",
                    ),
                )
            }
            Kind.ZIPVOICE -> {
                if (!has("encoder.int8.onnx") || !has("decoder.int8.onnx") ||
                    !has("tokens.txt") || !has("vocos_24khz.onnx")
                ) return null
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                            tokens = p("tokens.txt"),
                            encoder = p("encoder.int8.onnx"),
                            decoder = p("decoder.int8.onnx"),
                            vocoder = p("vocos_24khz.onnx"),
                            dataDir = p("espeak-ng-data"),
                            lexicon = p("lexicon.txt"),
                        ),
                        numThreads = numThreads, provider = "cpu",
                    ),
                )
            }
            Kind.UNSUPPORTED -> null
        }
    }

    /**
     * ZipVoice is zero-shot: it needs a reference audio clip + its transcript. We use the bundled
     * test_wavs/news-female.wav and its prompt line. Returns null for non-cloning models.
     */
    fun resolveGenerationConfig(context: Context, def: ModelDef, sid: Int, speed: Float): GenerationConfig? {
        if (def.kind != Kind.ZIPVOICE) return null
        val dir = modelDir(context, def)
        val refName = "news-female.wav"
        val refWav = File(dir, "test_wavs/$refName")
        val promptTxt = File(dir, "test_wavs/prompt.txt")
        if (!refWav.exists() || !promptTxt.exists()) {
            Log.w(TAG, "ZipVoice reference files missing")
            return null
        }
        val refText = promptTxt.readLines()
            .firstOrNull { it.startsWith(refName) }
            ?.removePrefix(refName)?.trim() ?: ""
        return try {
            val wave = WaveReader.readWave(refWav.absolutePath)
            GenerationConfig(
                speed = if (speed > 0f) speed else 1.0f,
                sid = sid,
                referenceAudio = wave.samples,
                referenceSampleRate = wave.sampleRate,
                referenceText = refText,
                numSteps = 4,
                extra = mapOf("min_char_in_sentence" to "10"),
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read ZipVoice reference wav: ${e.message}", e)
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Download + extraction
    // ---------------------------------------------------------------------------------------------

    fun downloadAndExtract(context: Context, def: ModelDef, onProgress: (Float) -> Unit) {
        require(def.supported) { "${def.displayName} cannot be downloaded" }
        val dir = modelDir(context, def)
        if (dir.exists() && !isReady(context, def)) dir.deleteRecursively()
        dir.mkdirs()

        val n = def.assets.size
        def.assets.forEachIndexed { i, asset ->
            val base = i.toFloat() / n
            val span = 1f / n
            if (asset.archive) {
                val tmp = File(context.filesDir, "${def.id}-$i.tar.bz2.part")
                if (tmp.exists()) tmp.delete()
                downloadTo(asset.url, tmp) { p -> onProgress(base + span * p * 0.7f) }
                Log.d(TAG, "Extracting ${asset.url}")
                onProgress(base + span * 0.75f)
                extractTarBz2(tmp, context.filesDir)
                tmp.delete()
            } else {
                val out = File(dir, asset.fileName)
                downloadTo(asset.url, out) { p -> onProgress(base + span * p) }
            }
            onProgress(base + span)
        }

        if (resolveConfig(context, def) == null) {
            dir.deleteRecursively()
            throw RuntimeException("Provisioning incomplete: expected files not found for ${def.id}")
        }
        readyMarker(context, def).writeText("ok")
        onProgress(1f)
        Log.d(TAG, "Model ${def.id} ready at ${dir.absolutePath}")
    }

    private fun downloadTo(url: String, dest: File, onProgress: (Float) -> Unit) {
        Log.d(TAG, "Downloading $url")
        val request = Request.Builder().url(url).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("Download failed: HTTP ${response.code} ($url)")
            val body = response.body ?: throw RuntimeException("Empty body: $url")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    var got = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        got += read
                        if (total > 0) onProgress((got.toFloat() / total))
                    }
                }
            }
        }
    }

    private fun extractTarBz2(archive: File, destRoot: File) {
        archive.inputStream().buffered().use { fis ->
            BZip2CompressorInputStream(fis).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    val rootPath = destRoot.canonicalPath
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val outFile = File(destRoot, entry.name)
                        if (!outFile.canonicalPath.startsWith(rootPath)) {
                            throw SecurityException("Bad tar entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tar.copyTo(out, 1 shl 16) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }
}
