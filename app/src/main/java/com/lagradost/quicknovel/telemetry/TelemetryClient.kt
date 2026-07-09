package com.lagradost.quicknovel.telemetry

import java.net.HttpURLConnection
import java.net.URL

/**
 * Fire-and-forget HTTP for device telemetry. Mirrors RemoteFixClient's plain HttpURLConnection
 * pattern but with SHORT timeouts, no logging (telemetry must never spam logs or block anything),
 * and boolean results. Call only from a background thread.
 */
object TelemetryClient {
    /** POST the snapshot; returns the response body on success (it carries queued server->device
     *  commands, e.g. {"commands":[{"type":"sync_books"}]}), null on failure. */
    fun postSnapshot(baseUrl: String, json: String): String? = try {
        val conn = URL("${baseUrl.trim().trimEnd('/')}/telemetry/device").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val body = if (conn.responseCode in 200..299)
            conn.inputStream.bufferedReader().readText() else null
        conn.disconnect()
        body
    } catch (t: Throwable) {
        null // silent by design
    }
}
