package com.lagradost.quicknovel.ui.settings

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.LLM_FIX_SERVER_MODEL
import com.lagradost.quicknovel.LLM_FIX_SERVER_URL
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.DialogLlmServerSettingsBinding
import com.lagradost.quicknovel.llm.RemoteFixClient
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.runOnMainThread

/**
 * Settings screen to configure + test the optional GPU fix server (see server/). Reads/writes the
 * same DataStore keys the reader's fix panel uses ([LLM_FIX_SERVER_URL] / [LLM_FIX_SERVER_MODEL]),
 * so a URL + model chosen here applies everywhere. "Test connection" lists the server's models;
 * "Run test rewrite" round-trips a sample sentence so you can confirm the model actually responds.
 */
object LlmServerSettingsDialog {
    private const val SAMPLE = "He walk to the store yesterday. She give him a apple, and he are very happy."

    fun show(activity: Activity) {
        val b = DialogLlmServerSettingsBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(b.root)

        b.serverUrl.setText(getKey<String>(LLM_FIX_SERVER_URL) ?: "")
        b.telemetrySwitch.isChecked = getKey<Boolean>(com.lagradost.quicknovel.TELEMETRY_ENABLED) != false
        b.telemetrySwitch.setOnCheckedChangeListener { _, on ->
            setKey(com.lagradost.quicknovel.TELEMETRY_ENABLED, on)
        }
        var models: List<RemoteFixClient.ServerModel> = emptyList()

        fun url() = b.serverUrl.text.toString().trim()

        fun populateModels(ms: List<RemoteFixClient.ServerModel>) {
            models = ms
            if (ms.isEmpty()) {
                b.status.text = activity.getString(R.string.llm_server_unreachable)
                b.modelSpinner.visibility = View.GONE
                return
            }
            b.status.text = activity.getString(R.string.llm_server_connected, ms.size)
            b.modelSpinner.visibility = View.VISIBLE
            b.modelSpinner.adapter = ArrayAdapter(
                activity, android.R.layout.simple_spinner_dropdown_item, ms.map { it.name }
            )
            val saved = getKey<String>(LLM_FIX_SERVER_MODEL) ?: ""
            b.modelSpinner.setSelection(ms.indexOfFirst { it.id == saved }.coerceAtLeast(0))
            b.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    setKey(LLM_FIX_SERVER_MODEL, models[pos].id)
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        b.testConnection.setOnClickListener {
            val u = url(); setKey(LLM_FIX_SERVER_URL, u)
            if (u.isBlank()) { b.status.text = activity.getString(R.string.llm_server_enter_url); return@setOnClickListener }
            b.status.text = activity.getString(R.string.llm_server_connecting)
            activity.ioSafe {
                val ms = RemoteFixClient.listModels(u)
                runOnMainThread { populateModels(ms) }
            }
        }

        b.testFix.setOnClickListener {
            val u = url(); setKey(LLM_FIX_SERVER_URL, u)
            if (u.isBlank()) { b.testResult.text = activity.getString(R.string.llm_server_enter_url); return@setOnClickListener }
            val model = getKey<String>(LLM_FIX_SERVER_MODEL) ?: ""
            b.testResult.text = activity.getString(R.string.llm_test_running)
            activity.ioSafe {
                val fixed = RemoteFixClient.fixSnippet(u, SAMPLE, model, "", "")
                runOnMainThread {
                    b.testResult.text = if (fixed.isNullOrBlank()) {
                        activity.getString(R.string.llm_test_no_response)
                    } else {
                        activity.getString(R.string.llm_test_result, SAMPLE, fixed)
                    }
                }
            }
        }

        b.saveClose.setOnClickListener {
            setKey(LLM_FIX_SERVER_URL, url())
            dialog.dismiss()
        }

        // Auto-probe if a URL is already saved, so the screen opens already showing connection state.
        if (url().isNotBlank()) b.testConnection.performClick()
        dialog.show()
    }
}
