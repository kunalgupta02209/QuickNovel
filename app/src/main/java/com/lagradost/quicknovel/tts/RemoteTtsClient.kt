package com.lagradost.quicknovel.tts

import com.lagradost.quicknovel.DataStore
import com.lagradost.quicknovel.mvvm.logError
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP client for the optional server-side TTS offload (the server's /tts endpoints). The app computes each
 * sentence's key = TtsAudioCache.keyFor (sha1), so returned "<key>.wav" files drop straight into the
 * cache with the SAME voice. Blocking (call from IO); all failures -> null (caller falls back to
 * on-device). Mirrors RemoteFixClient.
 */
object RemoteTtsClient {
    private val mapper = DataStore.mapper

    data class ServerVoice(val id: String = "", val speakers: Int = 1, val ready: Boolean = false)
    data class Sentence(val key: String, val text: String, val sid: Int? = null, val speed: Float? = null)
    data class ChapterReq(val index: Int, val sentences: List<Sentence>, val name: String = "")
    data class JobDetail(
        val id: String = "",
        val status: String = "",
        val progress: Int = 0,
        val total: Int = 0,
        val error: String? = null,
        val readyChapters: List<Int> = emptyList(),
    )

    fun reachable(baseUrl: String, timeoutMs: Int = 2500): Boolean = try {
        val conn = URL("${base(baseUrl)}/tts/health").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
        (conn.responseCode in 200..299).also { conn.disconnect() }
    } catch (t: Throwable) {
        false
    }

    fun listVoices(baseUrl: String): List<ServerVoice> {
        val resp = request("GET", "${base(baseUrl)}/tts/models", null) ?: return emptyList()
        return runCatching {
            mapper.readTree(resp).get("models")?.map {
                ServerVoice(
                    id = it.get("id").asText(),
                    speakers = it.get("speakers")?.asInt() ?: 1,
                    ready = it.get("ready")?.asBoolean() ?: false,
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    fun submitBatch(
        baseUrl: String, bookId: String, modelId: String, sid: Int, sampleRate: Int,
        chapters: List<ChapterReq>, bookName: String = "",
        deviceId: String = "", deviceName: String = "",
        apiName: String = "", author: String = "",
    ): String? {
        val body = mapper.writeValueAsString(
            mapOf(
                "book_id" to bookId,
                "book_name" to bookName,
                "api_name" to apiName,
                "author" to author,
                "model_id" to modelId,
                "sid" to sid,
                "sample_rate" to sampleRate,
                "device_id" to deviceId,
                "device_name" to deviceName,
                "items" to chapters.map { ch ->
                    mapOf(
                        "index" to ch.index,
                        "name" to ch.name,
                        "sentences" to ch.sentences.map {
                            mapOf("key" to it.key, "text" to it.text, "sid" to it.sid, "speed" to it.speed)
                        },
                    )
                },
            )
        )
        val resp = request("POST", "${base(baseUrl)}/tts/batch", body) ?: return null
        return runCatching { mapper.readTree(resp).get("job_id")?.asText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun getJob(baseUrl: String, id: String): JobDetail? {
        val resp = request("GET", "${base(baseUrl)}/tts/jobs/$id", null) ?: return null
        return runCatching {
            val n = mapper.readTree(resp)
            JobDetail(
                id = n.get("id")?.asText() ?: id,
                status = n.get("status")?.asText() ?: "",
                progress = n.get("progress")?.asInt() ?: 0,
                total = n.get("total")?.asInt() ?: 0,
                error = n.get("error")?.takeIf { !it.isNull }?.asText(),
                readyChapters = n.get("ready_chapters")?.map { it.asInt() } ?: emptyList(),
            )
        }.getOrNull()
    }

    fun cancelJob(baseUrl: String, id: String): Boolean =
        request("POST", "${base(baseUrl)}/tts/jobs/$id/cancel", "") != null

    fun pauseJob(baseUrl: String, id: String): Boolean =
        request("POST", "${base(baseUrl)}/tts/jobs/$id/pause", "") != null

    fun resumeJob(baseUrl: String, id: String): Boolean =
        request("POST", "${base(baseUrl)}/tts/jobs/$id/resume", "") != null

    fun fetchChapterManifest(baseUrl: String, bookId: String, modelId: String, sid: Int, index: Int): Set<String> {
        val resp = request("GET", "${audioBase(baseUrl, bookId, modelId, sid, index)}/manifest", null) ?: return emptySet()
        return runCatching {
            mapper.readTree(resp).get("keys")?.map { it.asText() }?.toSet() ?: emptySet()
        }.getOrElse { emptySet() }
    }

    /** Open the chapter's ZIP (of <key>.wav entries). Caller wraps in ZipInputStream and MUST close it. */
    fun openChapterZip(baseUrl: String, bookId: String, modelId: String, sid: Int, index: Int): InputStream? = try {
        val conn = URL("${audioBase(baseUrl, bookId, modelId, sid, index)}.zip").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 120_000
        if (conn.responseCode in 200..299) conn.inputStream
        else { conn.disconnect(); null }
    } catch (t: Throwable) {
        logError(t); null
    }

    private fun audioBase(baseUrl: String, bookId: String, modelId: String, sid: Int, index: Int): String =
        "${base(baseUrl)}/tts/audio/$bookId/$modelId/$sid/$index"

    private fun base(url: String): String = url.trim().trimEnd('/')

    private fun request(method: String, url: String, body: String?): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            logError(Exception("HTTP ${conn.responseCode} for $url"))
            null
        }
    } catch (t: Throwable) {
        logError(t); null
    }
}
