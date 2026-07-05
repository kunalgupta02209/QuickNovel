package com.lagradost.quicknovel.ui.llm

import android.app.Dialog
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.quicknovel.DataStore
import com.lagradost.quicknovel.llm.CharacterGraph

/**
 * Obsidian-style character graph, rendered by Cytoscape.js (fcose layout) in a full-screen WebView
 * from bundled offline assets. Tapping a node shows its aliases / traits / relationships in an overlay
 * (handled entirely in graph.html, so no JS bridge is needed).
 */
object CharacterGraphDialog {
    fun show(context: Context, bookId: String) {
        val json = CharacterGraph.toCytoscapeJson(bookId)
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFF0E0F13.toInt())
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // json is already a JSON string; wrap it as a JS string literal for renderGraph().
                    view.evaluateJavascript("renderGraph(${DataStore.mapper.writeValueAsString(json)})", null)
                }
            }
            loadUrl("file:///android_asset/graph.html")
        }
        Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(web)
            show()
        }
    }
}
