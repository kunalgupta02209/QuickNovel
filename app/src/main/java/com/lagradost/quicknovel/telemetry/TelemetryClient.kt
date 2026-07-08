package com.lagradost.quicknovel.telemetry

import java.net.HttpURLConnection
import java.net.URL

/**
 * Fire-and-forget HTTP for device telemetry. Mirrors RemoteFixClient's plain HttpURLConnection
 * pattern but with SHORT timeouts, no logging (telemetry must never spam logs or block anything),
 * and boolean results. Call only from a background thread.
 */
object TelemetryClient {
    fun postSnapshot(baseUrl: String, json: String): Boolean = try {
        val conn = URL("${baseUrl.trim().trimEnd('/')}/telemetry/device").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    } catch (t: Throwable) {
        false // silent by design
    }
}
