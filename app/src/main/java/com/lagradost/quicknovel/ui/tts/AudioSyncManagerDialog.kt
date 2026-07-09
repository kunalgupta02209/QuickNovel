package com.lagradost.quicknovel.ui.tts

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.DownloadActionType
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.tts.RemoteTtsManager
import com.lagradost.quicknovel.tts.TtsAudioCache
import com.lagradost.quicknovel.tts.TtsModels
import com.lagradost.quicknovel.tts.TtsPregenManager
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.runOnMainThread

/**
 * Audio sync & storage manager. Grounded in the ACTUAL on-disk cache ([TtsAudioCache.allVoices]) —
 * so it shows every generated voice regardless of producer (on-device pre-gen OR server sync), unlike
 * [TtsGenerateDialog] which only lists pregen records. Plus live control of in-flight syncs
 * (pause/resume/stop) and per-voice / per-book deletion to reclaim space.
 */
object AudioSyncManagerDialog {

    fun show(context: Context) {
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
        }
        scroll.addView(root)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.audio_sync_manager)
            .setView(scroll)
            .setPositiveButton(R.string.sort_close, null)
            .create()

        fun refresh() = rebuild(context, root) { refreshLater(context, root) }
        refresh()
        dialog.show()
    }

    private fun refreshLater(context: Context, root: LinearLayout) {
        root.post { rebuild(context, root) { refreshLater(context, root) } }
    }

    private fun rebuild(context: Context, root: LinearLayout, again: () -> Unit) {
        root.removeAllViews()
        ioSafe {
            val voices = TtsAudioCache.allVoices(context)
            val names = bookNames(context)
            val remote = RemoteTtsManager.remoteProgressSnapshot()
            val pregen = TtsPregenManager.records()
            runOnMainThread {
                // ---- storage summary ----
                val totalBytes = voices.sumOf { it.bytes }
                val totalCh = voices.sumOf { it.chaptersDone }
                root.addView(header(context, context.getString(
                    R.string.audio_sync_total, fmtSize(totalBytes), totalCh, voices.size)))

                // ---- active syncs ----
                val activeRemote = remote.filter { it.status != "done" }
                val activePregen = pregen.filter {
                    TtsPregenManager.pregenProgress[it.key]?.state.let { s ->
                        s == DownloadState.IsDownloading || s == DownloadState.IsPaused || s == DownloadState.IsPending
                    }
                }
                if (activeRemote.isNotEmpty() || activePregen.isNotEmpty()) {
                    root.addView(sectionLabel(context, R.string.audio_sync_active))
                    activeRemote.forEach { p ->
                        val bookId = p.key.substringBefore("|")
                        root.addView(activeRemoteRow(context, p, names[bookId.removePrefix("b")] ?: bookId, again))
                    }
                    activePregen.forEach { rec ->
                        root.addView(activePregenRow(context, rec, again))
                    }
                }

                // ---- stored audio, grouped by book ----
                root.addView(sectionLabel(context, R.string.audio_sync_stored))
                if (voices.isEmpty()) {
                    root.addView(dim(context, context.getString(R.string.audio_sync_empty)))
                } else {
                    voices.groupBy { it.bookId }
                        .toList()
                        .sortedByDescending { (_, vs) -> vs.sumOf { it.bytes } }
                        .forEach { (bookId, vs) ->
                            root.addView(bookHeader(context, names[bookId.removePrefix("b")] ?: bookId, bookId, vs, again))
                            vs.sortedByDescending { it.bytes }.forEach { v ->
                                root.addView(voiceRow(context, v, again))
                            }
                        }
                }
            }
        }
    }

    // ---- rows ----

    private fun activeRemoteRow(context: Context, p: RemoteTtsManager.RemoteProgress, name: String, again: () -> Unit): View {
        val row = vrow(context)
        row.addView(title(context, "☁ $name"))
        row.addView(dim(context, "server · ${p.done}/${p.total} · ${p.status}"))
        val paused = p.status == "paused"
        val buttons = brow(context)
        buttons.addView(textButton(context, if (paused) R.string.tts_resume else R.string.tts_pause) {
            RemoteTtsManager.addPendingAction(p.key, if (paused) DownloadActionType.Resume else DownloadActionType.Pause)
            again()
        })
        buttons.addView(textButton(context, R.string.tts_stop) {
            RemoteTtsManager.addPendingAction(p.key, DownloadActionType.Stop); again()
        })
        row.addView(buttons)
        return row
    }

    private fun activePregenRow(context: Context, rec: TtsPregenManager.TtsPregenRecord, again: () -> Unit): View {
        val row = vrow(context)
        val paused = TtsPregenManager.pregenProgress[rec.key]?.state == DownloadState.IsPaused
        row.addView(title(context, "▸ ${rec.name}"))
        row.addView(dim(context, "on-device · ${rec.generatedChapters}/${rec.totalChapters} · ${if (paused) "paused" else "generating"}"))
        val buttons = brow(context)
        buttons.addView(textButton(context, if (paused) R.string.tts_resume else R.string.tts_pause) {
            TtsPregenManager.addPendingAction(rec.key, if (paused) DownloadActionType.Resume else DownloadActionType.Pause)
            again()
        })
        buttons.addView(textButton(context, R.string.tts_stop) {
            TtsPregenManager.addPendingAction(rec.key, DownloadActionType.Stop); again()
        })
        row.addView(buttons)
        return row
    }

    private fun bookHeader(context: Context, name: String, bookId: String, vs: List<TtsAudioCache.VoiceRef>, again: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 12), 0, dp(context, 2))
        }
        row.addView(TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(themeColor(context, R.attr.textColor))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        row.addView(textButton(context, R.string.audio_sync_delete_book) {
            confirm(context, context.getString(R.string.audio_sync_delete_book_confirm, name)) {
                TtsAudioCache.deleteBook(context, bookId); again()
            }
        })
        return row
    }

    private fun voiceRow(context: Context, v: TtsAudioCache.VoiceRef, again: () -> Unit): View {
        val def = TtsModels.byId(v.modelId)
        val voiceName = TtsModels.voiceLabel(def, v.sid, context.getString(R.string.tts_voice)).name
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 4), 0, dp(context, 4))
        }
        row.addView(TextView(context).apply {
            text = "${def.displayName.substringBefore(" ")} · $voiceName · ${v.chaptersDone} ch · ${fmtSize(v.bytes)}"
            textSize = 13f
            alpha = 0.85f
            setTextColor(themeColor(context, R.attr.textColor))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        row.addView(textButton(context, R.string.tts_pregen_delete) {
            TtsAudioCache.deleteVoice(context, v.bookId, v.modelId, v.sid); again()
        })
        return row
    }

    // ---- name resolution (bookId "b<id>" -> title) ----

    private fun bookNames(context: Context): Map<String, String> {
        val out = HashMap<String, String>()
        runCatching {
            getKeys(DOWNLOAD_FOLDER)?.forEach { key ->
                val d = getKey<DownloadFragment.DownloadData>(key) ?: return@forEach
                out[BookDownloader2Helper.generateId(d.apiName, d.author, d.name).toString()] = d.name
            }
        }
        return out
    }

    // ---- ui helpers ----

    private fun confirm(context: Context, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(context)
            .setMessage(msg)
            .setPositiveButton(R.string.tts_pregen_delete) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun header(context: Context, text: String) = TextView(context).apply {
        this.text = text; textSize = 13f
        setTextColor(themeColor(context, R.attr.textColor)); setPadding(0, 0, 0, dp(context, 6))
    }

    private fun sectionLabel(context: Context, res: Int) = TextView(context).apply {
        setText(res); textSize = 12f; isAllCaps = true; alpha = 0.6f
        setTextColor(themeColor(context, R.attr.colorPrimary)); setPadding(0, dp(context, 14), 0, dp(context, 2))
    }

    private fun title(context: Context, t: String) = TextView(context).apply {
        text = androidx.core.text.HtmlCompat.fromHtml(t, 0); textSize = 14f
        setTextColor(themeColor(context, R.attr.textColor))
    }

    private fun dim(context: Context, t: String) = TextView(context).apply {
        text = t; textSize = 12f; alpha = 0.7f; setTextColor(themeColor(context, R.attr.textColor))
    }

    private fun vrow(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(context, 8), 0, dp(context, 8))
    }

    private fun brow(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
    }

    private fun textButton(context: Context, textRes: Int, onClick: () -> Unit) =
        android.widget.Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            setText(textRes); isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
            setTextColor(themeColor(context, R.attr.colorPrimary))
            setOnClickListener { onClick() }
        }

    private fun themeColor(context: Context, attr: Int): Int {
        val tv = android.util.TypedValue(); context.theme.resolveAttribute(attr, tv, true); return tv.data
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%d MB".format(bytes / (1024 * 1024))
        else -> "%d KB".format(bytes / 1024)
    }

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
