package com.lagradost.quicknovel.ui.llm

import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ReadActivity2
import com.lagradost.quicknovel.ReadActivityViewModel
import com.lagradost.quicknovel.databinding.LlmFixPanelBinding
import com.lagradost.quicknovel.llm.LlmModelDownloadManager
import com.lagradost.quicknovel.llm.LlmModels
import com.lagradost.quicknovel.llm.ProseFixPrompt
import com.lagradost.quicknovel.tts.ModelDownloadState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The "Fix / rewrite with AI" panel, opened from the reader's top-right ✨ button. Lets the user edit
 * the system prompt, choose how many previous chapters to feed as context, download the model, rewrite
 * the current chapter on the spot (with progress), and toggle between the fixed and original text.
 */
object LlmFixDialog {
    fun show(activity: ReadActivity2, viewModel: ReadActivityViewModel) {
        val b = LlmFixPanelBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(b.root)

        val def = LlmModels.byId(viewModel.llmModel)
        b.llmModelName.text = "${def.displayName} · ${def.approxSizeMb} MB   (${activity.getString(R.string.llm_device_ram, LlmModels.totalRamGb(activity))})"

        // ---- system prompt (blank stored = built-in default) ----
        b.llmSystemPrompt.setText(viewModel.llmSystemPrompt.ifBlank { ProseFixPrompt.DEFAULT_SYSTEM_PROMPT })
        b.llmPromptReset.setOnClickListener { b.llmSystemPrompt.setText(ProseFixPrompt.DEFAULT_SYSTEM_PROMPT) }

        // ---- previous-chapters slider ----
        fun prevLabel(n: Int) { b.llmPrevLabel.text = "${activity.getString(R.string.llm_prev_chapters)}  $n" }
        b.llmPrevSlider.value = viewModel.llmPrevChapters.coerceIn(0, 3).toFloat()
        prevLabel(viewModel.llmPrevChapters.coerceIn(0, 3))
        b.llmPrevSlider.addOnChangeListener { _, v, _ -> prevLabel(v.toInt()) }

        // ---- model download state ----
        fun refreshModel() {
            b.llmModelDownload.visibility = if (LlmModels.isReady(activity, def)) View.GONE else View.VISIBLE
        }
        refreshModel()
        b.llmModelDownload.setOnClickListener {
            it.isEnabled = false
            viewModel.downloadLlmModel(activity, def.id)
        }
        LlmModelDownloadManager.refreshFromDisk(activity)
        activity.lifecycleScope.launch {
            LlmModelDownloadManager.states.collectLatest { states ->
                when (val s = states[def.id]) {
                    is ModelDownloadState.Downloading -> {
                        b.llmModelDownload.visibility = View.GONE
                        b.llmModelProgress.visibility = View.VISIBLE
                        b.llmModelProgress.setProgressCompat((s.progress * 100).toInt(), true)
                    }
                    is ModelDownloadState.Ready -> {
                        b.llmModelProgress.visibility = View.GONE; refreshModel()
                    }
                    is ModelDownloadState.Error -> {
                        b.llmModelProgress.visibility = View.GONE
                        b.llmModelDownload.isEnabled = true; b.llmModelDownload.visibility = View.VISIBLE
                    }
                    else -> {}
                }
            }
        }

        // ---- fixed / original toggle (only if a fix exists for the current chapter) ----
        val idx = viewModel.currentIndex
        if (idx != Int.MIN_VALUE && viewModel.hasFixedChapter(activity, idx)) {
            b.llmToggleRow.visibility = View.VISIBLE
            b.llmToggleFixed.setOnClickListener { viewModel.llmShowFixed = true; dialog.dismiss() }
            b.llmToggleOriginal.setOnClickListener { viewModel.llmShowFixed = false; dialog.dismiss() }
        }

        // ---- persist prompt + prev-count, bumping promptVersion when the prompt actually changed ----
        fun persistSettings() {
            val edited = b.llmSystemPrompt.text.toString()
            val effective = if (edited.trim() == ProseFixPrompt.DEFAULT_SYSTEM_PROMPT.trim()) "" else edited
            if (effective != viewModel.llmSystemPrompt) {
                viewModel.llmSystemPrompt = effective
                viewModel.llmPromptVersion = viewModel.llmPromptVersion + 1 // invalidates cache cleanly
            }
            viewModel.llmPrevChapters = b.llmPrevSlider.value.toInt()
        }

        // ---- fix this chapter (foreground, minutes on mid-range) ----
        b.llmFixChapter.setOnClickListener {
            if (!LlmModels.isReady(activity, def)) {
                Toast.makeText(activity, R.string.llm_model_not_ready, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            persistSettings()
            b.llmFixChapter.isEnabled = false
            b.llmFixWhole.isEnabled = false
            b.llmStatus.visibility = View.VISIBLE
            viewModel.fixCurrentChapter(
                activity,
                onState = { s -> activity.runOnUiThread { b.llmStatus.text = s } },
                onDone = { ok ->
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            if (ok) R.string.llm_fixed_done else R.string.llm_fix_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) dialog.dismiss()
                        else {
                            b.llmFixChapter.isEnabled = true; b.llmFixWhole.isEnabled = true
                            b.llmStatus.visibility = View.GONE
                        }
                    }
                },
            )
        }

        // ---- fix whole novel in background ----
        b.llmFixWhole.setOnClickListener {
            if (!LlmModels.isReady(activity, def)) {
                Toast.makeText(activity, R.string.llm_model_not_ready, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            persistSettings()
            LlmBackgroundDialog.show(activity, viewModel)
            dialog.dismiss()
        }

        // ---- build character map (first N chapters, extract only) ----
        b.llmBuildGraph.setOnClickListener {
            if (!LlmModels.isReady(activity, def)) {
                Toast.makeText(activity, R.string.llm_model_not_ready, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            persistSettings()
            LlmBackgroundDialog.show(activity, viewModel, graphOnly = true)
            dialog.dismiss()
        }

        dialog.setOnDismissListener { persistSettings() }
        dialog.show()
    }
}
