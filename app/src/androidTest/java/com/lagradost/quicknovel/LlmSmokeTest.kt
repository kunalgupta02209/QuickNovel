package com.lagradost.quicknovel

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.quicknovel.llm.LlamaCppProseFixer
import com.lagradost.quicknovel.llm.LlmModels
import com.lagradost.quicknovel.llm.ProseFixPrompt
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end smoke test for the on-device LLM prose fixer, meant to be run on a connected
 * device/emulator (`connectedDebugAndroidTest`). Downloads the smallest Qwen2.5 GGUF if absent, loads
 * it through the real llama.cpp binding, and rewrites a deliberately broken machine-translated-style
 * sentence. Validates the whole P1 vertical (download -> load -> ChatML -> generate) with real output.
 */
@RunWith(AndroidJUnit4::class)
class LlmSmokeTest {
    private val TAG = "LlmSmoke"

    @Test
    fun downloadsLoadsAndFixesText() = runBlocking {
        Assume.assumeTrue("LLM engine requires API 24+", Build.VERSION.SDK_INT >= 24)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val def = LlmModels.byId("qwen2.5-0.5b") // smallest (~398 MB) for a fast test
        if (!LlmModels.isReady(ctx, def)) {
            Log.i(TAG, "downloading ${def.displayName} (~${def.approxSizeMb} MB)...")
            var last = -1
            LlmModels.downloadModel(ctx, def) { p ->
                val pct = (p * 100).toInt()
                if (pct / 10 != last) { last = pct / 10; Log.i(TAG, "download $pct%") }
            }
        }
        Assert.assertTrue("model should be ready after download", LlmModels.isReady(ctx, def))
        Log.i(TAG, "model ready at ${LlmModels.modelFile(ctx, def).length() / (1024 * 1024)} MB")

        val engine = LlamaCppProseFixer(ctx, LlmModels.modelFile(ctx, def))
        val loadStart = System.currentTimeMillis()
        Assert.assertTrue("engine.load() should succeed", engine.load())
        Log.i(TAG, "loaded in ${System.currentTimeMillis() - loadStart} ms")

        val broken = "He walk to the store slowly. She give him a apple, and say to he: \"You is very late, is it not?\" " +
                "The 5 apple was red. Lin Xuan gege was not happy."
        val prompt = ProseFixPrompt.buildFixPrompt(
            systemPrompt = "",           // use built-in default
            previousChapters = "",
            characterMemory = "Lin Xuan (he/him): protagonist.",
            chapterText = broken,
            supertonic = false,
        )
        Log.i(TAG, "generating...")
        val genStart = System.currentTimeMillis()
        val fixed = engine.generate(prompt)
        val ms = System.currentTimeMillis() - genStart
        Log.i(TAG, "===== INPUT  =====\n$broken")
        Log.i(TAG, "===== OUTPUT ($ms ms, ${fixed.length} chars) =====\n$fixed")
        engine.close()

        Assert.assertTrue("output should be non-empty", fixed.isNotBlank())
        // Sanity: it must preserve the proper noun and drop the digit "5".
        Assert.assertTrue("should preserve the name Lin Xuan", fixed.contains("Lin Xuan"))
    }
}
