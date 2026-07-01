# Downloader Module — Business Logic

This document describes how QuickNovel downloads novels, tracks progress, surfaces
state to the UI and notifications, packages results as EPUB, and stores files.

## Source files

| File | Role |
|------|------|
| `app/src/main/java/com/lagradost/quicknovel/BookDownloader2.kt` | Core download engine, EPUB packaging, progress/state store, notification builder |
| `app/src/main/java/com/lagradost/quicknovel/DownloadFileWorkManager.kt` | `CoroutineWorker` that runs downloads/refreshes off the main thread and survives backgrounding |
| `app/src/main/java/com/lagradost/quicknovel/DownloadNotificationService.kt` | `Service` that turns notification action buttons into pending download actions |
| `app/src/main/java/com/lagradost/quicknovel/ui/download/DownloadViewModel.kt` | Observes the engine's events, builds the per-card list for the UI |
| `app/src/main/java/com/lagradost/quicknovel/ui/download/DownloadFragment.kt` | Defines `DownloadData`/`DownloadDataLoaded`, observes `viewModel.pages` |
| `app/src/main/java/com/lagradost/quicknovel/DataStore.kt` | Persisted key constants (`DOWNLOAD_*`) |
| `app/src/main/java/com/lagradost/quicknovel/ui/settings/SettingsFragment.kt` | `getBasePath()` / `getDefaultDir()` — SafeFile/SAF destination resolution |

> Note: `app/src/main/java/com/lagradost/quicknovel/receivers/BecomingNoisyReceiver.kt`
> is the only file under `receivers/`; it pauses **TTS** on audio-becoming-noisy and is
> **not** part of the downloader. There is no download-specific `BroadcastReceiver`.

The relevant `BroadcastReceiver` equivalent for downloads is the `Service`
`DownloadNotificationService`, which receives notification button taps.

---

## 1. Core data model

Defined at the top of `BookDownloader2.kt`:

- `enum class DownloadActionType { Pause, Resume, Stop }` (`BookDownloader2.kt:101`) — user-driven control actions.
- `enum class DownloadState { IsPaused, IsDownloading, IsDone, IsFailed, IsStopped, IsPending, Nothing }` (`BookDownloader2.kt:138`).
- `data class DownloadProgress(progress, total, downloaded)` (`BookDownloader2.kt:107`) — a snapshot computed from disk.
- `data class DownloadProgressState(state, progress, downloaded, total, lastUpdatedMs, etaMs)` (`BookDownloader2.kt:113`) — the live, in-memory progress object kept per novel; `eta(context)` (`BookDownloader2.kt:124`) maps state to a user-facing string.
- `data class LoadedChapter(title, html)` (`BookDownloader2.kt:137`) — a single decoded chapter.
- `QuickStreamMetaData` / `QuickStreamData` (`BookDownloader2.kt:148`, `:154`) — used by the "stream/read without full download" path.

There are **two** novel representations in the UI layer (in `DownloadFragment`):

- `DownloadData` (`DownloadFragment.kt:43`) — the persisted metadata blob (JSON, stored under `DOWNLOAD_FOLDER`).
- `DownloadDataLoaded` (`DownloadFragment.kt:73`) — `DownloadData` plus live fields `downloadedCount`, `downloadedTotal`, `ETA`, `state`, `generating`. `isImported` is `true` when `apiName == IMPORT_SOURCE || apiName == IMPORT_SOURCE_PDF` (`DownloadFragment.kt:108`).

### Novel identity

`generateId(apiName, author, name)` (`BookDownloader2.kt:216`) sanitizes the three
strings and returns `"$sApiname$sAuthor$sName".hashCode()`. This `Int` id keys
**everything**: persisted keys, the in-memory maps, notifications, and pending actions.
Because the id derives from the title/author, when a re-loaded novel reports a different
name the engine **migrates** all keys to the new id (see §10).

### Persisted keys (`DataStore.kt:14`)

SharedPreferences-backed (JSON via Jackson `mapper`):

- `DOWNLOAD_FOLDER` (`downloads_data`) — the `DownloadData` per id; enumerated to rebuild the library.
- `DOWNLOAD_TOTAL` (`downloads_total`) — total chapter/section count.
- `DOWNLOAD_SIZE` (`downloads_size`) — cached computed downloaded count.
- `DOWNLOAD_OFFSET` (`downloads_offset`) — start chapter index (used for partial / stream-read downloads).
- `DOWNLOAD_EPUB_SIZE` (`downloads_epub_size`) — number of chapters baked into the last generated EPUB (used to decide whether to regenerate).
- `DOWNLOAD_EPUB_LAST_ACCESS` (`downloads_epub_last_access`) — last-opened timestamp, used for sorting.

---

## 2. How a download is initiated and queued

Entry points all funnel into `DownloadFileWorkManager`:

1. **From a novel page** — `BookDownloader2.download(load, context)` (`BookDownloader2.kt:1560`) delegates to `DownloadFileWorkManager.download(load, context)`.
2. **Re-download / refresh a library card** — `DownloadViewModel.refreshCard(card)` (`DownloadViewModel.kt:137`) → `DownloadFileWorkManager.download(card, context)`.

`DownloadFileWorkManager.download(load: LoadResponse, …)` (`DownloadFileWorkManager.kt:124`)
**rejects imports** (`IMPORT_SOURCE`, `IMPORT_SOURCE_PDF`) and otherwise calls `startDownload`.

### The 10 KB Data limit workaround

WorkManager input `Data` cannot exceed 10240 bytes serialized. Instead of serializing
the (potentially large) `LoadResponse`/`StreamResponse`, the work object is stashed in a
process-memory map: `insertWork(data)` (`DownloadFileWorkManager.kt:45`) stores it under a
monotonically increasing `workNumber` and only the **int key** is put into `Data`
(`DownloadFileWorkManager.kt:110`). The worker later calls `popWork(key)`
(`DownloadFileWorkManager.kt:53`) to retrieve it. (Caveat: this couples the queued work to
process lifetime — if the process is killed before the worker runs, the payload is lost.)

### Enqueue policy (`startDownload`, `DownloadFileWorkManager.kt:102`)

```
enqueueUniqueWork(
    ID_DOWNLOAD + System.currentTimeMillis(),  // unique name per request
    ExistingWorkPolicy.APPEND,                  // serialize downloads one after another
    OneTimeWorkRequest…)
```

`ExistingWorkPolicy.APPEND` chains download work so chapters from different novels do not
all run at once. `getWorkerManager` (`DownloadFileWorkManager.kt:75`) lazily initializes
WorkManager if the default initializer was disabled.

Two other unique-work flows exist:
- `refreshAll` (`:59`) — `ID_REFRESH_DOWNLOADS`, `REPLACE` policy — rebuilds the library list.
- `refreshAllReadingProgress` (`:85`) — `ID_REFRESH_READINGPROGRESS_<tab>`, `KEEP` policy — refetches chapter counts for a reading-list tab.

The comment at `DownloadFileWorkManager.kt:20` states the reason WorkManager is used at
all: *"newer android versions pause network connections in the background"*, so the actual
network loop must run inside a worker.

---

## 3. The worker dispatch (`doWork`)

`DownloadFileWorkManager.doWork()` (`DownloadFileWorkManager.kt:136`) reads the `ID` discriminator and dispatches:

- `ID_DOWNLOAD` → `popWork(...)` and branch on type:
  - `StreamResponse` → `BookDownloader2.downloadWorkThread(data, api)` (chapter-by-chapter).
  - `EpubResponse` → `BookDownloader2.downloadWorkThread(data, api)` (whole-file download, e.g. Anna's Archive).
  - `DownloadDataLoaded` → either `downloadPDFWorkThread` (if `IMPORT_SOURCE_PDF`) or `downloadWorkThread(data)` which re-loads the source from the API first.
- `ID_REFRESH_DOWNLOADS` → `viewModel?.refreshInternal()`.
- `ID_REFRESH_READINGPROGRESS` → `getOldDataReadingProgress(currentTab)`.

The `viewModel` is held as a `WeakReference` (`DownloadFileWorkManager.kt:33`) so the worker
can call back into the live `DownloadViewModel` without leaking it.

### Re-loading a library card (`downloadWorkThread(card)`, `BookDownloader2.kt:1471`)

For a `DownloadDataLoaded`: skips imports, dedupes against `currentDownloads`, marks state
`IsPending`, then `api.load(card.source, allowCache = false)`. If the freshly loaded novel
hashes to a new id (`oldId != newId`), it migrates keys and copies files (§10) before
dispatching to the `EpubResponse` / `StreamResponse` overload.

---

## 4. Chapter fetching and assembly (StreamResponse path)

This is the main "download a serialized web novel" path.

`downloadWorkThread(load: StreamResponse, api)` (`BookDownloader2.kt:2377`):
- Reads `DOWNLOAD_OFFSET` to get `desiredStart`, coerces it into the chapter range, and calls the ranged overload with `desiredStart until load.data.size`.

`downloadWorkThread(load, api, range)` (`BookDownloader2.kt:2388`):

1. `setPrefixData(load, api.name, totalItems, alreadyDownloaded)` (`:2407`) — registers the download as active (see §6), seeds `DOWNLOAD_TOTAL`, and pushes an `IsPending` progress state.
2. `downloadImage(...)` (`:2413`, defined `:2340`) — fetches the poster once and writes it to `poster.jpg` under the novel's files dir; skips re-download if the stored URL key is unchanged.
3. **Per-chapter loop** (`for index in range`, `:2419`):
   - **Action gate** — drains `consumeAction(id)` (`:2424`); if `Pause`, busy-waits with `delay(200)` until resumed/stopped; if `Stop`, returns.
   - Skips chapters whose file already exists and is `> 10` bytes (`:2456`) — this is the resume/idempotency mechanism.
   - `BookDownloader2Helper.downloadIndividualChapter(filepath, api, data)` (`:2462`) does the actual fetch.
   - Maintains a rolling average `timePerLoadMs` (`:2478`) to compute `etaMs`.
   - `changeDownload(id) { progress = index+1; downloaded = …; state; etaMs }` (`:2481`) updates live state and fires a notification.
   - On per-chapter failure, state becomes `IsFailed` and the loop returns (`:2492`).
4. After the loop, if any chapter was downloaded, `setSuffixData` updates `lastDownloaded` and state becomes `IsDone` with `progress = total` (`:2517`).
5. `finally` removes the id from `currentDownloads` (`:2538`).

### Individual chapter fetch — `downloadIndividualChapter` (`BookDownloader2.kt:641`)

- If the file already exists and is non-empty and `!forceReload`, returns `true` immediately.
- Retries up to `maxTries` (default 5) with escalating `delay(5000L * (i+1))` on failure.
- Honors `api.rateLimitTime` via `api.api.rateLimitMutex` (locked for the whole request, then `delay(rateLimitTime)`).
- Calls `api.loadHtml(data.url)`; on non-blank result writes the file as `"${data.name}\n${page}"` — i.e. **first line is the chapter title, remainder is HTML**. `createNewFile()` is only called once content is in hand, so empty/failed chapters leave no file (preserving the resume logic above).

Files are stored at `getFilename(apiName, author, name, index)` →
`/<apiName>/<author>/<name>/<index>.txt` under the app's internal `filesDir`
(`BookDownloader2Helper.getFilename`, `:178`).

### Reading a chapter back — `getChapter` (`BookDownloader2.kt:615`)

Splits the file at the first `\n` into `title` / `data`, optionally runs `stripHtml(...)`
to remove external links and author notes based on user settings (`getStripHtml`,
`getStripAuthorNodes`, `:597`/`:603`).

---

## 5. Other download paths

### Whole-file EPUB download (`EpubResponse`, e.g. Anna's Archive) — `BookDownloader2.kt:2146`

- `setPrefixData(load, api.name, 1L, 0L)` — single-unit "download".
- Downloads the poster, then if a valid local EPUB already exists (`> LOCAL_EPUB_MIN_SIZE`) marks `IsDone`.
- Otherwise `ExtractorApi.extract(load.downloadExtractLinks + load.downloadLinks)` (`:2181`), sorts links by `kbPerSec`, then streams the body to `local_epub.epub` with a buffered copy loop.
- During the byte copy it computes `etaMs = ((totalTimeSoFar * total) / progress) - totalTimeSoFar` (`:2256`) and emits **byte-based** notifications (`progressInBytes = true`), throttled to once per second (`:2291`).
- The same `consumeAction` pause/resume/stop gate is applied both before each link and inside the byte loop.

### PDF import → EPUB — `downloadPDFWorkThread` (`BookDownloader2.kt:1780`)

- Uses PDFBox (`PDDocument.load`). Groups pages into chapters of `pagesPerChapter = 10`.
- For each page, extracts text via `PDFTextStripperByArea` with header/footer trimmed (`pdfPageWithoutHAndF`, `:1715`) and converts to HTML (`textToHtmlChapter`).
- Extracts embedded images `> MIN_IMAGE_SIZE` (`processImageObject`, `:1732`); the first image becomes the cover.
- Writes per-chapter `chapterN.xhtml` into a resumable temp folder `cacheDir/temp_<id>` (`:1815`), so a re-run can resume from existing files (`:1841`).
- On completion, assembles a `me.ag2s.epublib` `EpubBook` (sections + image resources) and writes the final `local_epub.epub`; deletes the temp folder in `finally`.
- `preloadPartialImportedPdf` (`:1992`) builds a partial EPUB from whatever temp files exist so a half-imported PDF is still readable.

### EPUB file import — `downloadWorkThread(data: Uri, context)` (`BookDownloader2.kt:2038`)

- Opens the file via `AndroidZipFile` + `EpubReader().readEpubLazy(...)`.
- Derives author/name from metadata (falls back to display name when there's no cover image).
- Extracts a cover (metadata cover, or first image `> MIN_IMAGE_SIZE`), then **copies the raw EPUB bytes** to `local_epub.epub` and marks `IsDone`.

---

## 6. Progress / state tracking (in-memory store)

`BookDownloader2` (object) holds the canonical live state, all guarded by
`downloadInfoMutex` (`BookDownloader2.kt:1302`):

- `downloadProgress: HashMap<Int, DownloadProgressState>` (`:1303`) — live state per id.
- `downloadData: HashMap<Int, DownloadFragment.DownloadData>` (`:1305`) — metadata per id.
- `currentDownloads: HashSet<Int>` + `currentDownloadsMutex` (`:1344`) — the set of ids currently downloading; prevents double-starting the same novel and is polled by delete.
- `pendingAction: HashMap<Int, DownloadActionType>` + `pendingActionMutex` (`:1347`) — queued pause/resume/stop per id.

### Event bus (observer pattern)

Four `Event<…>` instances (`util/Event`) are the bridge to the UI (`:1307`):

- `downloadProgressChanged: Event<Pair<Int, DownloadProgressState>>`
- `downloadDataChanged: Event<Pair<Int, DownloadFragment.DownloadData>>`
- `downloadRemoved: Event<Int>`
- `downloadDataRefreshed: Event<Int>`

`changeDownload(id) { … }` (`BookDownloader2.kt:1392`) is the single mutator: under the
mutex it applies the lambda to the live `DownloadProgressState`, stamps `lastUpdatedMs`,
then fires `downloadProgressChanged`. Almost every state transition goes through it and is
paired with a `createNotification(...)`.

### Bootstrapping from disk — `initDownloadProgress` (`BookDownloader2.kt:1312`)

Called from the object's `init {}` block (`BookDownloader2.kt:2544`). Enumerates
`DOWNLOAD_FOLDER` keys, and for each calls `BookDownloader2Helper.downloadInfo(...)` to
compute a `DownloadProgress` from files on disk, seeds the two maps, then fires
`downloadDataRefreshed`.

### Computing progress from disk — `downloadInfo` (`BookDownloader2.kt:345`)

The source of truth for "how much is downloaded" when no live download is running:
- If `local_epub.epub` exists, it's treated as a finished single-unit download (or, for PDFs, counts `.xhtml` files in the temp folder).
- Otherwise it lists the `<index>.txt` files, finds the **first contiguous run** of indices starting at `DOWNLOAD_OFFSET`, and reports `(lastIndex+1)` as progress and the run length as `downloaded`. `DOWNLOAD_TOTAL` provides `total`.

### `setPrefixData` / `setSuffixData`

- `setPrefixData(load, apiName, total, downloaded)` (`BookDownloader2.kt:1592`) — registers the id into `currentDownloads` (returning early if already present), persists `DOWNLOAD_FOLDER` + `DOWNLOAD_TOTAL`, and seeds/updates the live `DownloadProgressState` to `IsPending`. This is the "download starting" handshake.
- `setSuffixData(load, apiName)` (`BookDownloader2.kt:1564`) — rewrites `DOWNLOAD_FOLDER` with a fresh `lastDownloaded` timestamp and fires `downloadDataChanged`. Called when work actually produced output.

---

## 7. Notifications

`NotificationHelper` (`BookDownloader2.kt:809`) owns the notification channel
(`CHANNEL_ID = "epubdownloader.general"`, `:810`) and builder.

`createNotification(...)` (`BookDownloader2.kt:867`):
- Maps `DownloadState` → status text + small icon (`rddone` / `rdload` / `rdpause` / `rderror`, `:924`).
- Shows a determinate/indeterminate progress bar while `IsDownloading`/`IsPaused`, plus an ETA "remaining" subtext (`:934`).
- For byte downloads (`progressInBytes`) it renders `Kb` units; for stream novels it renders `progress / total` chapter counts (`:904`).
- **Action buttons** (`:949`): while downloading it offers `Pause` + `Stop`; while paused it offers `Resume` + `Stop`. Each button is a `PendingIntent.getService(...)` targeting `DownloadNotificationService` with extras `type` (lowercased action name) and `id` (`:956`). Request codes are offset by `4337 + index + id` to keep them distinct.
- Posts via `NotificationManagerCompat.notify(id, …)` — the **notification id is the novel id**, so updates replace in place.

`BookDownloader2.createNotification(id, load, state)` (`BookDownloader2.kt:1366`) is the
private wrapper that supplies `activity`, `load.url`, name, and poster URL.

`etaToString` (`BookDownloader2.kt:852`) formats milliseconds into `hh mm ss`.

---

## 8. Pause / Resume / Stop (control flow)

The action loop is fully decoupled from the UI/notification via the `pendingAction` map.

1. **Trigger** — either:
   - Notification button → `DownloadNotificationService.onStartCommand` (`DownloadNotificationService.kt:8`) reads `type`+`id`, maps to a `DownloadActionType`, and calls `BookDownloader2.addPendingAction(id, action)`.
   - UI button → `DownloadViewModel.pause/resume(card)` (`DownloadViewModel.kt:141`/`:145`) → `BookDownloader2.addPendingAction(card.id, …)`.
2. **Enqueue** — `addPendingActionAsync` (`BookDownloader2.kt:1354`) only records the action **if the id is in `currentDownloads`** (otherwise a paused/finished download can't be poked), then stores it in `pendingAction[id]`.
3. **Consume** — inside each download loop, `consumeAction(id)` (`BookDownloader2.kt:1382`) atomically removes and returns the pending action. The loops translate it to a new `DownloadState`:
   - `Pause` → state `IsPaused`, then the loop spins on `delay(200)` re-consuming actions until it sees `Resume`/`Stop`.
   - `Resume` → `IsDownloading`, breaks the wait.
   - `Stop` → `IsStopped`, the function `return`s, ending the worker.

   (PDF path uses the same pattern via `handleDownloadActions`, `BookDownloader2.kt:1680`.)

Because pause is cooperative polling (not coroutine cancellation), pausing only takes
effect at the next loop checkpoint.

### Delete — `deleteNovelAsync` (`BookDownloader2.kt:1169`)

1. Issues a `Stop` pending action.
2. Busy-waits (`delay(100)`) until the id leaves `currentDownloads` — i.e. waits for the worker to actually halt.
3. `BookDownloader2Helper.deleteNovel(...)` (`:318`) removes `DOWNLOAD_SIZE/TOTAL/EPUB_SIZE/OFFSET` keys and `deleteRecursively()` the novel's files dir.
4. Removes the id from `downloadData`/`downloadProgress` and fires `downloadRemoved`.

Invoked from the UI by `DownloadViewModel.delete(card)` → `BookDownloader2.deleteNovel(...)`
(`DownloadViewModel.kt:292`) behind a confirmation dialog (`deleteAlert`, `:271`).

---

## 9. EPUB packaging (epublib)

QuickNovel uses the bundled `me.ag2s.epublib` library
(`EpubBook`, `EpubReader`, `EpubWriter`, `Resource`, `MediaTypes.XHTML`, etc.,
imported at `BookDownloader2.kt:87`).

### Generating the EPUB from downloaded chapters — `turnToEpub` (`BookDownloader2.kt:688`)

- Resolves the destination `SafeFile` via `activity.getBasePath().first ?: getDefaultDir(activity)` (`:709`), deletes any existing `<name>.epub`, and creates a new one (`:716`).
- **Fast path**: if a valid `local_epub.epub` already exists (`> LOCAL_EPUB_MIN_SIZE`), it just streams those bytes to the output and records `DOWNLOAD_EPUB_SIZE = 1` (`:726`). This covers imported/whole-file novels.
- **Assembly path** (serialized chapters):
  - Builds an `EpubBook`, adds `Author`, description (synopsis), title.
  - Adds `poster.jpg` as `book.coverImage` if present (`:747`).
  - Lists chapter files `>= DOWNLOAD_OFFSET`, decodes each via `getChapter` in parallel with `pmap` (`:761`), wraps each as a `Resource("id$i", html, "chapter$i.html", MediaTypes.XHTML)`, sorts by index, and `book.addSection(title, resource)` for each (`:792`).
  - Throws `ErrorLoadingException("Unable to create an empty book")` if nothing decoded.
  - `EpubWriter().write(book, fileStream)` (`:798`) and stores `DOWNLOAD_EPUB_SIZE = largestChapter` so future opens know how many chapters are baked in.

### When is it (re)generated? — `readEpub` (`BookDownloader2.kt:1147`)

`DownloadViewModel.readEpub(card)` (`DownloadViewModel.kt:162`) sets `generating = true` on
the card, then calls `BookDownloader2.readEpub(id, downloadedCount, …)`. That compares
`downloadedCount` against the persisted `DOWNLOAD_EPUB_SIZE`; if they differ
(`shouldUpdate`, `:1159`) it regenerates via `generateAndReadEpub` → `turnToEpub`,
otherwise it just opens the existing file (`hasEpub` / `openEpub`). On finish it stamps
`DOWNLOAD_EPUB_LAST_ACCESS` (`DownloadViewModel.kt:177`).

The PDF and whole-file paths write `local_epub.epub` directly with `EpubWriter` rather than
going through `turnToEpub`'s assembly path.

---

## 10. Where files are stored (internal files + SafeFile/SAF)

There are **two distinct storage tiers**:

### a) Working files — app-internal `filesDir` (`File`)

Per-chapter `.txt`, `poster.jpg`, and the cached `local_epub.epub` all live under the app's
private `filesDir` using the layout from `BookDownloader2Helper`:
- `getDirectory(apiName, author, name)` → `/<apiName>/<author>/<name>` (`:174`)
- `getFilename(..., index)` → `…/<index>.txt` (`:178`)
- `getFilenameIMG(...)` → `…/poster.jpg` (`:182`)
- `LOCAL_EPUB = "local_epub.epub"`, `LOCAL_EPUB_MIN_SIZE = 1000` (`:1660`)

Filenames are run through `sanitizeFilename` (`:166`) which strips reserved characters.

### b) Final EPUB — user-visible storage via SafeFile (SAF)

The shareable `<name>.epub` is written to a `SafeFile` (the `com.lagradost.safefile`
abstraction over both raw `File` paths and SAF `content://` document trees):
- `Context.getBasePath()` (`SettingsFragment.kt:152`) returns the user-configured download path; `basePathToFile` (`:133`) resolves a stored string into a `SafeFile` via `SafeFile.fromUri` (SAF) or `SafeFile.fromFilePath`.
- `getDefaultDir(context)` (`SettingsFragment.kt:122`) defaults to `SafeFile.fromMedia(context, MediaFileContentType.Downloads).gotoDirectory("Epub")` — i.e. `Downloads/Epub/`.
- `turnToEpub` writes through `subDir.createFileOrThrow(displayName).openOutputStream(...)`.

`BookDownloader2Helper.hasEpub` (`:259`) checks for an existing EPUB; on scoped storage
(API 29+) it queries `MediaStore.Downloads` (`getExistingDownloadUriOrNullQ`, `:469`),
otherwise it stats the file directly.

Imported EPUB/PDF read directly from a user `Uri` through `ContentResolver` /
`SafeFile.fromUri` (`downloadPDFWorkThread` `:1802`, `downloadWorkThread(Uri)` `:2041`).

---

## 11. How the Download UI observes state

`DownloadViewModel` is the observer that converts engine events into a renderable list.

### Subscription (`DownloadViewModel.init`, `DownloadViewModel.kt:516`)

```
BookDownloader2.downloadDataChanged    += ::progressDataChanged
BookDownloader2.downloadProgressChanged += ::progressChanged
BookDownloader2.downloadDataRefreshed  += ::downloadDataRefreshed
BookDownloader2.downloadRemoved        += ::downloadRemoved
```
(unsubscribed symmetrically in `onCleared`, `:523`).

- `progressChanged(id, state)` (`:552`) — copies the matching `cardsData[id]` with new `downloadedCount/downloadedTotal/state/ETA`, then `postCards()`.
- `progressDataChanged(id, value)` (`:574`) — updates metadata, creating a new `DownloadDataLoaded` if the card is new.
- `downloadRemoved(id)` (`:567`) — drops the card.
- `downloadDataRefreshed(_)` (`:649`) → `fetchAllData(true)` (`:617`) — rebuilds all cards from `BookDownloader2.downloadData` + `downloadProgress`.

### Internal card store and paging

`cardsData: HashMap<Int, DownloadDataLoaded>` (guarded by `cardsDataMutex`, `:549`) is the
ViewModel's copy. `postCards()` (`:504`) rebuilds page 0 (the "Downloads" page) via
`getDownloadedCards()` and posts `_pages`. `loadAllData` (`:455`) additionally builds the
reading-list tabs (Reading/On-hold/Plan/Completed/Dropped) from `RESULT_BOOKMARK_STATE`.

Sorting (`sortArray`, `:300`) supports alpha, download size, download percentage, last
access, last updated — selection persisted under `DOWNLOAD_SORTING_METHOD`.

### Fragment binding

`DownloadFragment` observes `viewModel.pages` (`DownloadFragment.kt:167`) and calls
`adapter.submitList(pages)`; it also observes `viewModel.isRefreshing` for the swipe
spinner (`:262`). Each `DownloadDataLoaded` exposes `image` lazily — using the cached
local bitmap for imports, else the remote poster (`DownloadFragment.kt:94`).

### Refresh-all download (`refreshInternal`, `DownloadViewModel.kt:186`)

Triggered through `DownloadFileWorkManager.refreshAll`. It selects cards that are not
imports, not already downloading, and `< 90%` complete, marks them `IsPending`, and
re-enqueues each via `BookDownloader2.downloadWorkThread(card)`.

---

## 12. Migration (id change mid-download)

When a re-loaded novel's name/author changes, its derived id changes. To avoid orphaning
data, `migrateKeys(from, to, oldName, newName)` (`BookDownloader2.kt:1406`) copies
`DOWNLOAD_TOTAL/FOLDER/EPUB_SIZE/OFFSET`, history/bookmark keys, and EPUB reading-position
keys to the new id, `copyAllData` (`:285`) copies the files dir, and the old novel is
deleted. This runs under `migrationNovelMutex` (`:1468`) from `downloadWorkThread(card)`
(`:1504`) and from `getNewTotalChapters` (`:1252`).

---

## Control-flow summary

```
UI (download button) ─┐
Notification button ──┤→ addPendingAction / download()
                      │
        DownloadFileWorkManager.startDownload  (APPEND, unique work)
                      │  (payload stashed in workData, only int key in Data)
                      ▼
        DownloadFileWorkManager.doWork ──► popWork ──► BookDownloader2.downloadWorkThread(...)
                      │
   ┌──────────────────┼──────────────────────────────┐
   ▼                  ▼                               ▼
 StreamResponse    EpubResponse / Uri              PDF Uri
 per-chapter loop  whole-file stream copy          page→chapter loop
   │  consumeAction (pause/resume/stop) gate at each iteration
   │  downloadIndividualChapter → /<api>/<author>/<name>/<index>.txt (filesDir)
   ▼
 changeDownload(id){…} ──► downloadProgressChanged Event ──► DownloadViewModel.progressChanged
   │                   └──► NotificationHelper.createNotification (id = novel id)
   ▼
 (read) turnToEpub ──► epublib EpubWriter ──► SafeFile (Downloads/Epub/<name>.epub via SAF)
```
