package com.lagradost.quicknovel.util

import android.content.Context
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.DOWNLOAD_TOTAL
import com.lagradost.quicknovel.DataStore.mapper
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.ui.download.DownloadFragment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Device-to-device book sync (P3): the server keeps every chapter text it has seen via /tts/batch;
 * this pulls a book onto THIS device — writes files/<api>/<author>/<name>/N.txt exactly like a
 * normal download plus the records the library needs, so the book appears in Downloads and TTS/fix
 * features work as if it was downloaded here.
 */
object ServerBookSync {
    data class ServerBook(
        val bookId: String = "",
        val name: String = "",
        val apiName: String = "",
        val author: String = "",
        val chapters: Int = 0,
        val maxIndex: Int = -1,
    )

    fun listBooks(baseUrl: String): List<ServerBook> = try {
        val conn = URL("${baseUrl.trim().trimEnd('/')}/books").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000; conn.readTimeout = 10000
        val body = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        mapper.readTree(body).get("books")?.map {
            ServerBook(
                bookId = it.get("book_id")?.asText() ?: "",
                name = it.get("book_name")?.asText() ?: "",
                apiName = it.get("api_name")?.asText() ?: "",
                author = it.get("author")?.asText() ?: "",
                chapters = it.get("chapters")?.asInt() ?: 0,
                maxIndex = it.get("max_index")?.asInt() ?: -1,
            )
        }?.filter { it.bookId.isNotBlank() && it.name.isNotBlank() } ?: emptyList()
    } catch (t: Throwable) {
        logError(t); emptyList()
    }

    /** Blocking — call from IO. Returns chapters written, or -1 on failure. */
    fun import(ctx: Context, baseUrl: String, book: ServerBook): Int {
        return try {
            val api = book.apiName.ifBlank { "Imported" }
            val sApi = BookDownloader2Helper.sanitizeFilename(api)
            val sAuthor = if (book.author.isBlank()) "" else BookDownloader2Helper.sanitizeFilename(book.author)
            val sName = BookDownloader2Helper.sanitizeFilename(book.name)
            val dir = File(ctx.filesDir, "$sApi/$sAuthor/$sName")
            dir.mkdirs()

            val conn = URL("${baseUrl.trim().trimEnd('/')}/books/${book.bookId}/chapters.zip")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 4000; conn.readTimeout = 120000
            var written = 0
            ZipInputStream(conn.inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name
                    if (n.endsWith(".txt") && n.removeSuffix(".txt").toIntOrNull() != null) {
                        File(dir, n).outputStream().use { zip.copyTo(it) }
                        written++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            conn.disconnect()
            if (written == 0) return -1

            // Library + downloadInfo records, exactly what a local download would have written.
            val id = BookDownloader2Helper.generateId(api, book.author.ifBlank { null }, book.name)
            setKey(DOWNLOAD_TOTAL, id.toString(), book.maxIndex + 1)
            setKey(
                DOWNLOAD_FOLDER, id.toString(),
                DownloadFragment.DownloadData(
                    source = "", name = book.name, author = book.author.ifBlank { null },
                    posterUrl = null, rating = null, peopleVoted = null, views = null,
                    synopsis = null, tags = null, apiName = api,
                    lastUpdated = System.currentTimeMillis(), lastDownloaded = System.currentTimeMillis(),
                )
            )
            written
        } catch (t: Throwable) {
            logError(t); -1
        }
    }
}
