package com.lagradost.quicknovel.llm

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.DataStore.mapper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Read-side client for the server character map (P3). Mirrors RemoteFixClient's plain
 * HttpURLConnection pattern; all calls blocking -> use from IO.
 */
object CharMapClient {
    private fun base(u: String) = u.trim().trimEnd('/')

    private fun get(url: String, timeoutMs: Int = 8000): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 4000
        conn.readTimeout = timeoutMs
        val ok = conn.responseCode in 200..299
        val body = if (ok) conn.inputStream.bufferedReader().readText() else null
        conn.disconnect()
        body
    } catch (t: Throwable) {
        null
    }

    /** Search characters/locations: "where did X appear and how did X interact" (spoiler-gated). */
    fun search(baseUrl: String, bookId: String, query: String, throughChapter: Int?): JsonNode? {
        val q = URLEncoder.encode(query, "UTF-8")
        val gate = throughChapter?.let { "&through_chapter=$it" } ?: ""
        val resp = get("${base(baseUrl)}/charmap/$bookId/search?q=$q$gate") ?: return null
        return runCatching { mapper.readTree(resp) }.getOrNull()
    }

    /** The full map (casting included). */
    fun map(baseUrl: String, bookId: String): JsonNode? {
        val resp = get("${base(baseUrl)}/charmap/$bookId", 15000) ?: return null
        return runCatching { mapper.readTree(resp) }.getOrNull()
    }
}
