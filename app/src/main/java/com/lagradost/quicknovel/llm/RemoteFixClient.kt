package com.lagradost.quicknovel.llm

import com.lagradost.quicknovel.DataStore
import com.lagradost.quicknovel.mvvm.logError
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP client for the optional GPU fix server (see server/). When a server URL is configured the
 * app offloads rewriting to it (seconds instead of minutes on-device). Plain HttpURLConnection +
 * Jackson so it adds no dependency; all calls are blocking (invoke from an IO context).
 */
object RemoteFixClient {
    private val mapper = DataStore.mapper

    data class ServerModel(val id: String = "", val name: String = "")
    data class JobSummary(
        val id: String = "",
        val model: String = "",
        val status: String = "",
        val progress: Int = 0,
        val total: Int = 0,
    )

    fun isConfigured(url: String?): Boolean = !url.isNullOrBlank()

    /** Synchronous single-chapter fix. Returns null on any failure (caller falls back to on-device). */
    fun fixSnippet(baseUrl: String, text: String, model: String?, previousChapters: String, memory: String): String? {
        val body = mapper.writeValueAsString(
            mapOf(
                "text" to text,
                "model" to model?.ifBlank { null },
                "previous_chapters" to previousChapters,
                "character_memory" to memory,
            )
        )
        val resp = request("POST", "${base(baseUrl)}/fix/snippet", body) ?: return null
        return runCatching { mapper.readTree(resp).get("fixed")?.asText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun listModels(baseUrl: String): List<ServerModel> {
        val resp = request("GET", "${base(baseUrl)}/models", null) ?: return emptyList()
        return runCatching {
            mapper.readTree(resp).get("models")?.map {
                ServerModel(it.get("id").asText(), it.get("name")?.asText() ?: it.get("id").asText())
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    fun listJobs(baseUrl: String): List<JobSummary> {
        val resp = request("GET", "${base(baseUrl)}/jobs", null) ?: return emptyList()
        return runCatching {
            mapper.readTree(resp).get("jobs")?.map {
                JobSummary(
                    id = it.get("id").asText(),
                    model = it.get("model")?.asText() ?: "",
                    status = it.get("status")?.asText() ?: "",
                    progress = it.get("progress")?.asInt() ?: 0,
                    total = it.get("total")?.asInt() ?: 0,
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    fun submitBatch(baseUrl: String, model: String?, items: List<Pair<String, String>>): String? {
        val body = mapper.writeValueAsString(
            mapOf("model" to model?.ifBlank { null }, "items" to items.map { mapOf("id" to it.first, "text" to it.second) })
        )
        val resp = request("POST", "${base(baseUrl)}/fix/batch", body) ?: return null
        return runCatching { mapper.readTree(resp).get("job_id")?.asText() }.getOrNull()
    }

    fun cancelJob(baseUrl: String, id: String): Boolean =
        request("POST", "${base(baseUrl)}/jobs/$id/cancel", "") != null

    fun health(baseUrl: String): Boolean = request("GET", "${base(baseUrl)}/health", null) != null

    /** Fast reachability probe (short timeout, no error logging) used to decide server vs on-device. */
    fun reachable(baseUrl: String, timeoutMs: Int = 2500): Boolean = try {
        val conn = URL("${base(baseUrl)}/health").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
        (conn.responseCode in 200..299).also { conn.disconnect() }
    } catch (t: Throwable) {
        false
    }

    private fun base(url: String): String = url.trim().trimEnd('/')

    private fun request(method: String, url: String, body: String?): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 600_000 // a chapter fix can take a while server-side
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
