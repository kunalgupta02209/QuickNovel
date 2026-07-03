# QuickNovel Background On-Device TTS Pre-Generation & Management — Authoritative Implementation Plan

## 0. Summary & guiding principle

Make the existing on-device synth path a **read-through / write-through disk cache**, then feed that same cache from a **headless background precacher** that reuses the exact same `generate → trimSilence → save` code. The cache is the single source of truth shared by the live reader engine and the headless precacher, so pre-generation and normal reading populate and consume byte-identical WAV files.

The entire feature pivots on one seam — `OnDeviceTtsEngine.render()`. The change there is additive and null-guarded, so it is completely inert until wired, and every later layer (WorkManager job, range picker, cross-book management screen) is optional plumbing stacked on a cache that is independently useful the moment it exists (instant re-listen, offline replay).

Absolute repo root: `C:/dev/QuickNovel/QuickNovel/.claude/worktrees/tts-ondevice-p1`

---

## 1. Architecture at a glance

Four clean layers, each mirroring a proven QuickNovel subsystem so the code stays idiomatic:

```
UI (2 surfaces)          TtsGenerateDialog (pick book + range)   TtsPregenFragment (manage all books)
        │                          │                                   │  Event<> subscribe
        ▼                          ▼                                   ▼
Orchestration            TtsPregenManager  ── singleton, mirrors BookDownloader2 ──┐
        │  enqueue                 │  in-mem state + DataStore + pending-actions    │ Events
        ▼                          ▼                                                ▼
Background job           TtsPregenWorkManager (CoroutineWorker, setForeground)  TtsPregenNotificationService
        │  runJob                  │
        ▼                          ▼
Synthesis + cache        TtsChapterSynthesizer ──writes──►  TtsAudioCache (WAV per sentence)
                                                                    ▲
Reader reuse             OnDeviceTtsEngine.render() ── read-through / write-through ┘
```

Design invariants that keep this clean and correct:

- **The cache is the single source of truth** shared by the headless precacher and the live reader. Both write byte-identical WAVs through `TtsAudioCache`.
- **The cache key deliberately excludes speed/pitch/gap.** `OnDeviceTtsEngine.render()` already synthesizes at `speed = 1.0f`; user speed/pitch are applied on the `AudioTrack` (`applyParams`) and gap is written as silence in `consumeLoop`. Cached audio is therefore reusable across every playback setting — the property that makes a cache trivially correct.
- **Reuse existing types verbatim** for progress/state (`DownloadProgressState`, `DownloadState`, `DownloadActionType`) so the existing progress-bar/ETA UI helpers (`DownloadProgressState.eta(context)`) work with zero new UI plumbing.
- **`applicationContext` everywhere in the background** — never `BookDownloader2.activity` (a possibly-null Activity the download code leans on; do not copy that pitfall).
- **Separate native handles** — the precacher opens and `release()`s its own `OfflineTts`; it never shares the playback-owned instance.

---

## 2. The seam — `OnDeviceTtsEngine.render()`

`render()` today: (a) calls `generateWithConfig[AndCallback]` at `speed=1.0f`, (b) accumulates chunks into `raw`, (c) `trimSilence(raw)` → `out`, (d) stores `item.pcm = out`. Because `out` is playback-parameter-independent, a `(bookId, modelId, sid, chapterIndex, sentence)` key is sufficient.

Add one `@Volatile` field and a two-insertion change:

```kotlin
@Volatile var cacheBookId: String? = null   // set by initTTSSession; null → feature inert

private fun render(item: Item) {
    val engine = tts ?: run { item.failed = true; return }
    val f = cacheBookId?.let { TtsAudioCache.fileFor(appContext, it, def.id, sid, item.line) }

    // READ-THROUGH: cache hit → ~1 ms disk read instead of real-time synth
    if (f != null && f.exists()) {
        val pcm = TtsAudioCache.load(f)
        if (pcm != null) { synchronized(lock) { item.pcm = pcm; lock.notifyAll() }; return }
    }

    // …existing synth into `raw`, then:
    val out = TtsAudioCache.trimSilence(raw)              // SHARED trim → byte-identical to precacher
    if (f != null) runCatching { TtsAudioCache.save(f, out, sampleRate) }   // WRITE-THROUGH
    synchronized(lock) { item.pcm = out; lock.notifyAll() }
}
```

The private `trimSilence` in `OnDeviceTtsEngine` is replaced by a call to `TtsAudioCache.trimSilence` so cached audio is byte-identical regardless of producer. `WaveReader.readWave` (used at `TtsModels.kt:247`) and `GeneratedAudio(FloatArray, Int).save(path)` are already-compiled native round-trip deps. On a cache hit the producer fills the look-ahead window instantly and the consumer never blocks on `item.pcm == null` → gapless, zero-latency reading. On a miss, normal reading transparently populates the cache and the management list.

---

## 3. Identity, cache format & keys

### 3.1 Book identity
Reuse the existing download identity so pregen artifacts align 1:1 with downloaded books:

- `bookId: Int = BookDownloader2Helper.generateId(apiName, author, name)` (`BookDownloader2.kt:216`).
- `bookDirComponent = "b$bookId"` (filename-safe).

The reader reproduces this via `QuickBook` (`data.meta.apiName / author / name`). Pre-generation is offered **only for downloaded stream books** (which always have all three), so the reader always computes the same `bookId` → cache hit. `RegularBook` (imported EPUB) falls back to `"h" + title().hashCode()` and is out of scope for P1 batch pregen, but still benefits from write-through caching if read on-device.

### 3.2 Composite generation identity
A book can be pre-generated under multiple model/voice combos, so the persistent unit of work is:

```
compositeKey: String = "$bookId|$modelId|$sid"      // e.g. "-183726611|kitten|3"
notifId: Int         = compositeKey.hashCode() xor 0x7715_0000.toInt()
```

`notifId` is XOR-namespaced so a pregen notification never collides with a concurrent **download** notification of the same book (download uses the bare `bookId` as its notification id).

### 3.3 On-disk cache layout (`filesDir/tts-cache/`)
```
filesDir/tts-cache/
  b<bookId>/
    <modelId>/
      s<sid>/
        c<chapterIndex>/
          <sha1Hex(speakOutMsg).take(24)>.wav   ← one file per sentence (TRIMMED, 24 kHz mono 16-bit)
          .done                                 ← marker: chapter fully synthesized
          meta.json                             ← {modelId, sid, sampleRate, sentences:[{hash,startChar,endChar}]}
```

- **Chapter dir `c<index>`** gives per-chapter granular eviction (required by delete-read / sync). `<index>` is the 0-based chapter index == `TTSHelper.TTSLine.index`.
- **Sentence file = `sha1(speakOutMsg).take(24)`**, NOT `startChar-endChar`. Content-addressing makes the cache robust to any rendering/offset drift between precacher and live engine: what the reader looks up is a hash of the exact string it is about to synthesize (`item.line.speakOutMsg`). This sidesteps boundary-drift and the title-line `startChar=0` collision entirely. (`meta.json` still records `startChar/endChar` for tooling/debug.)
- **Format**: `GeneratedAudio(trimmedSamples, sampleRate).save(path)` writes native 16-bit PCM WAV; `WaveReader.readWave(path).samples` reads it back. Sample rate is a constant 24000 but is also stored in `meta.json`.

### 3.4 Correctness invariants baked into `TtsAudioCache` (each maps to a research pitfall)
- Write the **trimmed** array only — parity with live `item.pcm`, else inter-sentence gaps drift.
- All `save()`/`load()` off-main, wrapped in `runCatching` — a full disk degrades to live synth, never crashes playback.
- Model/voice change needs **no purge**: a different `modelId`/`sid` is simply a different directory; old cache goes inert and is reclaimable via "Delete all".
- Content-hash filenames make title-line `0-0` vs first-body-sentence `0-…` collisions impossible.

---

## 4. Cache subsystem — `TtsAudioCache` (new)

`app/src/main/java/com/lagradost/quicknovel/tts/TtsAudioCache.kt` — `object`, **sole owner of the cache format** so engine and precacher can never disagree.

```kotlin
object TtsAudioCache {
    private const val ROOT = "tts-cache"
    const val SILENCE_THRESHOLD = 0.01f   // identical to OnDeviceTtsEngine

    fun bookIdFor(book: AbstractBook): String            // "b<generateId>" for QuickBook, "h<hash>" fallback
    fun bookDir(ctx, bookId): File
    fun chapterDir(ctx, bookId, modelId, sid, chapterIndex): File
    fun fileFor(ctx, bookId, modelId, sid, line: TTSHelper.TTSLine): File
        // chapterDir/…/<sha1(line.speakOutMsg).take(24)>.wav

    fun trimSilence(pcm: FloatArray): FloatArray                        // moved from OnDeviceTtsEngine
    fun save(dest: File, trimmed: FloatArray, sampleRate: Int): Boolean // GeneratedAudio(...).save()
    fun load(src: File): FloatArray?                                    // WaveReader.readWave().samples

    fun markChapterDone(ctx, bookId, modelId, sid, chapterIndex, lines) // writes .done + meta.json
    fun isChapterDone(...): Boolean
    fun chapterBytes(...): Long ; fun bookBytes(...): Long

    fun deleteChapter(...) ; fun deleteVoice(ctx, bookId, modelId, sid) ; fun deleteBook(ctx, bookId)
}
```

All JNI/file I/O; callers stay off the main thread.

---

## 5. Headless synthesizer — `TtsChapterSynthesizer` (new)

`app/src/main/java/com/lagradost/quicknovel/tts/TtsChapterSynthesizer.kt` — constructed per job with `(context, def: ModelDef, sid: Int, bookId: String)`. Opens its **own** `OfflineTts` and releases it in `finally`. No AudioTrack/focus/queue.

```kotlin
class TtsChapterSynthesizer(ctx, def, sid, bookId) {
    private val markwon = MarkwonFactory.create(ctx)
    private var tts: OfflineTts? = null
    fun open(): Boolean            // TtsModels.resolveConfig(ctx, def)?.let { OfflineTts(null, it) }
    fun close()                    // tts?.release()

    /** Returns sentence count synthesized, or -1 on failure. Skips existing files (resume). */
    suspend fun synthChapter(record, chapterIndex, html, chapterTitle,
                             onSentence: (done: Int, total: Int) -> Unit,
                             shouldStop: () -> Boolean): Int
}
```

`synthChapter` reproduces the reader's **exact** sentence pipeline so `speakOutMsg` strings (and thus content-hash keys) match:

1. `rawText = TTSHelper.preParseHtml(html, authorNotes)` (`TTSHelper.kt:557`)
2. `rendered = TTSHelper.render(rawText, markwon)` (`TTSHelper.kt:598`)
3. `lines = TTSHelper.ttsParseText(rendered.toString(), chapterIndex)` (`TTSHelper.kt:622`)
4. Prepend the title line exactly as the reader does: `lines.add(0, TTSLine(chapterTitle, 0, 0, chapterIndex))` (matches `LiveChapterData.ttsLines`, `ReadActivityViewModel.kt:460-470`).
5. For each line: `f = TtsAudioCache.fileFor(...)`; if `f.exists()` skip; else
   - ZipVoice: `TtsModels.resolveGenerationConfig(ctx, def, sid, 1.0f)?.let { tts.generateWithConfig(text, it) }`
   - else: `tts.generate(text = line.speakOutMsg, sid = sid, speed = 1.0f)` (blocking, callback-free — ideal headless API).
   - `val trimmed = TtsAudioCache.trimSilence(gen.samples)` then `TtsAudioCache.save(f, trimmed, gen.sampleRate)`.
   - `onSentence(++done, total)`; check `shouldStop()` between sentences.
6. On full completion `TtsAudioCache.markChapterDone(...)`.

HTML source is **offline**: `BookDownloader2.readDownloadedChapter(ctx, apiName, author, name, index)` reads `<index>.txt`. Missing indices (gaps from `DOWNLOAD_OFFSET`) are skipped, never fetched. Applies the same readiness/language guard as `initTTSSession` (SDK ≥ M, `ModelDownloadManager.isReady`, `onDeviceLanguageOk`).

---

## 6. Orchestration singleton — `TtsPregenManager` (new)

`app/src/main/java/com/lagradost/quicknovel/tts/TtsPregenManager.kt` — a trimmed clone of `BookDownloader2`'s state machine, keyed by `compositeKey: String` instead of `Int`.

**State (guarded by a `Mutex`):**
```kotlin
val pregenProgress = HashMap<String, DownloadProgressState>()   // reuse existing type
val pregenRecords  = HashMap<String, TtsPregenRecord>()
val currentJobs    = HashSet<String>()                          // dedupe + clean-stop gate
private val pendingAction = HashMap<String, DownloadActionType>()
```

**Events (subscribed by the ViewModel with `+=`/`-=`, mirroring `DownloadViewModel`):**
```kotlin
val pregenProgressChanged = Event<Pair<String, DownloadProgressState>>()
val pregenRecordChanged   = Event<Pair<String, TtsPregenRecord>>()
val pregenRemoved         = Event<String>()
val pregenRefreshed       = Event<Int>()
```

**Cooperative control** (identical pattern to `addPendingAction`/`consumeAction`, `BookDownloader2.kt:1352-1392`): an action is accepted only if `compositeKey ∈ currentJobs`; the job registers itself in `currentJobs` at the very start of `runJob` (before any awaitable work) so Stop/Pause buttons are never dropped. WorkManager's own `isStopped` is not honored by these loops — cancellation is cooperative.

**`PregenRequest`** (WorkManager payload, passed via the static map — never serialized into `Data`):
```kotlin
data class PregenRequest(val bookId: Int, val apiName: String, val author: String?, val name: String,
                         val posterUrl: String?, val modelId: String, val sid: Int,
                         val rangeStart: Int, val rangeEnd: Int)  // inclusive 0-based indices
```

**`TtsPregenRecord`** (persisted JSON, value of `TTS_PREGEN_FOLDER`):
```kotlin
data class TtsPregenRecord(
    val bookId: Int, val apiName: String, val author: String?, val name: String, val posterUrl: String?,
    val modelId: String, val sid: Int,
    val rangeStart: Int, val rangeEnd: Int,      // requested inclusive chapter indices
    val generatedChapters: Int, val totalChapters: Int,
    val bytes: Long, val lastUpdated: Long,
)
```

**`runJob(context, req)`** (called from the worker, on `applicationContext`):
1. Register `key` in `currentJobs` (return if already present → dedupe); seed `pregenProgress[key] = DownloadProgressState(IsPending, …, total = rangeEnd-rangeStart+1)`; persist an initial record; post notification.
2. Build a `TtsChapterSynthesizer(ctx, TtsModels.byId(modelId), sid, "b$bookId")`; `open()`.
3. Loop `for index in rangeStart..rangeEnd`:
   - poll `consumeAction(key)` → Pause spins `delay(200)` until Resume; Stop → early return (same shape as the download loop, `BookDownloader2.kt:2421-2447`).
   - if `TtsAudioCache.isChapterDone(...)` → skip (resume).
   - read HTML offline; `synthChapter(...)`, forwarding per-sentence progress to `changeProgress(key){ … }` → `TtsPregenNotifications.update(...)` (throttled ~1/sec).
   - update `record.generatedChapters` and `record.bytes`.
4. `finally { synth.close(); currentJobs -= key; persist final record; post Done/Failed notification }`.

**`init { reconcileFromDisk() }`** — mirrors `initDownloadProgress` (`BookDownloader2.kt:1314`): load every `TTS_PREGEN_FOLDER` record, recompute `generatedChapters`/`bytes` from the cache tree, AND scan `filesDir/tts-cache/*` for cache produced by reader write-through that has no record yet, creating a record by resolving `bookId → DOWNLOAD_FOLDER` metadata. This is what makes the management list show **ALL** pre-generated TTS regardless of origin. Then `pregenRefreshed.invoke(0)`.

**Management operations:**
- `deleteReadChapters(ctx, key)` — `readIndex = getKey<Int>(EPUB_CURRENT_POSITION, record.name) ?: 0`; delete every chapter dir `c<i>` with `i < readIndex`; recompute counts/bytes; fire `pregenRecordChanged`. (Reading position is keyed by book **name**, `ReadActivityViewModel.kt:1245/1783`.)
- `sync(ctx, key)` — `latest = downloaded chapter count`; if `latest-1 > record.rangeEnd` enqueue a new `PregenRequest(range = record.rangeEnd+1 .. latest-1)` with the same model/sid; bump `record.rangeEnd`.
- `deleteAll(ctx, key)` — `TtsAudioCache.deleteVoice(...)`; `removeKey(TTS_PREGEN_FOLDER, key)`; `pregenRemoved.invoke(key)`.
- `migrateBook(from, to)` — called from `BookDownloader2.migrateKeys`; moves records + renames the `b<from>` → `b<to>` cache dir.

---

## 7. WorkManager job — `TtsPregenWorkManager` (new)

`app/src/main/java/com/lagradost/quicknovel/TtsPregenWorkManager.kt` — a **separate** `CoroutineWorker` (do not touch `DownloadFileWorkManager`), same `insertWork/popWork` static-map indirection (dodges the 10 240-byte `Data` cap):

```kotlin
class TtsPregenWorkManager(context, params) : CoroutineWorker(context, params) {
  companion object {
    const val ID = "id"; const val DATA = "data"; const val ID_PREGEN = "TTS_PREGEN"
    private val workData = HashMap<Int, Any>(); private var n = 0
    private fun insertWork(d: Any): Int; private fun popWork(k: Int): Any?

    fun enqueue(context: Context, req: TtsPregenManager.PregenRequest) {
      DownloadFileWorkManager.getWorkerManager(context).enqueueUniqueWork(
        ID_PREGEN, ExistingWorkPolicy.APPEND,                 // one serial chain: OfflineTts is heavy
        OneTimeWorkRequest.Builder(TtsPregenWorkManager::class.java)
          .setInputData(Data.Builder().putString(ID, ID_PREGEN).putInt(DATA, insertWork(req)).build())
          .build())
    }
  }

  override suspend fun doWork(): Result {
    val req = popWork(inputData.getInt(DATA, -1)) as? TtsPregenManager.PregenRequest
        ?: return Result.failure()                            // process-death re-run: static map gone
    setForeground(TtsPregenNotifications.foregroundInfo(applicationContext, req)) // FGS compliance
    TtsPregenManager.runJob(applicationContext, req)
    return Result.success()
  }
}
```

Choices & rationale:
- **`ExistingWorkPolicy.APPEND` on a single constant unique name (`"TTS_PREGEN"`)** → all pregen jobs run **serially**, avoiding two `OfflineTts` instances competing for CPU. Duplicate `(book,model,sid)` requests are additionally deduped by `currentJobs` + skip-existing-files.
- **`setForeground(ForegroundInfo)`** — the deliberate improvement over the download flow (which never calls `startForeground` and risks OS kill on Android 12+/14). Resumability after process death is guaranteed by on-disk `.done` markers + the persisted record, not by the lost static payload; popping `null` → `Result.failure()` is acceptable because a re-enqueue resumes cleanly (existing files skipped).

---

## 8. Notifications + control service (new)

- **`TtsPregenNotifications`** (`tts/TtsPregenNotifications.kt`): dedicated channel `CHANNEL_ID_TTS = "quicknovel.tts_pregen"` (created on first use like `BookDownloader2.kt:842`). Builds a media-style notification titled "Generating audio", `setProgress` from `DownloadProgressState`, and Pause/Resume/Stop actions as `PendingIntent.getService` targeting `TtsPregenNotificationService`, using `notifId` (§3.2) and putting `compositeKey`/`type` as extras. Also exposes `foregroundInfo(ctx, req): ForegroundInfo`.
- **`TtsPregenNotificationService`** (`TtsPregenNotificationService.kt`, mirrors `DownloadNotificationService`): reads `key: String` + `type: String` extras → `TtsPregenManager.addPendingAction(key, DownloadActionType.{Resume|Pause|Stop})`.
- Reuse `DownloadActionType` verbatim. Namespaced id + dedicated channel ensure a TTS-generation job and a text-download of the same book never overwrite each other's notification.

---

## 9. Shared rendering — `MarkwonFactory` (new)

`app/src/main/java/com/lagradost/quicknovel/util/MarkwonFactory.kt` — `object` extracting the reader's Markwon builder (`ReadActivityViewModel.initMarkwon`, ~lines 1310-1354) into `MarkwonFactory.create(context)`. Both the reader and the precacher call it, guaranteeing identical HTML→text rendering so `speakOutMsg` (and content-hash keys) match. Small refactor; reader behavior unchanged.

---

## 10. UI Surface 1 — Generate picker (`TtsGenerateDialog`, new)

`ui/tts/TtsGenerateDialog.kt` — a `BottomSheetDialog` inflating `dialog_tts_generate.xml`, opened from the management fragment's "Generate new…" button and from a download-card long-press (prefilled). Flow:

1. **Book** — spinner/list of downloaded stream books: enumerate `getKeys(DOWNLOAD_FOLDER)` → `getKey<DownloadFragment.DownloadData>(key)`, filtered to non-imported providers (exclude `IMPORT_SOURCE` / `IMPORT_SOURCE_PDF`).
2. **Range** — a `RangeSlider` (1..N) mirrored to two `EditText`s for exact "start chapter" / "end chapter". `N = downloaded chapter count`. Convert 1-based UI numbers → 0-based indices (`index = number - 1`), clamped to `0 until N`.
3. **Model / voice** — defaults from `EPUB_TTS_OD_MODEL` / `EPUB_TTS_OD_VOICE` (`TtsModels.parseVoice`). Guard: if the selected model isn't `ModelDownloadManager.isReady`, disable Start and show "Download model in Read-aloud settings" (reuse the existing model-download UX rather than duplicating it).
4. **Start** → `TtsPregenManager.enqueue(request)` → `TtsPregenWorkManager.enqueue(ctx, request)`; dismiss; surface the row in Surface 2.

---

## 11. UI Surface 2 — Cross-ebook management (`TtsPregenFragment`, new)

`ui/tts/TtsPregenFragment.kt` — a `Fragment` (nav destination `navigation_tts_pregen`) reachable from the **Downloads** action bar (`action_pregen`) and optionally a Settings entry. Lists **all** `TtsPregenRecord`s across every ebook via `TtsPregenViewModel`, which subscribes to the four `TtsPregenManager` events (`+=` in `init`, `-=` in `onCleared`) exactly like `DownloadViewModel`.

Each `tts_pregen_row.xml` shows: poster (`BookDownloader2Helper.getCachedBitmap`), book name, `"<model> · Voice <sid+1>"`, a `LinearProgressIndicator` bound to `DownloadProgressState.progress/total`, state text via `DownloadProgressState.eta(context)`, and disk size (`record.bytes`). Row actions:

- **Pause / Resume / Stop** (when `state == IsDownloading/IsPaused`) → `TtsPregenManager.addPendingAction(key, …)`.
- Overflow (`tts_pregen_row_menu.xml`):
  - **Delete read chapters** → `deleteReadChapters(key)` (confirm dialog).
  - **Sync to latest downloaded** → `sync(key)` (enqueues the extension range; disabled if already at latest).
  - **Delete all** → `deleteAll(key)`.
  - **Generate more / edit range** → open `TtsGenerateDialog` prefilled.
- Top **"Generate new…"** button → `TtsGenerateDialog`.
- Empty state when no records.

Live updates flow `Event` → ViewModel `viewModelScope` → `LiveData`/`StateFlow` → adapter `DiffUtil`, the same pattern `DownloadViewModel.progressChanged` uses.

`ui/tts/TtsPregenViewModel.kt` backs both surfaces (exposes candidate books + records); `ui/tts/TtsPregenAdapter.kt` binds rows.

---

## 12. Reader playback reuse

`initTTSSession` (`ReadActivityViewModel.kt:1371`) sets `engine.cacheBookId = TtsAudioCache.bookIdFor(book)` right after constructing `OnDeviceTtsEngine`. Once a book is pre-generated, cold-start reading is gapless — `produceLoop` fills the look-ahead window from disk in ~ms and the consumer never blocks on `item.pcm == null`. Optional P4 extra: an opt-in "auto pre-generate N chapters ahead while reading" toggle that enqueues a `PregenRequest` for upcoming chapters from the reader.

**Critical parity check:** the precacher consumes the same `preParseHtml` → `MarkwonFactory.render` → `ttsParseText` + title-line-prepend pipeline as the reader (§5), so content-hash lookups always match. Reuse, don't re-parse.

---

## 13. New files (complete list)

| File | Type | Responsibility |
|------|------|----------------|
| `tts/TtsAudioCache.kt` | `object` | Path scheme, WAV save/load, shared `trimSilence`, per-chapter `.done`/`meta.json`, size + delete helpers. Sole owner of the cache format. |
| `tts/TtsChapterSynthesizer.kt` | `class` | Headless per-chapter synth into the cache; owns its own `OfflineTts`. |
| `tts/TtsPregenManager.kt` | `object` | Orchestration singleton: state maps, `Event<>` pub/sub, DataStore persistence, `runJob`, management ops. |
| `TtsPregenWorkManager.kt` | `CoroutineWorker` | WorkManager entry; `insertWork/popWork`, `enqueue`, `setForeground`. |
| `TtsPregenNotificationService.kt` | `Service` | Routes notification action buttons → `TtsPregenManager.addPendingAction`. |
| `tts/TtsPregenNotifications.kt` | `object` | Builds the pregen notification + `foregroundInfo`. |
| `util/MarkwonFactory.kt` | `object` | Shared Markwon builder (reader + precacher identical rendering). |
| `ui/tts/TtsGenerateDialog.kt` | `BottomSheetDialog` | Surface 1: pick book + range + model/voice → enqueue. |
| `ui/tts/TtsPregenFragment.kt` | `Fragment` | Surface 2: list ALL records; per-row actions; management ops. |
| `ui/tts/TtsPregenViewModel.kt` | `ViewModel` | Backs both surfaces; subscribes to manager events. |
| `ui/tts/TtsPregenAdapter.kt` | RecyclerView adapter | Row binding for the management list. |
| `res/layout/fragment_tts_pregen.xml` | layout | Management list (RecyclerView + "Generate new…" + empty state). |
| `res/layout/tts_pregen_row.xml` | layout | One record row. |
| `res/layout/dialog_tts_generate.xml` | layout | Book selector, `RangeSlider` + start/end `EditText`, model/voice label, Start. |
| `res/menu/tts_pregen_row_menu.xml` | menu | Overflow: delete-read / sync / delete-all / edit-range. |

All under `app/src/main/java/com/lagradost/quicknovel/…` unless a `res/` path.

---

## 14. Existing files to change (exact)

1. **`DataStore.kt`** — add `TTS_PREGEN_FOLDER` alongside the `DOWNLOAD_*` consts (§15).
2. **`tts/OnDeviceTtsEngine.kt`** — add `@Volatile var cacheBookId: String?`; add read-through/write-through in `render()` (§2); replace private `trimSilence` with a call to `TtsAudioCache.trimSilence`.
3. **`ReadActivityViewModel.kt`** — in `initTTSSession()` (line 1371) set `engine.cacheBookId = TtsAudioCache.bookIdFor(book)`; extract `initMarkwon` into `MarkwonFactory.create(context)` and call it here.
4. **`BookDownloader2.kt`** —
   - Extend `migrateKeys(from,to,oldName,newName)` (line 1408) to migrate `TTS_PREGEN_FOLDER` records whose `bookId == from` and rename cache dir `b<from>` → `b<to>` (delegate to `TtsPregenManager.migrateBook`). Also add the new keys to `copyAllData()` (285-316).
   - Add public offline helper `fun readDownloadedChapter(context, apiName, author, name, index): LoadedChapter?` wrapping private `getChapter` (line 615). Expose downloaded chapter count (highest `<n>.txt` present, or `getKey<Int>(DOWNLOAD_TOTAL, id)`).
5. **`AndroidManifest.xml`** — register `TtsPregenNotificationService` (mirror `DownloadNotificationService`, lines 45-49) with `android:foregroundServiceType="dataSync"`. Ensure `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` permissions exist (add if missing — required for the worker's `setForeground`). Existing WorkManager `<provider>` (line 275) already covers job init.
6. **`res/menu/download_actionbar.xml`** — add `action_pregen` (icon e.g. `ic_baseline_record_voice_over_24`).
7. **`ui/download/DownloadFragment.kt`** — handle `action_pregen` → navigate to `TtsPregenFragment`; long-press on a download card offers "Generate audio…" → `TtsGenerateDialog` prefilled.
8. **`res/navigation/mobile_navigation.xml`** — add `<fragment android:id="@+id/navigation_tts_pregen" .../>` + `<action>` from `navigation_download`.
9. **`res/values/strings.xml`** — all new strings.

---

## 15. DataStore keys (exhaustive)

**New:**
```kotlin
const val TTS_PREGEN_FOLDER: String = "tts_pregen_data"   // sub-keyed by compositeKey, value TtsPregenRecord
```
Stored/read like downloads: `setKey(TTS_PREGEN_FOLDER, compositeKey, record)` / `getKeys(TTS_PREGEN_FOLDER)`. Counts/range/bytes live inside the record and are recomputable from disk, so no separate per-counter keys are required. (If preferred for parity with downloads, optional mirrors `TTS_CACHE_TOTAL/DONE/SIZE` sub-keyed by `compositeKey` can be added — not necessary given `reconcileFromDisk`.)

**Reused (read-only) by the feature:**
- `DOWNLOAD_FOLDER` — enumerate candidate books; resolve name/author/apiName/poster for records.
- `DOWNLOAD_TOTAL` / on-disk `<n>.txt` — downloaded chapter count for range max + sync-to-latest.
- `DOWNLOAD_OFFSET` — awareness that early chapters may be absent (skip gaps).
- `EPUB_CURRENT_POSITION` (keyed by book **name**) — read position for delete-read.
- `EPUB_TTS_OD_MODEL` / `EPUB_TTS_OD_VOICE` — default model/sid in the picker and the reader's cache key.

---

## 16. Edge cases & pitfalls handled

- **Foreground compliance** — worker calls `setForeground` (fixes the download flow's non-compliance).
- **10 240-byte `Data` limit** — payload passed via static map; process-death re-run popping `null` → `Result.failure()` acceptable because resumability comes from `.done` markers + persisted record.
- **Notification collision** — `notifId` XOR-namespaced + dedicated channel, separate from the download `bookId` notification.
- **Dropped Pause/Stop** — job registers in `currentJobs` before any suspend point; cancellation is cooperative (WorkManager `isStopped` not relied upon).
- **Chapter identity / offset drift / title-line `0-0` collision** — sidestepped by **content-hash** sentence keys; trimming shared via `TtsAudioCache.trimSilence` so cached audio == live audio (no gap drift).
- **Translation** — the cache key is a hash of the exact rendered `speakOutMsg`. If ML-Kit/online translation alters chapter text, the translated string hashes differently → automatic miss + fresh synth; no stale audio served. Pregen batch jobs read the untranslated downloaded `<index>.txt`, so a book read with live translation simply gets write-through cache under different hashes (the two coexist harmlessly). Reader-side translation therefore never collides with pregen output.
- **Model/voice mismatch of cached audio** — audio under a different `modelId`/`sid` path is inert; changing voice yields a fresh directory and clean miss; "Delete all" reclaims stale voices. No cross-voice contamination possible.
- **Offline guarantee** — synthesizer reads `<index>.txt` and skips missing/gappy indices; never hits the network.
- **Separate `OfflineTts` handles** — precacher opens/`release()`s its own; never shares the live engine's.
- **Readiness/language guard** — picker + `runJob` re-check `ModelDownloadManager.isReady` and the English-only `ModelDef.lang` guard, mirroring `initTTSSession`/`onDeviceLanguageOk`.
- **Title-change migration** — `migrateKeys` moves pregen records + renames the `b<from>` cache dir.
- **Storage limits** (~48 KB/s ≈ 2.8 MB/min; a whole book can be hundreds of MB) — chapter-granular delete, delete-read, per-voice/-book delete-all, and the up-front range picker keep it bounded. Ship an optional LRU/size cap in P3. All `save`/`load` `runCatching`-wrapped so a full disk degrades to live synth, never crashes.
- **`applicationContext` only** in worker/precacher — never the possibly-null Activity.

---

## 17. Phased rollout (each phase independently shippable)

**P1 — Cache core + headless synth (no new job UI).** `TtsAudioCache` + move `trimSilence`; wire `OnDeviceTtsEngine` read/write-through + `cacheBookId` in `initTTSSession`; `MarkwonFactory`; `BookDownloader2.readDownloadedChapter`; `TtsChapterSynthesizer`. Add one "Pre-generate this chapter" action in the existing Read-aloud sheet (`ReadActivity2.showTtsSettingsDialog`, line 433) that launches the precacher in `lifecycleScope`. Reading now self-caches — independently valuable (instant re-listen, offline replay). **Low risk:** the only edit to a running path is additive and null-guarded; delete the lambda and behavior is byte-identical to today.

**P2 — Range picker + background job.** `TtsPregenManager`, `TtsPregenWorkManager`, `TtsPregenNotifications`, `TtsPregenNotificationService`, `TTS_PREGEN_FOLDER`, manifest service + permissions, `TtsGenerateDialog` + Surface-1 wiring. Serial background generation with foreground notification + Pause/Resume/Stop.

**P3 — Cross-ebook management + delete/sync.** `TtsPregenFragment` + `TtsPregenViewModel` + `TtsPregenAdapter` + layouts/menu, nav wiring, `reconcileFromDisk`, `deleteReadChapters` / `sync` / `deleteAll`, `migrateKeys` + `copyAllData` hooks, optional size cap/`meta.json` staleness validation.

**P4 — Reader reuse polish.** `initTTSSession` cache wiring is already live from P1; add opt-in "auto pre-generate N chapters ahead while reading". Strings, empty states, confirm dialogs, model-not-ready guidance.

---

## 18. Device verification

No JVM test suite exists (per CLAUDE.md) — verify with `./gradlew assembleDebug` + `installDebug` on an emulator/device (on-device engine needs SDK ≥ M and a downloaded model).

1. **P1 round-trip** — download a stream book; download the Kitten model in Read-aloud settings; read one chapter, stop, replay → logcat shows the `render` rtf synth line replaced by an instant cache read; confirm `.wav` files under `filesDir/tts-cache/b<id>/kitten/s<sid>/c<index>/` (`adb shell run-as com.lagradost.quicknovel.debug ls …`). Toggle airplane mode and replay → still plays (cache hit, no synth).
2. **P2 background job** — Generate audio for chapters 3–8 → confirm foreground notification, progress, Pause/Resume/Stop, and 6 chapter dirs filling with `.wav` + `.done`. Kill the process mid-job and re-enqueue → resumes (existing files skipped).
3. **Reader reuse** — open the reader on chapter 5 with on-device TTS → confirm instant, gapless start (cache hit; `render` logcat shows the disk-read path, not `synth=…rtf=…`); highlight/seek land on the right sentences; inter-sentence gaps equal the configured `gapMs` (trim parity holds).
4. **P3 management** — read to chapter 6, "Delete read chapters" → `c2..c4` gone (`du` under `run-as` confirms space freed); "Sync to latest" after downloading more chapters enqueues the extension; "Delete all" clears the tree and removes the row; management list shows books pre-generated via both batch job and reader write-through.
5. **Migration** — rename-triggered id migration keeps the record + cache attached (via `migrateKeys`).
6. **Voice mismatch** — switch voice/model, replay → clean miss + fresh synth under a new directory; old voice reclaimable via Delete all.

Key existing files for the implementer (absolute):
- `…/app/src/main/java/com/lagradost/quicknovel/tts/OnDeviceTtsEngine.kt` (`render()` 288-318, `applyParams` 204-211, `consumeLoop` 358-364)
- `…/tts/TtsModels.kt` (`resolveConfig`, `resolveGenerationConfig`, `WaveReader.readWave` 247, `GeneratedAudio.save`)
- `…/tts/ModelDownloadManager.kt` (`isReady`, `delete` 63-67)
- `…/ReadActivityViewModel.kt` (`initTTSSession` 1371-1392, `LiveChapterData.ttsLines` 460-470, markwon builder ~1310-1354, `EPUB_CURRENT_POSITION` 1245/1783)
- `…/TTSHelper.kt` (`preParseHtml` 557, `render` 598, `ttsParseText` 622)
- `…/BookDownloader2.kt` (`generateId` 216, `getChapter` 615, notifications 842/867, pending-actions 1352-1392, `initDownloadProgress` 1314, download loop 2390-2544, `migrateKeys` 1408, `copyAllData` 285-316)
- `…/DownloadFileWorkManager.kt` (`insertWork/popWork` 45-57, `getWorkerManager`), `…/DownloadNotificationService.kt`, `…/DataStore.kt` (`DOWNLOAD_*` 14-19, `EPUB_TTS_OD_*` 40-43)
- `…/ui/download/DownloadFragment.kt` / `DownloadViewModel.kt`
- `…/ReadActivity2.kt` (`showTtsSettingsDialog` 433-526) + `res/layout/tts_settings.xml`, `tts_model_row.xml`
