package com.lagradost.quicknovel.ui.tts

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.EPUB_TTS_OD_MODEL
import com.lagradost.quicknovel.EPUB_TTS_OD_VOICE
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.DialogTtsGenerateBinding
import com.lagradost.quicknovel.tts.TtsModels
import com.lagradost.quicknovel.tts.TtsPregenManager
import com.lagradost.quicknovel.ui.download.DownloadFragment

/**
 * Combined "Read-aloud audio" surface: pick a downloaded book + a chapter range and start background
 * on-device TTS generation, and manage (delete-read / sync / delete) everything already pre-generated
 * across all ebooks. Opened from the Read-aloud settings sheet.
 */
object TtsGenerateDialog {

    private data class BookRow(val name: String, val author: String?, val apiName: String, val poster: String?, val chapters: Int)

    fun show(context: Context) {
        val binding = DialogTtsGenerateBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)

        // ---- downloaded books ----
        val books = loadBooks(context)
        if (books.isEmpty()) {
            binding.ttsGenBook.visibility = View.GONE
            binding.ttsGenRange.visibility = View.GONE
            binding.ttsGenRangeLabel.text = context.getString(R.string.no_data)
            binding.ttsGenStart.isEnabled = false
        } else {
            binding.ttsGenBook.adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item, books.map { it.name }
            )
        }

        // ---- model / voice (reader defaults) ----
        val def = TtsModels.byId(getKey<String>(EPUB_TTS_OD_MODEL) ?: "kitten")
        val sid = TtsModels.parseVoice(getKey<String>(EPUB_TTS_OD_VOICE))?.second ?: 0
        val ready = TtsModels.isReady(context, def)
        binding.ttsGenVoiceLabel.text =
            "${def.displayName}  ·  ${TtsModels.voiceLabel(def, sid, context.getString(R.string.tts_voice)).name}"
        binding.ttsGenHint.visibility = if (ready) View.GONE else View.VISIBLE
        binding.ttsGenStart.isEnabled = ready && books.isNotEmpty()

        fun applyRange(book: BookRow) {
            val maxCh = book.chapters.coerceAtLeast(1)
            runCatching {
                binding.ttsGenRange.valueFrom = 1f
                binding.ttsGenRange.valueTo = maxCh.coerceAtLeast(2).toFloat()
                binding.ttsGenRange.stepSize = 1f
                binding.ttsGenRange.setValues(1f, maxCh.toFloat())
            }
            binding.ttsGenRangeLabel.text = context.getString(R.string.tts_pregen_range) + "  1 – $maxCh"
        }
        if (books.isNotEmpty()) applyRange(books[0])

        binding.ttsGenBook.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyRange(books[pos])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        binding.ttsGenRange.addOnChangeListener { slider, _, _ ->
            val v = slider.values
            if (v.size == 2) binding.ttsGenRangeLabel.text =
                context.getString(R.string.tts_pregen_range) + "  ${v[0].toInt()} – ${v[1].toInt()}"
        }

        binding.ttsGenStart.setOnClickListener {
            val idx = binding.ttsGenBook.selectedItemPosition
            val book = books.getOrNull(idx) ?: return@setOnClickListener
            val values = binding.ttsGenRange.values
            val start = (values.minOrNull() ?: 1f).toInt() - 1  // 1-based UI -> 0-based index
            val end = (values.maxOrNull() ?: 1f).toInt() - 1
            val req = TtsPregenManager.PregenRequest(
                bookId = BookDownloader2Helper.generateId(book.apiName, book.author, book.name),
                apiName = book.apiName, author = book.author, name = book.name, posterUrl = book.poster,
                modelId = def.id, sid = sid,
                rangeStart = start.coerceAtLeast(0), rangeEnd = end.coerceAtLeast(start),
            )
            TtsPregenManager.enqueue(context, req)
            Toast.makeText(context, R.string.tts_pregen_started, Toast.LENGTH_SHORT).show()
            renderRecords(context, binding)
        }

        // ---- existing records + live refresh ----
        val onRecord: (Pair<String, TtsPregenManager.TtsPregenRecord>) -> Unit =
            { binding.root.post { renderRecords(context, binding) } }
        val onRemoved: (String) -> Unit = { binding.root.post { renderRecords(context, binding) } }
        TtsPregenManager.reconcileFromDisk(context)
        TtsPregenManager.pregenRecordChanged += onRecord
        TtsPregenManager.pregenRemoved += onRemoved
        dialog.setOnDismissListener {
            TtsPregenManager.pregenRecordChanged -= onRecord
            TtsPregenManager.pregenRemoved -= onRemoved
        }
        renderRecords(context, binding)

        dialog.show()
    }

    private fun loadBooks(context: Context): List<BookRow> {
        val keys = getKeys(DOWNLOAD_FOLDER) ?: return emptyList()
        return keys.mapNotNull { key ->
            val d = getKey<DownloadFragment.DownloadData>(key) ?: return@mapNotNull null
            if (d.apiName == BookDownloader2Helper.IMPORT_SOURCE || d.apiName == BookDownloader2Helper.IMPORT_SOURCE_PDF) return@mapNotNull null
            val total = BookDownloader2Helper.downloadInfo(context, d.author, d.name, d.apiName)?.total?.toInt() ?: 0
            if (total <= 0) return@mapNotNull null
            BookRow(d.name, d.author, d.apiName, d.posterUrl, total)
        }.sortedBy { it.name }
    }

    private fun renderRecords(context: Context, binding: DialogTtsGenerateBinding) {
        val records = TtsPregenManager.records()
        binding.ttsGenEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.ttsGenRecords.removeAllViews()
        for (rec in records) {
            binding.ttsGenRecords.addView(buildRow(context, binding, rec))
        }
    }

    private fun buildRow(
        context: Context,
        binding: DialogTtsGenerateBinding,
        rec: TtsPregenManager.TtsPregenRecord,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 10), 0, dp(context, 10))
        }
        val title = TextView(context).apply {
            text = rec.name
            textSize = 15f
            setTextColor(themeTextColor(context))
        }
        val progress = TtsPregenManager.pregenProgress[rec.key]
        val stateTxt = when (progress?.state) {
            DownloadState.IsDownloading -> "generating"
            DownloadState.IsPaused -> "paused"
            DownloadState.IsPending -> "queued"
            DownloadState.IsFailed -> "failed"
            DownloadState.IsStopped -> "stopped"
            else -> "ready"
        }
        val sub = TextView(context).apply {
            text = "${TtsModels.byId(rec.modelId).id} · voice ${rec.sid + 1} · " +
                    "${rec.generatedChapters}/${rec.totalChapters} ch · ${rec.bytes / (1024 * 1024)} MB · $stateTxt"
            textSize = 12f
            alpha = 0.7f
            setTextColor(themeTextColor(context))
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(textButton(context, R.string.tts_pregen_delete_read) {
            TtsPregenManager.deleteReadChapters(context, rec.key); renderRecords(context, binding)
        })
        buttons.addView(textButton(context, R.string.tts_pregen_sync) {
            val latest = BookDownloader2Helper.downloadInfo(context, rec.author, rec.name, rec.apiName)?.total?.toInt() ?: 0
            TtsPregenManager.sync(context, rec.key, latest); renderRecords(context, binding)
        })
        buttons.addView(textButton(context, R.string.tts_pregen_delete) {
            TtsPregenManager.deleteAll(context, rec.key); renderRecords(context, binding)
        })
        row.addView(title)
        row.addView(sub)
        row.addView(buttons)
        return row
    }

    private fun textButton(context: Context, textRes: Int, onClick: () -> Unit): android.widget.Button =
        android.widget.Button(context).apply {
            setText(textRes)
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
            setBackgroundColor(0)
            setTextColor(context.theme.let { t ->
                val tv = android.util.TypedValue(); t.resolveAttribute(R.attr.colorPrimary, tv, true); tv.data
            })
            setOnClickListener { onClick() }
        }

    private fun themeTextColor(context: Context): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(R.attr.textColor, tv, true)
        return tv.data
    }

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
