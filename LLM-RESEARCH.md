# On-device LLM novel-fixer — research synthesis

## recommendedStack

DECISION: llama.cpp (GGUF) as the single inference stack, integrated the SAME way sherpa-onnx already is — a vendored native artifact under app/libs consumed via the flatDir already declared in settings.gradle.kts. Build llama.cpp once with the Android NDK (from upstream examples/llama.android, pinned to a specific tag) into a `llama-android.aar` (arm64-v8a + armeabi-v7a + x86_64, the exact three ABIs QuickNovel already splits on) and drop it next to sherpa-onnx.aar. Consume with `implementation(name: 'llama-android', ext: 'aar')`.

Why this over the alternatives (all evaluated): LiteRT-LM is Gemma-only + needs a Python .litertlm/.task conversion + effectively minSdk 24, and Gemma is measurably weaker than Qwen2.5 at CJK-origin text — the whole point of this feature. MediaPipe tasks-genai is deprecated. MLC-LLM needs a per-model TVM compile step (cannot be a plain dependency, breaks runtime model choice). ONNX-Runtime-GenAI has no official Android Maven artifact and NNAPI is unreliable on Exynos. ExecuTorch adds fbjni/soloader + a .pte pipeline with no upside over GGUF. GGUF/llama.cpp is the only path that (a) runs CPU-only reliably on the Exynos 9611 hard-requirement device, (b) unlocks Qwen2.5 (the CJK winner), (c) works from minSdk 21, (d) matches the existing ABI splits, and (e) supports GBNF grammar for the character-extraction JSON.

Vendoring (not the io.github.ljcamargo:llamacpp-kotlin:0.4.0 Maven artifact) is the tech-lead call for a FOSS app: it removes single-maintainer supply risk, is MIT-clean, gives us a pinned llama.cpp version, and reuses the vendoring muscle the team already has. The Maven artifact is fine ONLY as a throwaway P1 spike to de-risk the API before we commit to the build. The one real conflict with sherpa-onnx (both ship libc++_shared.so) is resolved by a single new packaging rule.

The engine is wrapped behind a new `ProseFixerEngine` interface that mirrors the existing `TtsEngine` seam (register/load/predict/close), so the reader/WorkManager layers never touch JNI directly — identical to how OnDeviceTtsEngine hides sherpa.

## recommendedModel

PRIMARY (default, ships as the recommended first download): Qwen2.5-1.5B-Instruct, Q4_K_M GGUF, ~1.12 GB on disk (int4 weights). Source: https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF (file qwen2.5-1.5b-instruct-q4_k_m.gguf). Format: GGUF, Apache-2.0. This is the default BECAUSE the hard requirement is mid-range feasibility: it is the largest model that runs on the Exynos 9611 (Samsung F41) at a usable background speed (~3-4 tok/s CPU, ngl=0) while carrying Qwen2.5's 18T-token multilingual corpus with official Chinese/Japanese/Korean support — the single most important property for repairing CJK-origin machine translation (gender-ambiguous 他/她, kinship honorifics like gege/shixiong/-nim, transliterated cultivation terms).

FLAGSHIP HIGH-QUALITY TIER (opt-in for >=6 GB RAM / Snapdragon 8 Gen 2+): Qwen2.5-3B-Instruct Q4_K_M, ~1.9-2.1 GB. Source: https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF. Same corpus/license, better multi-clause rewriting; ~12-15 tok/s CPU on 8 Gen 2, ~2 tok/s on Exynos (so it is gated OFF on mid-range).

Model selection is a runtime heuristic on ActivityManager.MemoryInfo.totalMem with a manual override in Settings: >=6 GB -> offer 3B; 3-6 GB -> default 1.5B; <3 GB free -> force the fallback below. All three are the same Qwen2.5 family so prompt/behaviour is identical across tiers — only quality/speed scale.

## fallbackModel

Qwen2.5-0.5B-Instruct, Q4_K_M GGUF, ~398 MB on disk. Source: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF (qwen2.5-0.5b-instruct-q4_k_m.gguf), GGUF/Apache-2.0. This is the low-RAM safety net for devices reporting <3 GB free (older/cheaper Exynos/Snapdragon-6xx, 3-4 GB total). It keeps Qwen2.5's CJK training so it still fixes pronoun-gender and honorifics better than any Llama/SmolLM at this size, runs at ~8-10 tok/s CPU on an Exynos 9611 (a 3000-word chapter in ~10-15 min background), and fits in ~700 MB RAM leaving headroom on a 4 GB phone. Quality is "smoother, not rewritten" — acceptable as best-effort. DISQUALIFIED as fallbacks (do not offer): DeepSeek-R1-Distill-Qwen-1.5B (emits <think> chain-of-thought that poisons TTS output) and any English-only model (Llama 3.2 1B, SmolLM2) which cannot fix CJK honorific/gender artifacts.

## ttsCueFormat

The LLM emits PLAIN PROSE that survives QuickNovel's existing TTSHelper.ttsParseText() strip pass byte-for-byte, plus an OPTIONAL Supertonic-only expression layer. Exact spec the model must emit:

UNIVERSAL LAYER (all four models — Kokoro, Kitten, ZipVoice, Supertonic):
1. Plain UTF-8 text only. No Markdown, HTML, headings, lists, asterisks, code fences, footnotes, or translator-note lines.
2. Every number, ordinal, date, currency amount, and abbreviation written out in WORDS ("3rd"->"third", "5,000 spirit stones"->"five thousand spirit stones", "Dr."->"Doctor", "vol. 2"->"volume two"). ttsParseText does NOT expand digits, so the LLM is the only place this can happen.
3. NO em dash (—) or en dash (–) — ttsParseText replaces them with a space and audibly breaks the sentence. Use commas, periods, or "and"/"but".
4. NO "..." for pauses (replaced with a space). End the sentence with a period, or use a comma.
5. Sentence enders limited to . ? ! ; : — these are the pause/phrasing signals the neural front-ends respond to (prosody-by-punctuation is the only lever these models expose; sherpa-onnx has no SSML — Discussion #519).
6. Dialogue attributed in standard English tag order: "Come with me," she said. (comma keeps the tag in one spoken line).
7. Paragraphs of 3-5 sentences, separated by a single blank line (newline = the reader's longest pause; user's gapMs slider widens it further).
8. Sentences generally under ~25 words so they stay inside Kokoro/Kitten's natural phoneme-chunk window.

SUPERTONIC EXPRESSION LAYER (emitted ONLY when the active TTS modelId == "supertonic"; the app appends a one-line instruction to the system prompt in that case): the model MAY place exactly one of <laugh>, <breath>, or <sigh> on its own line between sentences, ONLY where the prose explicitly describes that action. No other angle-bracket tags (the other 7 Supertonic tags are undocumented/inaudible). Example: `He looked at the empty seat.\n<sigh>\n"She's gone," he whispered.`

PIPELINE PLUMBING (required, one code change): ttsParseText() currently lists '<' and '>' in invalidChars, so it would DELETE the Supertonic tags before synthesis. Fix: add preserveSupertonicTags(text, modelId) that, for Supertonic only, escapes <laugh>/<breath>/<sigh> to a private sentinel before the strip pass and restores them after; for every other model, strip any stray angle-bracket tag so a leaked tag is never spoken literally. Hook it in TtsChapterSynthesizer.synthChapter and OnDeviceTtsEngine.render right where speakOutMsg is built. Zero AAR changes, zero runtime cost.

## systemPromptDraft

The following is stored as an editable default in Settings (a multiline preference), with {previous_chapters}, {character_memory}, and {chapter_text} filled at inference time. For a Supertonic voice the app appends the single expression-tag line noted at the end.

---
You are a literary editor who repairs machine-translated web-novel chapters so they read like fluent, natural English written by a native author, and so they sound good when read aloud by a text-to-speech voice. The chapter below was originally written in Chinese, Korean, or Japanese and machine-translated, so expect wrong-gender pronouns, literal honorific calques, stiff or inverted word order, missing articles, tense drift, and awkward rhythm.

YOUR TASK: Rewrite the CHAPTER TO REWRITE below into clean, natural English. Fix grammar, pronoun gender, tense consistency, articles, prepositions, and unnatural phrasing, and smooth the sentence rhythm for spoken delivery. Do NOT summarize, shorten, expand, reorder, or invent any event, line of dialogue, or description. Preserve the author's meaning and every plot beat exactly.

HARD RULES:
1. Preserve VERBATIM every proper noun: character names, place names, sect / clan / guild / kingdom names, cultivation or power ranks, and technique / skill / item names. Never translate or localize a proper noun. If a name is spelled inconsistently in the source, use the spelling given in CHARACTER MEMORY.
2. Use CHARACTER MEMORY to keep continuity: apply each character's correct pronouns, do not swap a "she" to "he" or vice-versa against the memory, and keep relationships consistent with it.
3. Honorifics: convert kinship or status honorifics into natural English. Render "gege / oppa / onii-san" as the relationship or the person's name; render "-nim / -ssi / -sama / -sshixiong" as a respectful English form or drop it. Keep an honorific only when the scene's meaning depends on it.
4. Output PLAIN PROSE ONLY. No Markdown, HTML, headings, bullet or numbered lists, asterisks, tables, code fences, translator notes, "TL:" lines, or author's-note blocks. Remove anything that is not the story itself.
5. Write out every number, ordinal, date, currency amount, and abbreviation in words (for example "3rd" becomes "third", "5,000 spirit stones" becomes "five thousand spirit stones", "Dr." becomes "Doctor"), so the voice never mis-reads them.
6. Do not use em dashes or en dashes; use commas, periods, or "and" / "but". Do not use "..." for pauses; end the sentence or use a comma. Attribute dialogue in standard English order: "Come with me," she said.
7. Keep paragraphs short, three to five sentences each, separated by a single blank line. Keep most sentences under about twenty-five words.
8. Output ONLY the rewritten chapter. No preamble, title, explanation, or closing remark.

RECENT STORY SO FAR (context only — do NOT copy any of this into your output):
{previous_chapters}

CHARACTER MEMORY (context only — names, pronouns, relationships to keep consistent; do NOT copy into your output):
{character_memory}

CHAPTER TO REWRITE:
{chapter_text}
---

Supertonic-only line the app appends after rule 8 when the selected voice is Supertonic: "You may place exactly one of <laugh>, <breath>, or <sigh> on its own line between sentences, and only where the text explicitly describes that action. Use no other angle-bracket tags."

## memoryGraphDesign

Per-novel character graph, built by the SAME llama.cpp model, wired through the SAME WorkManager chain as the prose fixer.

EXTRACTION (LLM): after a chapter's fixed text is produced, run a second constrained pass on that fixed text with a llama.cpp GBNF grammar that forces a JSON array of objects {name, aliases[], gender, pronouns, traits[], relationships[{target, type}], appearedThisChapter}. GBNF guarantees syntactically valid JSON at sampling time, so there is zero defensive parsing (llama.cpp grammar sampler; same guarantee LiteRT-LM's OpenApiTool gives, but works on our stack). The prompt is fed the chapter's fixed text plus the list of already-known canonical names for that book so the model reuses them.

MERGE: for each extracted entity, dedup against existing nodes using the fuzzywuzzy dependency ALREADY in build.gradle (me.xdrop:fuzzywuzzy:1.4.0): FuzzySearch.ratio(name, canonicalName) >= 85 OR an alias hit -> merge (union aliases + traits, append the chapter number to appearances, set updatedAtChapter/lastSeenChapter); otherwise create a new node. charId = sha1(canonicalName.trim().lowercase()).take(16) — same SHA-1-truncate idiom TtsAudioCache uses.

STORAGE (KV DataStore, no new deps): add const val CHAR_GRAPH_FOLDER = "char_graph" in DataStore.kt (mirrors TTS_PREGEN_FOLDER). Key = "char_graph/<bookId>/<charId>" -> Jackson-serialized CharacterNode data class (id, canonicalName, aliases, gender, pronouns, traits, relationships:List<CharRel>, chapterAppearances, updatedAtChapter) — exactly the TtsPregenRecord persistence shape. getKeys(CHAR_GRAPH_FOLDER) filtered by the bookId segment lists a book's cast; a novel of ~100 characters is ~50-200 KB, trivially within SharedPreferences. Batch all of a chapter's writes then one apply() to avoid repeated fsync (same concern the pregen manager already manages).

CONTEXT INJECTION: on each fix pass, load all nodes for bookId, score by (1) appeared in the current chapter, then (2) chapters-since-last-seen ascending, then (3) total appearances descending; take the top N that fit a ~280-token budget and format each as one line — "NAME (pronouns): trait1, trait2. type of OtherName." — into the {character_memory} placeholder. ~25-35 tokens/entry => 8-10 characters, which fits comfortably in a 2048-token context alongside {previous_chapters}.

VISUALIZATION (no Compose — the app is XML/View-only): one Fragment hosting a WebView that loads app/src/main/assets/graph.html bundling cytoscape.min.js + cytoscape-fcose.min.js (~300 KB, committed for full offline). Kotlin pushes the graph JSON via webView.evaluateJavascript("renderGraph(...)") and receives node taps via addJavascriptInterface(bridge, "Android") -> open a character detail sheet. Serve assets with WebViewAssetLoader (https scheme) so Cytoscape can load its own resources without file:// CORS issues. fcose handles 50-200 nodes with pinch-zoom/drag out of the box.

SCHEDULING: extraction runs as a stage in the same serial "TTS_PREGEN"-style unique-work chain (a ProseFixPregenManager modeled on TtsPregenManager), one chapter at a time with a short inter-chapter pause for thermal recovery; CharacterNode.updatedAtChapter makes re-runs skippable, exactly like the .done markers make TTS pregen resumable. Fully offline, no new permissions.

## perfExpectations

All heavy LLM work is BACKGROUND-ONLY on every tier — never a foreground/blocking call, because even the flagship case is minutes per chapter. The winning UX is the existing pattern: pre-process chapter-by-chapter into a fixed-text cache under WorkManager (constrained to battery-not-low, and requires-charging on mid-range), so reading/TTS is instant on already-fixed chapters and silently falls back to raw text on not-yet-fixed ones.

MID-RANGE (Exynos 9611 / Samsung F41, Snapdragon 6xx, ~4-6 GB RAM, LPDDR4X ~12 GB/s, CPU-only, ngl=0 — Mali G72 Vulkan is slower than CPU for sub-2B):
- 0.5B Q4: ~8-10 tok/s decode, ~700 MB RAM. 3000-word chapter (~4000 out-tokens) ~10-15 min.
- 1.5B Q4 (default): ~3-4 tok/s, ~1.3 GB RAM. Chapter ~20-30 min. Prefill of a ~2000-token input adds ~2-4 min before first token.
- 3B Q4: ~2 tok/s and RAM-tight on 4 GB -> gated OFF.
- Foreground: NONE. Background: yes, charging + not-low. Character-extraction pass adds roughly one more chapter-equivalent.

FLAGSHIP (Snapdragon 8 Gen 2+, 8-12 GB RAM, LPDDR5X ~50-67 GB/s):
- 1.5B Q4: ~15-20 tok/s CPU, up to ~3x with Vulkan (ngl=99) on Adreno. Chapter ~2-4 min.
- 3B Q4 (opt-in): ~12-15 tok/s CPU, ~1.9 GB RAM. Chapter ~4-6 min.
- Foreground: still avoid a blocking spinner; the 2-4 min turnaround makes "fix the next chapter while the user reads the current one" seamless as a background prefetch.

CROSS-TIER GUARDS: cap llama.cpp ctx-size at 2048 (halves KV-cache RAM vs 4096 at negligible quality cost for rewriting); chunk input to <=512 tokens and process paragraph-groups so a partial result can be cached and read while later paragraphs are still fixing; register a ThermalStatusListener (API 30+) and pause at THERMAL_STATUS_SEVERE, resume at NONE/LIGHT; keep each WorkManager unit < 10 min and use setExpedited to dodge the SDK 34 foreground-service timeout; thermal throttling is real (8 Gen 3 GPU observed dropping 680->231 MHz within ~6 iterations), so sustained numbers are the lower ends above, not cold-burst peaks.

## stackIntegration

GRADLE (app/build.gradle, Groovy):
1) dependencies: add `implementation(name: 'llama-android', ext: 'aar')` next to the existing `implementation(name: 'sherpa-onnx', ext: 'aar')` (flatDir in settings.gradle.kts already covers app/libs — no settings change). For a P1 spike only, `implementation 'io.github.ljcamargo:llamacpp-kotlin:0.4.0'` works with the same packaging fix.
2) android { } — add the block (there is none today): `packagingOptions { pickFirst 'lib/*/libc++_shared.so' }`. This is the ONLY real conflict: both sherpa-onnx.aar and llama.cpp ship libc++_shared.so; picking either is safe (both NDK r21+). 
3) ABI splits already include arm64-v8a, armeabi-v7a, x86_64 — the exact ABIs to build llama.cpp for. No change.

NATIVE BUILD (produce llama-android.aar once, check it into app/libs): build upstream llama.cpp/examples/llama.android with the NDK at a pinned tag; CMake ANDROID_STL=c++_shared, ANDROID_PLATFORM=android-21 (CPU NEON path compiles at 21 — matches minSdk), for all three ABIs. armeabi-v7a is a slow-but-valid fallback (no i8mm); the F41 runs the arm64 .so.

API LAYER (mirror the TtsEngine seam):
- ProseFixerEngine (interface load()/fix(text)/extract(text)/close()) with a llama.cpp-backed impl; ALL calls inside withContext(Dispatchers.IO) since llama.cpp is blocking C++ — exactly how TtsChapterSynthesizer isolates sherpa. Set ngl = 0 on mid-range, 99 on 8 Gen 2+ (gate on totalMem + SoC). Use the GBNF grammar sampler for extract().
- LlmModels object mirroring TtsModels: a ModelDef list for the three Qwen2.5 GGUFs (a single .gguf is a non-archive Asset, exactly like ZipVoice's vocos_24khz.onnx `Asset(archive=false)`), downloaded to filesDir with a `.ready` marker. Reuse ModelDownloadManager/ModelDownloadState verbatim (generalize its TtsModels reference to a shared registry, or clone as LlmModelDownloadManager). Download URLs are HuggingFace resolve links, e.g. https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf.

CRITICAL CACHE-COUPLING (must not get wrong): the audio cache key is sha1(speakOutMsg). If the fixer changes the text, BOTH the live OnDeviceTtsEngine path AND the TtsChapterSynthesizer path must feed IDENTICAL fixed text into ttsParseText or they will thrash the audio cache. Therefore do NOT re-run the LLM per path — introduce a FixedTextCache (filesDir/llm-fixed/<bookId>/c<index>/<llmModelId>-<promptVersion>.txt, .ready markers, mirroring TtsAudioCache) that the LLM writes once. Both the reader's LiveChapterData.ttsLines and TtsChapterSynthesizer.synthChapter substitute the fixed body in place of preParseHtml(loaded.html) when a fixed version exists, else use raw. promptVersion is part of the key so editing the system prompt invalidates cleanly.

## implementationPlan

1. P1 — Engine seam + model download + settings (no reader/pipeline change yet). Vendor llama-android.aar into app/libs (built from upstream examples/llama.android), add the `pickFirst 'lib/*/libc++_shared.so'` packagingOptions block, and the aar dependency line. Create ProseFixerEngine (interface mirroring TtsEngine) + a llama.cpp impl running on Dispatchers.IO. Create LlmModels (mirror TtsModels) registering Qwen2.5-0.5B/1.5B/3B GGUFs as non-archive filesDir downloads with .ready markers, reusing ModelDownloadManager/ModelDownloadState. Add a Settings screen: enable toggle, model picker with RAM-based recommendation (ActivityManager.MemoryInfo.totalMem), download/delete, and the editable default system prompt. Ship a hidden debug 'fix this paragraph' screen to validate quality/speed on real devices before touching the reader.

2. P2 — Background chapter fixer + fixed-text cache + reader wiring. Add FixedTextCache (filesDir/llm-fixed/<bookId>/c<index>/<llmModelId>-<promptVersion>.txt with .ready markers, modeled on TtsAudioCache). Add ProseFixPregenManager + ProseFixWorkManager as a clone of TtsPregenManager/TtsPregenWorkManager (serial unique-work chain, static-map payload, setForeground, APPEND_OR_REPLACE, cooperative pause/resume/stop, persisted records in a new LLM_FIX_FOLDER, chunked per paragraph-group, ThermalStatusListener pause, requires-charging on mid-range / battery-not-low always). Wire substitution: both LiveChapterData.ttsLines and TtsChapterSynthesizer.synthChapter use the fixed body in place of preParseHtml(loaded.html) when cached, else raw — so audio-cache SHA-1 keys stay identical across live + pregen. Add a chapter-range picker reusing the existing TtsGenerateDialog pattern.

3. P3 — Character memory graph: extraction + storage + injection. Add CharacterNode/CharRel data classes and const CHAR_GRAPH_FOLDER='char_graph' in DataStore.kt (keys 'char_graph/<bookId>/<charId>', charId=sha1(name.lowercase()).take(16)). Add an extraction stage to the ProseFix WorkManager chain: run the same model with a GBNF grammar forcing the character-JSON schema on each chapter's FIXED text; merge with the existing me.xdrop:fuzzywuzzy:1.4.0 (ratio>=85) + alias match; batch-write per chapter (one apply()); skip via updatedAtChapter. Build the recency+role-scored {character_memory} block (~280-token budget) and inject it plus a rolling {previous_chapters} tail into the fix prompt. Now fixing chapter N is continuity-aware.

4. P4 — Reader/UI polish, TTS cues, graph viz. Add preserveSupertonicTags(text, modelId) in the TTS text path (escape <laugh>/<breath>/<sigh> before ttsParseText's <>-strip for Supertonic, strip stray tags for other models) and append the Supertonic instruction line to the prompt when that voice is active. Add the graph Fragment: a WebView loading assets/graph.html (Cytoscape.js + fcose, ~300 KB committed offline), graph JSON via evaluateJavascript, node taps via addJavascriptInterface -> character detail sheet, served through WebViewAssetLoader. Add per-book enable/disable + a 'reader shows fixed vs raw' toggle, and a management screen (like TTS pregen management) to view/delete fixed-text + graph caches. Final device-tier QA on Exynos 9611 and an 8 Gen 2 device.

## risks

1. kotlinllamacpp Maven artifact is single-maintainer — mitigated by making the VENDORED llama-android.aar the shipping path (Maven only for a P1 spike); fallback is SmolChat-Android's Apache-2.0 JNI module as a :llm subproject.

2. libc++_shared.so duplicated by sherpa-onnx.aar + llama.cpp — mitigated by the one-line `pickFirst 'lib/*/libc++_shared.so'` (both built with NDK r21+, safe).

3. RAM OOM: 3B Q4 is ~2 GB weights + KV-cache on 4 GB phones — mitigated by gating model choice on ActivityManager.MemoryInfo.totalMem (force 0.5B <3 GB, default 1.5B, 3B only >=6 GB) and ctx-size 2048.

4. Cache-key coupling: if live-read and pregen feed different text into ttsParseText, sha1(speakOutMsg) audio keys diverge and cache thrashes — mitigated by a single-source FixedTextCache written once by the LLM and read by BOTH paths; promptVersion in the key invalidates on prompt edits.

5. LLM alters plot / drops lines / hallucinates on small models — mitigated by strict system prompt (no summarize/expand), keeping raw text as fallback, a per-book enable and a reader 'show raw' toggle, and defaulting fixing OFF until the user opts in per book.

6. Model download is 0.4-2.1 GB — mitigated by explicit user consent, WiFi-preferred download, and the existing filesDir on-demand pattern (never bundled in APK).

7. Thermal + battery: sustained background inference heats the device and draws ~3-4 W — mitigated by requires-charging on mid-range, battery-not-low always, ThermalStatusListener pause at SEVERE, and inter-chapter cooldown pauses.

8. SDK 34 foreground-service timeout can kill long jobs — mitigated by paragraph-group chunking (<10 min units) + setExpedited, and on-disk .ready markers so a re-enqueue resumes (same resiliency the TTS pregen worker already relies on).

9. Mid-range speed makes 3B impractical and even 1.5B is 20-30 min/chapter — accepted: the feature is background-only pre-generation, never foreground; set expectations in UI ('preparing chapters').

10. Supertonic tag-stripping bug: ttsParseText deletes < and > today, so tags would vanish — mitigated by preserveSupertonicTags before the strip pass; leaked tags on other models are stripped so they are never spoken literally.

11. GBNF-constrained extraction can still emit semantically wrong merges (same person as two nodes) — mitigated by fuzzywuzzy>=85 + alias union and by feeding known canonical names back into the extraction prompt; graph is user-viewable/editable so mistakes are correctable.


---
# Facet recommendations

## On-device LLM Inference Stack for Android (2025-2026) — QuickNovel prose-fixing feature

**Recommendation:** Use llama.cpp via the kotlinllamacpp Maven artifact (io.github.ljcamargo:llamacpp-kotlin:0.4.0). It ships prebuilt .so files for the three ABIs already declared in build.gradle (arm64-v8a, armeabi-v7a, x86_64), integrates with one Gradle line plus one packagingOptions line to resolve the libc++_shared.so collision with sherpa-onnx.aar, supports streaming token generation via a Kotlin callback, and — critically — can load Qwen2.5-1.5B-Instruct-Q4_K_M.gguf (1.12 GB, downloaded to filesDir on first run like the TTS models). Qwen2.5 1.5B achieves ~167 tok/s on edge benchmarks at Q8_0 and is the best sub-2B model for CJK-origin text reformulation. On Exynos 9611 CPU-only expect ~3-6 tok/s (disable Vulkan with ngl=0 — Mali G72 Vulkan is slower than CPU for sub-2B models); on Snapdragon 8 Gen 2 optionally enable Vulkan (ngl=99) for ~3x uplift. Concrete Gradle changes: (1) add `implementation 'io.github.ljcamargo:llamacpp-kotlin:0.4.0'` to app/build.gradle dependencies; (2) inside the android{} block add `packagingOptions { pickFirst 'lib/*/libc++_shared.so' }` — this resolves the only real conflict with the vendored sherpa-onnx.aar; (3) run inference inside a Dispatchers.IO coroutine (llama.cpp calls are blocking C++) and plug into the existing WorkManager background pre-generation pipeline as a prose-polishing stage before TTSHelper.generateAudio(). For RAM-constrained devices (under 4 GB available) offer Qwen3-0.6B-Q4_K_M (~380 MB) as the fallback; for flagships offer Qwen2.5-3B-Q4_K_M (~2 GB) as the high-quality option.

**Integration:** GRADLE CHANGES (app/build.gradle — Groovy DSL):

  dependencies {
      // Existing sherpa-onnx vendored AAR stays untouched
      implementation(name: 'sherpa-onnx', ext: 'aar')
      // On-device LLM inference via llama.cpp JNI bindings
      implementation 'io.github.ljcamargo:llamacpp-kotlin:0.4.0'
  }

  android {
      packagingOptions {
          // Both sherpa-onnx.aar and llama.cpp ship libc++_shared.so — pick first to avoid duplicate
          pickFirst 'lib/*/libc++_shared.so'
      }
      // Existing ABI splits (arm64-v8a, armeabi-v7a, x86_64) already cover kotlinllamacpp's prebuilt ABIs — no change needed
  }

No changes to settings.gradle.kts are required (kotlinllamacpp is on Maven Central).

MODEL DOWNLOAD: Fetch Qwen/Qwen2.5-1.5B-Instruct-GGUF / qwen2.5-1.5b-instruct-q4_k_m.gguf (1.12 GB) from HuggingFace into context.filesDir at first use — same pattern already used for sherpa-onnx model tarballs. Offer Qwen2.5-0.5B-Q4_K_M (~390 MB) as a low-RAM tier for devices reporting < 3 GB available RAM.

KOTLIN API SKETCH:
  class ProseFixerEngine(private val modelPath: String) {
      private var model: LlamaCppModel? = null

      suspend fun load() = withContext(Dispatchers.IO) {
          model = LlamaCppModel.load(
              path = modelPath,
              ngl = if (isSnapdragon8Gen2OrNewer()) 99 else 0  // CPU-only on Mali
          )
      }

      suspend fun fix(rawParagraph: String): String = withContext(Dispatchers.IO) {
          model!!.predict(
              "Rewrite the following machine-translated web-novel paragraph " +
              "in natural, fluent English prose. Keep all names, places, and " +
              "plot details. Output ONLY the rewritten paragraph.\n\n$rawParagraph"
          )
      }

      fun close() = model?.close()
  }

INTEGRATION POINT: Hook this into the existing background TTS pre-generation WorkManager job (BackgroundTTSWorker) as a stage before TTSHelper.generateAudio(). The prose-fixer runs chapter-paragraph-by-paragraph; 3-6 tok/s on Exynos 9611 processes a ~200-word paragraph in ~30-50 seconds, which is within the background pre-generation budget.

NATIVE LIBRARY GOTCHAS:
- libc++_shared.so: The pickFirst rule is the canonical fix (see Android NDK middleware guidance). Both sherpa-onnx and llama.cpp statically link their own C++ standard library internals; the shared libc++_shared.so is only the STL runtime. Picking either copy is safe as long as both were built with NDK r21+.
- armeabi-v7a: llama.cpp CPU runs on armeabi-v7a (32-bit ARM) but is noticeably slower due to no NEON i8mm. Acceptable as a fallback; the Samsung F41 ships an arm64-v8a kernel so it will pick the arm64 .so.
- Context size: Cap at maxTokens=512 for input + 256 for output per invocation to keep peak RSS under ~300 MB additional beyond the base model load.

ALTERNATIVE IF kotlinllamacpp IS ABANDONED: Copy the JNI module from SmolChat-Android (github.com/shubham0204/SmolChat-Android — Apache 2.0, actively maintained as of 2025) into a :llm module in the project and add `include ':llm'` to settings.gradle.kts. This uses the same GGUF/llama.cpp core and the same packagingOptions fix.

## Best small quantized instruction-tuned models (1-3B params) for cleaning machine-translated CJK web novel prose on Android (2025-2026)

**Recommendation:** PRIMARY: Qwen2.5-3B-Instruct Q4_K_M (2.1 GB). Download from https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF. Use on devices with ≥6 GB RAM or Snapdragon 8 Gen 2+; wire it into the existing WorkManager background pre-generation job so the ~2 tok/s Exynos 9611 speed (and ~12-15 tok/s on Snapdragon 8 Gen 2) is handled asynchronously before TTS rendering. Apache 2.0 is the cleanest possible license for a FOSS app. Its 18-trillion-token multilingual training with official Chinese/Japanese/Korean support is the single most important differentiator for fixing CJK-sourced prose: it understands that Chinese 他/她 are both "ta" (gender disambiguation from context), that "gege/dage/shixiong" are kinship honorifics not proper nouns, and that Korean -nim/-ssi/-oppa endings should be adapted or dropped for English TTS.

SMALLER FALLBACK for Exynos 9611 / ≤4 GB RAM devices: Qwen2.5-1.5B-Instruct Q4_K_M (1.12 GB). Download from https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF. Same CJK training corpus, same Apache 2.0 license, half the weight file. ~4 tok/s on Exynos 9611 makes a 500-token paragraph take ~2 minutes in background — tight but viable if processing runs chapter-by-chapter during download.

Runtime device-selection heuristic: check ActivityManager.MemoryInfo.totalMem at startup; route to 3B if ≥5 GB physical RAM, else 1.5B; expose a manual override in settings.

**Integration:** FRAMEWORK: llama.cpp via Android NDK/JNI is the recommended path for Qwen GGUF models in QuickNovel. The existing pattern of vendoring a native .aar (used for sherpa-onnx) maps directly here. The llama.cpp repository ships examples/llama.android with pre-built Kotlin bindings; clone it as a git submodule and add it to settings.gradle. Alternatively, the community project github.com/kherud/java-llama.cpp publishes JVM/Android JNI bindings. CMake flags: ANDROID_ABI=arm64-v8a, ANDROID_STL=c++_static, ANDROID_PLATFORM=android-21 (CPU-only path compiles from API 21; the official docs show android-28 for GPU/SME2 features but the CPU NEON path works at 21). Build armeabi-v7a as a secondary ABI for full minSdk 21 coverage.

LITERT-LM ALTERNATIVE (Gemma 3 1B only): If you want the simpler Google-managed integration path and are willing to accept the weaker CJK quality, Gemma 3 1B .task file is available at https://huggingface.co/litert-community/Gemma3-1B-IT and on Kaggle at https://www.kaggle.com/models/google/gemma-3/tfLite/gemma3-1b-it-int4. Gradle dependency: implementation 'com.google.ai.edge.litert-lm:litert-lm-android:1.x.x'. WARNING: Google explicitly states this API is 'optimized for high-end devices such as Pixel 8 and Samsung S23 or later' — the Exynos 9611 (Samsung F41) is not in that category and may fail or be extremely slow on the GPU path.

GGUF DOWNLOAD SIZES SUMMARY:
- Qwen2.5-3B-Instruct Q4_K_M: 2.1 GB (https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF)
- Qwen2.5-1.5B-Instruct Q4_K_M: 1.12 GB (https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF)
- Qwen2.5-1.5B-Instruct Q2_K: 753 MB (same repo, extreme-budget fallback)
- Gemma 3 1B QAT Q4_0 GGUF: ~1 GB (https://huggingface.co/google/gemma-3-1b-it-qat-q4_0-gguf)
- Llama 3.2 3B Q4_K_M: 2.02 GB (https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF)
- Llama 3.2 1B Q4_K_M: 808 MB (https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF)

PERFORMANCE ESTIMATES (llama.cpp CPU, decode phase):
- Exynos 9611 (Samsung F41, Mali-G72, LPDDR4X ~30 GB/s): 1B ~6 tok/s, 1.5B ~4 tok/s, 3B ~2 tok/s
- Snapdragon 8 Gen 2 (LPDDR5X ~68 GB/s): 1B ~25 tok/s, 1.5B ~20 tok/s, 3B ~12-15 tok/s
- Snapdragon 8 Gen 3 with Adreno 750 (MLC-LLM GPU): ~30 tok/s for 3B
- LiteRT-LM GPU on Samsung S26 Ultra: ~52 tok/s decode for Gemma 4 E2B class
These are CPU-inference estimates derived from benchmarked Snapdragon 888 data (3.2 tok/s at 3B Q4) scaled by memory bandwidth ratios.

GOTCHAS:
1. Model files cannot be bundled in the APK — use the same on-demand download pattern as the sherpa-onnx model download; store in filesDir or an external storage path the user selects.
2. llama.cpp does not publish a pre-built AAR to Maven Central; you must either build from source as an ExternalNativeBuild module or vendor a pre-built .so + Java/Kotlin wrapper as a local module (same pattern as the vendored sherpa-onnx AAR in app/libs/).
3. For the prose-editing system prompt: instruct the model to preserve all proper nouns, location names, cultivation ranks, and skill names verbatim; fix only grammar, pronoun gender, and awkward phrasing; output plain prose (no markdown) since TTS is the consumer.
4. Chunk by paragraph (not full chapter) to keep context window pressure low and allow incremental streaming to the TTS pipeline.

## TTS-ideal LLM output format and voice cues for sherpa-onnx OfflineTts

**Recommendation:** Use a two-tier output format: (A) a universal plain-prose layer that all four models consume correctly, plus (B) an optional Supertonic-specific `<laugh>/<breath>/<sigh>` pass applied only when the active model is Supertonic. The LLM system prompt must enforce: no markdown or HTML (except the three expression tags for Supertonic), all numbers written out in words, abbreviations expanded, em/en dashes replaced with commas or sentence breaks, `...` replaced with a period or comma, dialogue attributed in standard English tag order ("she said," / "he whispered,"), footnotes and translator notes stripped, and paragraphs of 3–5 sentences separated by a blank line. At synthesis time the app should branch: when modelId == "supertonic", permit the three tags to pass through to `speakOutMsg`; for all other models, strip any angle-bracket tags before the text reaches `engine.generateWithCallback`. This costs zero extra runtime overhead beyond the LLM inference already planned, requires no changes to the sherpa-onnx AAR, and gives meaningful expressiveness on Supertonic while remaining safe on the other three models.

**Integration:** SSML status: sherpa-onnx has no SSML parser (Discussion #519, unresolved); `silence_scale` in OfflineTtsConfig was merged (PR #1820) but reported as non-functional for Kokoro and VITS/Piper (Issue #2043); `length_scale` controls global speed only.

Supertonic tags in-pipeline: in `OnDeviceTtsEngine.render()`, the text arrives as `item.line.speakOutMsg` which has already passed through `TTSHelper.ttsParseText()`. That function strips `<` and `>` characters (the `invalidChars` array includes both). Therefore expression tags MUST be preserved BEFORE ttsParseText runs. The correct hook is: add a `preprocessForEngine(text: String, modelId: String): String` step in `TTSHelper.ttsParseText()` or in the LLM output handler that (a) for Supertonic: preserves `<laugh>`, `<breath>`, `<sigh>` by temporarily escaping them before the strip pass then restoring them, or (b) better: strip `<` and `>` only for non-tag content by checking against a whitelist before the invalidChars replacement. Alternatively, since Supertonic's front-end expects raw angle-bracket tags, the cleanest fix is to move the `<`/`>` strip out of `ttsParseText()` and into `TTSSession.speak()` only (the Android TextToSpeech engine would break on raw angle-brackets, so it needs stripping there; Supertonic does not).

Text normalization pipeline order for LLM output:
1. LLM rewrites + normalizes (numbers, abbreviations, markdown stripped, expression tags optionally added)
2. `preParseHtml()` — strips style/script, handles HTML tables, removes translator credit lines
3. `render()` — Markwon render to Spanned
4. `parseTextToSpans()` — newline split into TextSpan list
5. `ttsParseText()` on each span's text — sentence segmentation + char stripping → TTSLine list
6. Model-specific tag preservation: add a filter AFTER step 1 and BEFORE step 5 that, for non-Supertonic models, strips any residual angle-bracket tags, and for Supertonic, leaves the whitelisted tags intact.

Numbers in existing pipeline: `ttsParseText()` only handles `.([0-9])` → `,` (decimal protection) and `Dr/Mr/Mrs\. ` abbreviations. It does NOT expand "42" to "forty-two". The LLM must do this expansion — it cannot be relied on in the post-processing layer.

Ellipsis: The existing `preParseHtml()` converts `...` to `…` (the Unicode character U+2026). Then `ttsParseText()` does NOT strip `…` — it is in the `invalidStartChars` list (so it's skipped if a sentence starts with it) but is NOT stripped mid-sentence. Neural models (Kokoro, Kitten) handle `…` as a longer pause naturally; however the LLM should still prefer explicit punctuation (period or comma) over ellipsis for clarity.

Dialogue formatting: The existing reader splits on periods, so dialogue attribution lines like `"Come here," he said.` are handled correctly — the comma before `he said` keeps them in one TTSLine. Do NOT let the LLM use em dashes for dialogue interruption (`—` is stripped to a space, breaking the sentence mid-thought audibly).

Expression-tag whitelisting in Kotlin (recommended implementation sketch):
```kotlin
private val SUPERTONIC_TAGS = setOf("laugh", "breath", "sigh")
fun preserveSupersonicTags(text: String): String {
    // Replace <laugh> → laugh before the strip pass, restore after
    ...
}
```

Sources confirmed via web search and code review: sherpa-onnx Discussion #519, Issue #2043, Supertonic v3 GitHub Issues #155, PolyNorm arxiv:2511.03080.

## Character memory graph for narrative consistency

**Recommendation:** Use Qwen2.5-1.5B GGUF + llama.cpp JNI with GBNF grammar for extraction on ALL devices (fits within 1.2GB RAM, covers Exynos 9611 comfortably), plus optionally promote to Gemma 4 E2B + LiteRT-LM at runtime when Runtime.getRuntime().maxMemory() exceeds ~3.5GB. Embed Cytoscape.js (fcose layout) in a WebView Fragment for visualization — no Compose needed, ships as a ~300KB assets/graph.html. Store graph data in the existing DataStore KV as 'char_graph/bookId/charId' keys with Jackson-serialized CharacterNode data classes. For context injection, on each chapter fixing pass: (1) query all CharacterNode keys for the current bookId, (2) score each by recency (chapters since last_seen) and role (POV > named-with-relationship > background), (3) format the top N characters that fit within a 280-token budget as 'NAME (pronouns): trait1, trait2. Relationship to Y.' lines prepended to the fixing prompt, (4) use the existing fuzzywuzzy dependency (already in build.gradle as me.xdrop:fuzzywuzzy:1.4.0) for alias deduplication during the merge step. Wire extraction as a preceding WorkManager task in the same chain as background TTS pre-generation (TtsPregenManager already shows the pattern); mark each chapter's extraction state in the CharacterNode's updatedAtChapter field so re-runs are skippable. The entire pipeline stays fully offline and requires no new permissions beyond what the LLM inference engine already needs.

**Integration:** GRADLE DEPS (app/build.gradle, Groovy format):
- LiteRT-LM (flagship path): implementation('com.google.ai.edge.litertlm:litertlm-android:latest.release') — Google Maven
- llama.cpp JNI (universal path): no single canonical AAR; best option is to compile llama.cpp with the Android NDK and drop the resulting libllama.so under app/src/main/jniLibs/arm64-v8a/ and armeabi-v7a/ (the project already has ABI splits configured in build.gradle). The llama.cpp repo ships a minimal Android demo at examples/llama.android/. Alternatively, check JitPack for a community wrapper.
- GraphView (fallback native option only): implementation('dev.bandb.graphview:graphview:0.8.1')
- Cytoscape.js (preferred visualization): NO Gradle dep — bundle as app/src/main/assets/graph.html with inline JS. Download cytoscape.min.js (~200KB) and cytoscape-fcose.min.js (~50KB) from cdn.jsdelivr.net and commit them; this avoids network dependencies and CDN availability risks.
- fuzzywuzzy already present: me.xdrop:fuzzywuzzy:1.4.0 — use FuzzySearch.ratio(a, b) >= 85 for alias merge candidate detection, then confirm with the LLM or a simple heuristic.

STORAGE DATA CLASSES (place in a new file, e.g. tts/CharacterGraph.kt):
  data class CharacterNode(val id: String, val canonicalName: String, val aliases: List<String>, val gender: String?, val pronouns: String?, val relationships: List<CharRel>, val traits: List<String>, val chapterAppearances: List<Int>, val updatedAtChapter: Int)
  data class CharRel(val targetId: String, val targetName: String, val type: String)
  Key pattern: DataStore key = "char_graph/$bookId/$charId" where charId = SHA-1(canonicalName.trim().lowercase())[:16]
  Add const val CHAR_GRAPH_FOLDER = "char_graph" to DataStore.kt.

LLAMA.CPP JSON GRAMMAR: Define a GBNF that enforces the CharacterNode array schema. Pass as grammar_string to llama_sampler_chain_add(llama_sampler_init_grammar(...)). This forces syntactically valid JSON at every sampling step with zero post-processing fallback needed.

LITETRT-LM JSON SCHEMA: Implement OpenApiTool with getToolDescriptionJsonString() returning your character-array schema. LiteRT-LM's constrained decoding engine rejects tokens that would violate the schema. See: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md

WEBVIEW BRIDGE PATTERN:
  webView.addJavascriptInterface(CharGraphBridge(viewModel), "Android")
  // In JS: Android.onNodeTapped(characterId)
  // In Kotlin: webView.evaluateJavascript("renderGraph(${graphJson})", null)
  Use WebViewAssetLoader to serve assets:// with content:// scheme (avoids file:// CORS issues with Cytoscape loading its own CSS).

CONTEXT INJECTION TOKEN BUDGET:
  Target: 280 tokens for character reference block in a ~2048-token context.
  Format each character as: "- {canonicalName} ({pronouns}): {traits.take(3).joinToString()}. {relationships.take(2).map { "${it.type} of ${it.targetName}" }.joinToString()}."
  Approximate tokens: ~25-35 per character entry. Budget allows ~8-10 character entries.
  Rank by: (1) appeared in the current chapter (detected during extraction pass), (2) chapters_since_last_seen ascending, (3) number of total appearances descending.

NATIVE LIBRARY SIZE BUDGET: Current APK already has sherpa-onnx .aar (~55MB native). llama.cpp arm64-v8a .so is ~6-8MB additional. With per-ABI APK splits already configured, this adds ~6-8MB per ABI split APK, not a universal APK bloat. LiteRT-LM AAR is ~10-15MB additional.

MINSK CONCERN: LiteRT-LM requires Android 7.0+ (API 24) in practice for GPU delegation. The project's minSdk is 21; guard LiteRT-LM inference behind Build.VERSION.SDK_INT >= 24 and fall back to llama.cpp CPU path. llama.cpp works on API 21+.

THERMAL THROTTLING: Samsung Galaxy S24 Ultra (Exynos 2400) degrades ~15% over 20 iterations; Exynos 9611 is worse. For extraction, process one chapter at a time with a 2-3 second inter-chapter pause inside the WorkManager job to allow thermal recovery. This is already the pattern in TtsChapterSynthesizer.

## REALISTIC on-device LLM PERFORMANCE for chapter rewriting on Android (mid-range Exynos 9611 vs flagship Snapdragon 8 Gen 2)

**Recommendation:** Default ALL tiers to background-only pre-processing via WorkManager (constrained to charging + battery-not-low), and cap the max model at 1B int4 for mid-range (Exynos 9611 / Snapdragon 6xx) and 1.5B int4 for flagship (Snapdragon 8 Gen 2+). The core reasoning: a 3000-word chapter produces ~4000 output tokens; at 2–4 tok/s on Exynos 9611 this is 17–33 minutes — never foreground. Even on flagship at 15–35 tok/s sustained (2–5 minutes), showing a spinner and blocking TTS is worse UX than silently pre-fetching. The winning architecture is to process in paragraph-sized chunks of ~500–700 words (~600–900 output tokens per call), writing each result to the TTS audio cache (already present in this codebase as the P1 foundation) as soon as each chunk is done, so TTS playback can begin on paragraph 1 while the LLM is still rewriting paragraph 4. On mid-range, use llama.cpp Q4_K_M (CPU path, broad device support including Exynos 9611 via JNI) with a 1B model (Gemma 3 1B or Qwen2.5 0.5B as a fallback for 4 GB devices). On flagship, prefer LiteRT-LM with a Gemma 4 E2B (~2.6B) or Qwen2.5 1.5B via the QNN NPU backend for 2–3x the throughput vs CPU. Gate model selection at runtime on available RAM (≥6 GB free after OS → allow 1.5B; ≥3 GB → 1B; <3 GB → skip or queue). Set WorkManager constraints: setRequiresBatteryNotLow(true) + optionally setRequiresCharging(true) on mid-range. Never run a chapter-length rewrite as a foreground blocking call on any tier.

**Integration:** llama.cpp Android JNI: use the llama-android or maid project's JNI wrapper; add the .so to jniLibs for arm64-v8a and armeabi-v7a; load model from filesDir (same pattern as the existing sherpa-onnx TTS). LiteRT-LM (Google): single Gradle dependency 'com.google.ai.edge.litert:litert-lm:1.x', needs minSdk 26+ (QuickNovel minSdk is 21 — add Build.VERSION guard or use llama.cpp as universal fallback). Model download: Gemma 3 1B int4 = ~600 MB on-disk; Gemma 4 E2B int4 = ~1.3 GB; download lazily via DownloadManager to filesDir (same location as sherpa-onnx models). KV cache warning: a 4000-token context window with a 1B GQA model adds ~140–360 MB RAM for KV cache (fp16); use --ctx-size 2048 to halve it with minimal quality loss on chapter-rewriting tasks. Android foreground service timeout: SDK 34+ enforces foreground service timeouts; use WorkManager 2.10+ with setExpedited() for the inference job and keep individual paragraph jobs under 10 minutes to avoid STOP_REASON_FOREGROUND_SERVICE_TIMEOUT. Thermal: register a ThermalStatusListener (API 30+) and pause inference if status >= THERMAL_STATUS_SEVERE; resume when status drops back to THERMAL_STATUS_NONE or THERMAL_STATUS_LIGHT.

