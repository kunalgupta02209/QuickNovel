package com.lagradost.quicknovel.ui.llm

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ReadActivity2
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.databinding.DialogLlmBackgroundBinding
import com.lagradost.quicknovel.llm.LlmFixManager
import com.lagradost.quicknovel.llm.LlmModels
import com.lagradost.quicknovel.ui.download.DownloadFragment

/**
 * "Rewrite chapters in background" — pick a downloaded book + chapter range and spin off an
 * [LlmFixManager] job; plus manage everything already rewritten across ebooks. Twin of TtsGenerateDialog.
 */
object LlmBackgroundDialog {
    private data class BookRow(val name: String, val author: String?, val apiName: String, val poster: String?, val chapters: Int)

    fun show(activity: ReadActivity2, viewModel: ReadActivityViewModel, graphOnly: Boolean = false) {
        val ctx: Context = activity
        val b = DialogLlmBackgroundBinding.inflate(LayoutInflater.from(ctx))
        val dialog = BottomSheetDialog(ctx)
        dialog.setContentView(b.root)

        if (graphOnly) {
            b.llmBgTitle.setText(R.string.llm_build_graph_title)
            b.llmBgStart.setText(R.string.llm_build_graph_start)
        }

        val books = loadBooks(ctx)
        if (books.isEmpty()) {
            b.llmBgBook.visibility = View.GONE
            b.llmBgRange.visibility = View.GONE
            b.llmBgStart.isEnabled = false
            b.llmBgRangeLabel.text = ctx.getString(R.string.no_data)
        } else {
            b.llmBgBook.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, books.map { it.name })
        }

        fun applyRange(book: BookRow) {
            val max = book.chapters.coerceAtLeast(1)
            val upper = if (graphOnly) minOf(10, max) else max // character map defaults to the first 10 chapters
            runCatching {
                b.llmBgRange.valueFrom = 1f
                b.llmBgRange.valueTo = max.coerceAtLeast(2).toFloat()
                b.llmBgRange.stepSize = 1f
                b.llmBgRange.setValues(1f, upper.toFloat())
            }
            b.llmBgRangeLabel.text = "Chapters  1 – $upper"
        }
        if (books.isNotEmpty()) applyRange(books[0])
        b.llmBgBook.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyRange(books[pos])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.llmBgRange.addOnChangeListener { s, _, _ ->
            val v = s.values
            if (v.size == 2) b.llmBgRangeLabel.text = "Chapters  ${v[0].toInt()} – ${v[1].toInt()}"
        }

        b.llmBgStart.setOnClickListener {
            val book = books.getOrNull(b.llmBgBook.selectedItemPosition) ?: return@setOnClickListener
            val v = b.llmBgRange.values
            val start = (v.minOrNull() ?: 1f).toInt() - 1
            val end = (v.maxOrNull() ?: 1f).toInt() - 1
            val req = LlmFixManager.FixRequest(
                bookId = BookDownloader2Helper.generateId(book.apiName, book.author, book.name),
                apiName = book.apiName, author = book.author, name = book.name, posterUrl = book.poster,
                modelId = viewModel.llmModel, promptVersion = viewModel.llmPromptVersion,
                systemPrompt = viewModel.llmSystemPrompt, prevChapters = viewModel.llmPrevChapters,
                rangeStart = start.coerceAtLeast(0), rangeEnd = end.coerceAtLeast(start),
                graphOnly = graphOnly,
            )
            LlmFixManager.enqueue(ctx, req)
            Toast.makeText(ctx, R.string.tts_pregen_started, Toast.LENGTH_SHORT).show()
            renderRecords(ctx, b)
        }

        val onRec: (Pair<String, LlmFixManager.LlmFixRecord>) -> Unit = { b.root.post { renderRecords(ctx, b) } }
        val onRem: (String) -> Unit = { b.root.post { renderRecords(ctx, b) } }
        val onProg: (Pair<String, com.lagradost.quicknovel.DownloadProgressState>) -> Unit = { b.root.post { renderRecords(ctx, b) } }
        LlmFixManager.reconcileFromDisk(ctx)
        LlmFixManager.recordChanged += onRec
        LlmFixManager.removed += onRem
        LlmFixManager.progressChanged += onProg
        dialog.setOnDismissListener {
            LlmFixManager.recordChanged -= onRec
            LlmFixManager.removed -= onRem
            LlmFixManager.progressChanged -= onProg
        }
        renderRecords(ctx, b)
        dialog.show()
    }

    private fun loadBooks(ctx: Context): List<BookRow> {
        val keys = getKeys(DOWNLOAD_FOLDER) ?: return emptyList()
        return keys.mapNotNull { key ->
            val d = getKey<DownloadFragment.DownloadData>(key) ?: return@mapNotNull null
            if (d.apiName == BookDownloader2Helper.IMPORT_SOURCE || d.apiName == BookDownloader2Helper.IMPORT_SOURCE_PDF) return@mapNotNull null
            val total = BookDownloader2Helper.downloadInfo(ctx, d.author, d.name, d.apiName)?.total?.toInt() ?: 0
            if (total <= 0) return@mapNotNull null
            BookRow(d.name, d.author, d.apiName, d.posterUrl, total)
        }.sortedBy { it.name }
    }

    private fun renderRecords(ctx: Context, b: DialogLlmBackgroundBinding) {
        val recs = LlmFixManager.records()
        b.llmBgEmpty.visibility = if (recs.isEmpty()) View.VISIBLE else View.GONE
        b.llmBgRecords.removeAllViews()
        for (rec in recs) b.llmBgRecords.addView(row(ctx, b, rec))
    }

    private fun row(ctx: Context, b: DialogLlmBackgroundBinding, rec: LlmFixManager.LlmFixRecord): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(ctx, 10), 0, dp(ctx, 10)) }
        val tc = themeColor(ctx, R.attr.textColor)
        root.addView(TextView(ctx).apply { text = rec.name; textSize = 15f; setTextColor(tc) })

        val live = LlmFixManager.progress[rec.key]
        val state = live?.state
        val done = live?.progress?.toInt() ?: rec.fixedChapters
        val total = (live?.total?.toInt() ?: rec.totalChapters).coerceAtLeast(1)
        val statusTxt = when (state) {
            com.lagradost.quicknovel.DownloadState.IsDownloading -> "rewriting  $done / $total"
            com.lagradost.quicknovel.DownloadState.IsPaused -> "paused  $done / $total"
            com.lagradost.quicknovel.DownloadState.IsPending -> "queued"
            com.lagradost.quicknovel.DownloadState.IsFailed -> "failed"
            com.lagradost.quicknovel.DownloadState.IsStopped -> "stopped  $done / $total"
            else -> "done  ${rec.fixedChapters}/${rec.totalChapters}"
        }
        root.addView(TextView(ctx).apply {
            text = "${LlmModels.byId(rec.modelId).id} · $statusTxt · ${rec.bytes / 1024} KB"
            textSize = 12f; alpha = 0.7f; setTextColor(tc)
        })
        // progress bar for active jobs
        if (state == com.lagradost.quicknovel.DownloadState.IsDownloading || state == com.lagradost.quicknovel.DownloadState.IsPaused) {
            root.addView(android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = total; progress = done; isIndeterminate = false
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(ctx, 4); layoutParams = lp
            })
        }

        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        when (state) {
            com.lagradost.quicknovel.DownloadState.IsDownloading -> {
                btns.addView(textBtn(ctx, R.string.llm_job_pause) { LlmFixManager.addPendingAction(rec.key, com.lagradost.quicknovel.DownloadActionType.Pause); b.root.post { renderRecords(ctx, b) } })
                btns.addView(textBtn(ctx, R.string.llm_job_cancel) { LlmFixManager.addPendingAction(rec.key, com.lagradost.quicknovel.DownloadActionType.Stop); b.root.post { renderRecords(ctx, b) } })
            }
            com.lagradost.quicknovel.DownloadState.IsPaused -> {
                btns.addView(textBtn(ctx, R.string.llm_job_resume) { LlmFixManager.addPendingAction(rec.key, com.lagradost.quicknovel.DownloadActionType.Resume); b.root.post { renderRecords(ctx, b) } })
                btns.addView(textBtn(ctx, R.string.llm_job_cancel) { LlmFixManager.addPendingAction(rec.key, com.lagradost.quicknovel.DownloadActionType.Stop); b.root.post { renderRecords(ctx, b) } })
            }
            com.lagradost.quicknovel.DownloadState.IsPending -> {
                btns.addView(textBtn(ctx, R.string.llm_job_cancel) { LlmFixManager.addPendingAction(rec.key, com.lagradost.quicknovel.DownloadActionType.Stop); b.root.post { renderRecords(ctx, b) } })
            }
            else -> {
                btns.addView(textBtn(ctx, R.string.llm_graph_title) { CharacterGraphDialog.show(ctx, "b${rec.bookId}") })
                btns.addView(textBtn(ctx, R.string.tts_pregen_delete) { LlmFixManager.deleteAll(ctx, rec.key); renderRecords(ctx, b) })
            }
        }
        root.addView(btns)
        return root
    }

    private fun textBtn(ctx: Context, textRes: Int, onClick: () -> Unit): Button =
        Button(ctx).apply {
            setText(textRes); isAllCaps = false; textSize = 12f; minWidth = 0; minimumWidth = 0
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4)); setBackgroundColor(0)
            setTextColor(themeColor(ctx, R.attr.colorPrimary)); setOnClickListener { onClick() }
        }

    private fun themeColor(ctx: Context, attr: Int): Int {
        val tv = android.util.TypedValue(); ctx.theme.resolveAttribute(attr, tv, true); return tv.data
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
