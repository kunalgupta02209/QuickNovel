package com.lagradost.quicknovel.ui.llm

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.llm.RemoteFixClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists the fix server's running jobs (GET /jobs) with cancel — the "see running jobs" surface. */
object ServerJobsDialog {
    fun show(context: Context, baseUrl: String) {
        if (baseUrl.isBlank()) return
        val owner = context as? LifecycleOwner ?: return
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 20))
            setBackgroundColor(themeColor(context, R.attr.primaryGrayBackground))
        }
        root.addView(TextView(context).apply {
            setText(R.string.llm_server_jobs); textSize = 20f; setTextColor(themeColor(context, R.attr.textColor))
        })
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val refresh = Button(context).apply { setText(R.string.llm_refresh); isAllCaps = false }

        fun render() {
            owner.lifecycleScope.launch {
                val jobs = withContext(Dispatchers.IO) { RemoteFixClient.listJobs(baseUrl) }
                container.removeAllViews()
                if (jobs.isEmpty()) {
                    container.addView(TextView(context).apply {
                        setText(R.string.tts_pregen_empty); alpha = 0.6f; setTextColor(themeColor(context, R.attr.textColor))
                    })
                }
                for (j in jobs) {
                    val rowRoot = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(context, 8), 0, dp(context, 8))
                    }
                    rowRoot.addView(TextView(context).apply {
                        text = "${j.model} · ${j.status} · ${j.progress}/${j.total}"
                        setTextColor(themeColor(context, R.attr.textColor)); textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (j.status == "running" || j.status == "queued") {
                        rowRoot.addView(Button(context).apply {
                            setText(R.string.llm_job_cancel); isAllCaps = false; textSize = 12f
                            setBackgroundColor(0); setTextColor(themeColor(context, R.attr.colorPrimary))
                            setOnClickListener {
                                owner.lifecycleScope.launch {
                                    withContext(Dispatchers.IO) { RemoteFixClient.cancelJob(baseUrl, j.id) }
                                    render()
                                }
                            }
                        })
                    }
                    container.addView(rowRoot)
                }
            }
        }
        refresh.setOnClickListener { render() }
        root.addView(refresh)
        root.addView(container)
        render()
        dialog.setContentView(root)
        dialog.show()
    }

    private fun themeColor(ctx: Context, attr: Int): Int {
        val tv = android.util.TypedValue(); ctx.theme.resolveAttribute(attr, tv, true); return tv.data
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
