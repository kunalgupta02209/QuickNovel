# Data & Persistence

This module is the single source of truth for everything QuickNovel remembers between
sessions: reading history, bookmarks, per-book reading progress, download metadata, and
all user settings. There is **no SQLite/Room database** — every persisted value is stored
as a key in Android `SharedPreferences`, with complex objects serialized to JSON via
Jackson.

## Core symbols

| Symbol | File | Purpose |
| --- | --- | --- |
| `object DataStore` | `app/src/main/java/com/lagradost/quicknovel/DataStore.kt:97` | The key/value engine: get/set/remove keys, JSON (de)serialization, `Editor` batching. |
| `BaseApplication.Companion` | `app/src/main/java/com/lagradost/quicknovel/BaseApplication.kt:33` | Static, context-free facade over `DataStore` (`getKey`, `setKey`, `getKeys`, `removeKey`). |
| `object BackupUtils` | `app/src/main/java/com/lagradost/quicknovel/util/BackupUtils.kt:34` | Full-state JSON backup and restore. |
| `data class ResultCached` | `app/src/main/java/com/lagradost/quicknovel/util/ResultCached.kt:9` | The persisted "card" used for both history and bookmarks. |
| `DownloadFragment.DownloadData` | `app/src/main/java/com/lagradost/quicknovel/ui/download/DownloadFragment.kt:43` | Persisted download metadata. |
| `object SettingsHelper` | `app/src/main/java/com/lagradost/quicknovel/util/SettingsHelper.kt:8` | Reads a handful of UI-format settings out of the *default* prefs. |

---

## 1. The storage model: Jackson over SharedPreferences

### Two distinct preference stores

`DataStore` deliberately separates **app data** from **app settings** into two different
`SharedPreferences` files (`DataStore.kt:117-131`):

- **App data** — `getSharedPrefs()` → `getSharedPreferences("rebuild_preference", MODE_PRIVATE)`.
  The constant is `PREFERENCES_NAME = "rebuild_preference"` (`DataStore.kt:13`).
  Holds history, bookmarks, reading progress, download metadata.
- **App settings** — `getDefaultSharedPrefs()` → `PreferenceManager.getDefaultSharedPreferences(this)`.
  The standard Android settings file driven by the `PreferenceScreen` XML; holds user
  preferences (rating format, grid format, theme, etc.).

`editor(context, isEditingAppSettings)` (`DataStore.kt:99`) chooses between the two when
producing an `Editor`.

### The Jackson mapper

A single shared `JsonMapper` instance is built once (`DataStore.kt:105-115`):

```kotlin
val mapper: JsonMapper = JsonMapper.builder().addModule(
    KotlinModule.Builder()
        .withReflectionCacheSize(512)
        .configure(KotlinFeature.NullToEmptyCollection, false)
        .configure(KotlinFeature.NullToEmptyMap, false)
        .configure(KotlinFeature.NullIsSameAsDefault, false)
        .configure(KotlinFeature.SingletonSupport, false)
        .configure(KotlinFeature.StrictNullChecks, false)
        .build()
).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()
```

Two configuration choices matter for forward/backward compatibility:

- `FAIL_ON_UNKNOWN_PROPERTIES = false` — old app versions can read JSON written by newer
  versions that added fields; unknown JSON keys are silently dropped.
- `NullToEmptyCollection/Map = false`, `NullIsSameAsDefault = false` — nullable fields stay
  null rather than being coerced, so `null` round-trips faithfully.

### How a value is stored

`setKey` (`DataStore.kt:171-179`) serializes **any** value `T` to a JSON string and stores
it as a single `String` preference:

```kotlin
fun <T> Context.setKey(path: String, value: T) {
    getSharedPrefs().edit { putString(path, mapper.writeValueAsString(value)) }
}
```

Even an `Int` or `Long` is stored as its JSON text (e.g. `5`, `"history.../3"`). Reading
back, `getKey` (`DataStore.kt:194-201`) pulls the string and deserializes with the reified
type via `String.toKotlinObject()` (`DataStore.kt:185-191`). On any deserialization
exception `getKey` returns `null` (defensive — corrupt/legacy JSON simply disappears rather
than crashing).

> Note the asymmetry: `setKey` swallows errors and logs via `logError` (`DataStore.kt:176`);
> `getKey` swallows errors and returns the default/null. Nothing throws to the caller.

### Folder-namespaced keys

Most domain data is "foldered". A logical key is `"<folder>/<path>"`, built by
`getFolderName(folder, path)` (`DataStore.kt:125-127`) → `"${folder}/${path}"`. The
overloads `setKey(folder, path, value)` (`DataStore.kt:181`) and
`getKey(folder, path)` (`DataStore.kt:216`) wrap this. The folder is a prefix string
constant (see the key table below) and the `path` is almost always a stringified novel id
or a book title.

Enumerating a folder is done by prefix scan: `getKeys(folder)` (`DataStore.kt:133-135`)
returns every preference key that `startsWith(folder)`. This is how the history and download
lists are built (iterate the folder, `getKey` each entry). `removeKeys(folder)`
(`DataStore.kt:163-169`) deletes a whole folder.

### Batched `Editor`

`data class Editor` (`DataStore.kt:65-95`) wraps a raw `SharedPreferences.Editor` for bulk
writes (used by restore). `setKeyRaw` (`DataStore.kt:69-82`) writes **native** typed values
(Boolean/Int/String/Float/Long/StringSet) directly — *not* JSON — because restore replays
the raw SharedPreferences map. The comment at `DataStore.kt:64` explains the motive:
calling `apply()` per key is memory-expensive, so callers batch and call `apply()` once;
`apply()` also forces `System.gc()` (`DataStore.kt:91-94`).

### Static facade

UI/view-model code rarely holds a `Context`, so `BaseApplication` exposes the same API as
companion functions over a `WeakReference<Context>` (`BaseApplication.kt:38-95`). For
example `setKey(folder, path, value)` (`BaseApplication.kt:53`) and
`getKey<T>(folder, path)` (`BaseApplication.kt:73`) just forward to the captured app
context. This is why call sites like `HistoryViewModel` can call `getKeys(...)` with no
context argument.

---

## 2. The persisted keys and data classes

All folder/key constants are declared at the top of `DataStore.kt:13-63`.

### History — `HISTORY_FOLDER = "result_history"` (`DataStore.kt:62`)

- **Value type:** `ResultCached` (`ResultCached.kt:9-31`).
- **Path:** the novel's load id, `loadId.toString()`.
- **Written by:** `ResultViewModel.addToHistory()` (`ResultViewModel.kt:475-496`) —
  `setKey(HISTORY_FOLDER, loadId.toString(), ResultCached(...))`. Only written when the
  result was actually loaded from the network (`if (!isGetLoaded) return`,
  `ResultViewModel.kt:477`), never from a cached preview.
- **Read by:** `HistoryViewModel.updateHistory()` (`HistoryViewModel.kt:21-33`) — scans
  `getKeys(HISTORY_FOLDER)`, `getKey<ResultCached>` for each, sorts by `-cachedTime`
  (most-recent first).
- **Deleted by:** `removeKey(HISTORY_FOLDER, card.id.toString())`
  (`HistoryViewModel.kt:104`) or `removeKeys(HISTORY_FOLDER)` for clear-all
  (`HistoryViewModel.kt:51`).

### Bookmarks — two parallel folders

Bookmarks are split across two keys, both pathed by novel id:

- **`RESULT_BOOKMARK = "result_bookmarked"` (`DataStore.kt:60`)** — value type
  `ResultCached`; the bookmarked book's metadata card.
- **`RESULT_BOOKMARK_STATE = "result_bookmarked_state"` (`DataStore.kt:61`)** — value type
  `Int`; the `ReadType.prefValue` shelf (see enum below).

Written together by `ResultViewModel`:
- `bookmark(state)` (`ResultViewModel.kt:558-568`) writes the `Int` state via
  `setKey(RESULT_BOOKMARK_STATE, loadId.toString(), state)` then calls
  `updateBookmarkData()`.
- `updateBookmarkData()` (`ResultViewModel.kt:535-556`) writes the `ResultCached` card to
  `RESULT_BOOKMARK`. It guards against overwriting real data with cached preview data
  (`ResultViewModel.kt:537`).

Read back by `DownloadViewModel.loadAllData()` (`DownloadViewModel.kt:455-493`): it scans
`getKeys(RESULT_BOOKMARK_STATE)`, reads each `Int` state, then maps the key from the state
folder to the bookmark folder via
`key.replaceFirst(RESULT_BOOKMARK_STATE, RESULT_BOOKMARK)` (`DownloadViewModel.kt:469-472`)
and reads the matching `ResultCached`. Books are bucketed by `ReadType.prefValue` into the
shelves. The same pattern appears in `BookDownloader2.kt:1205-1245`.

Deleted by `DownloadViewModel.kt:266-267` (both folders for one id).

The **`ReadType` enum** (`app/src/main/java/com/lagradost/quicknovel/ui/ReadType.kt:6-16`)
defines the integer states stored in `RESULT_BOOKMARK_STATE`:

| `prefValue` | Name |
| --- | --- |
| 0 | `NONE` |
| 1 | `PLAN_TO_READ` |
| 2 | `DROPPED` |
| 3 | `COMPLETED` |
| 4 | `ON_HOLD` |
| 5 | `READING` |

`ResultCached` itself (`ResultCached.kt:9-31`) carries `source` (url), `name`, `apiName`,
`id`, `author`, `poster`, `tags`, `rating`, `totalChapters`, `cachedTime` (ms), and
`synopsis`. It overrides `hashCode()` to return `id` (`ResultCached.kt:24-26`). Two of its
properties are *derived live from the data store* rather than stored:
- `currentTotalChapters` (`ResultCached.kt:27-29`) re-reads the bookmark card to get a
  fresher chapter count.
- `lastChapterRead` (`ResultCached.kt:30`) reads `EPUB_CURRENT_POSITION` by book name.

### Reading progress — per-book, pathed by book title

These keys are written by the reader (`ReadActivityViewModel`) and the result screen, all
pathed by the **book title** (`book.title()` / `streamResponse.name`), not the numeric id:

| Constant | Key string | Value | Meaning |
| --- | --- | --- | --- |
| `EPUB_CURRENT_POSITION` (`DataStore.kt:54`) | `reader_epub_position` | `Int` | Current chapter index. |
| `EPUB_CURRENT_POSITION_CHAPTER` (`DataStore.kt:59`) | `reader_epub_position_chapter` | `String` | Current chapter name (used to relocate after a chapter list changes). |
| `EPUB_CURRENT_POSITION_SCROLL` (`DataStore.kt:55`) | `reader_epub_position_scroll` | `Int` | Scroll offset. |
| `EPUB_CURRENT_POSITION_SCROLL_CHAR` (`DataStore.kt:56`) | `reader_epub_position_scroll_char` | `Int` | Character-level scroll offset within the chapter. |
| `EPUB_CURRENT_POSITION_READ_AT` (`DataStore.kt:58`) | `reader_epub_position_read` | `Long` (ms) | Timestamp a chapter was marked read; pathed `"<name>/<index>"`. |

Key write/read sites:
- `ReadActivityViewModel` saves position on scroll: `setKey(EPUB_CURRENT_POSITION, book.title(), …)`
  (`ReadActivityViewModel.kt:1704, 1734, 1752`) and reads it back at load
  (`ReadActivityViewModel.kt:1223-1264`).
- `ResultViewModel.setReadChapter()` (`ResultViewModel.kt:169-188`) toggles a chapter's
  read marker by writing/removing `EPUB_CURRENT_POSITION_READ_AT` at path
  `"${streamResponse.name}/$index"`. `getChapterReadTime()` (`ResultViewModel.kt:159-167`)
  reads it back, and `hasReadChapter()` (`ResultViewModel.kt:155-157`) tests for presence.
- `ResultViewModel.kt:328-335` writes current chapter position when opening a chapter.

### Download metadata / state

| Constant | Key string | Value | Meaning |
| --- | --- | --- | --- |
| `DOWNLOAD_FOLDER` (`DataStore.kt:14`) | `downloads_data` | `DownloadFragment.DownloadData` | Per-download metadata card. |
| `DOWNLOAD_SIZE` (`DataStore.kt:15`) | `downloads_size` | `Int` | Chapters fetched so far. |
| `DOWNLOAD_TOTAL` (`DataStore.kt:16`) | `downloads_total` | `Int`/`Long` | Total chapters in the source. |
| `DOWNLOAD_EPUB_SIZE` (`DataStore.kt:18`) | `downloads_epub_size` | `Int` | Chapters present in the generated EPUB. |
| `DOWNLOAD_EPUB_LAST_ACCESS` (`DataStore.kt:19`) | `downloads_epub_last_access` | `Long` (ms) | Last time the EPUB was opened (for "last read" sorting). |

`DownloadData` (`DownloadFragment.kt:43-71`) is JSON-annotated with `@JsonProperty` on every
field (`source`, `name`, `author`, `posterUrl`, `rating`, `peopleVoted`, `views`,
`synopsis`, `tags`, `apiName`, `lastUpdated`, `lastDownloaded`). The explicit annotations
make field names stable in the serialized JSON regardless of obfuscation/refactor.

Written by `BookDownloader2`:
- `setSuffixData()` (`BookDownloader2.kt:1564-1590`) and `setPrefixData()`
  (`BookDownloader2.kt:1592-1625`) write the `DownloadData` card and `DOWNLOAD_TOTAL`.
- Progress counters: `setKey(DOWNLOAD_SIZE, id, count)` (`BookDownloader2.kt:449`),
  `setKey(DOWNLOAD_EPUB_SIZE, id, …)` (`BookDownloader2.kt:729, 799`).
- Cleanup on delete removes `DOWNLOAD_SIZE/TOTAL/EPUB_SIZE` (`BookDownloader2.kt:331-333`).

Read back by `DownloadViewModel` / `AnyAdapter` (e.g. `AnyAdapter.kt:239, 320` read
`DOWNLOAD_EPUB_SIZE`) and `BookDownloader2.kt:380, 424, 450, 1158` to compute download
progress and ETA. The live `DownloadDataLoaded` (`DownloadFragment.kt:73-108`) is the
in-memory, non-persisted view combining stored `DownloadData` with live `state`,
`downloadedCount`, `ETA`, etc.

There is **no persisted `DownloadState` enum value** — download run-state is reconstructed at
runtime from the counters (`DOWNLOAD_SIZE` vs `DOWNLOAD_TOTAL` vs `DOWNLOAD_EPUB_SIZE`) and
the in-memory `currentDownloads` set.

### Reader & sort/filter settings (app-data folder)

Many reader preferences live in the same `rebuild_preference` store, keyed by their bare
constant (no folder/path), e.g. `EPUB_TEXT_SIZE`, `EPUB_BG_COLOR`, `EPUB_TEXT_COLOR`,
`EPUB_FONT`, `EPUB_READER_TYPE`, `EPUB_TTS_SET_SPEED`, plus the result-screen sort/filter
toggles `RESULT_CHAPTER_SORT`, `RESULT_CHAPTER_FILTER_DOWNLOADED/BOOKMARKED/READ/UNREAD`
(`DataStore.kt:23-53`). `CURRENT_TAB = "current_tab"` (`DataStore.kt:63`) stores the last
open bottom-nav tab.

### True app settings (default prefs)

`SettingsHelper` (`SettingsHelper.kt:8-57`) reads a few display settings straight out of
`PreferenceManager.getDefaultSharedPreferences` using **R.string** key names defined in the
preferences XML (not the `DataStore` constants): `rating_format_key`
(`SettingsHelper.kt:11, 20`), `grid_format_key` (`SettingsHelper.kt:34`),
`download_format_key` (`SettingsHelper.kt:39`). These return formatted ratings
(`getRating`/`getRatingReview`) and grid/compact layout flags
(`getGridIsCompact`/`getDownloadIsCompact`). This is the only file in this module that
talks to the *settings* store directly rather than through `DataStore.getKey`.

---

## 3. Backup & restore

`BackupUtils` (`BackupUtils.kt:34-243`) serializes the **entire** contents of both
preference stores to a single JSON file, and restores them.

### Backup format

`BackupVars` (`BackupUtils.kt:38-45`) groups a flat preference map by native type into six
JSON-annotated maps: `_Bool`, `_Int`, `_String`, `_Float`, `_Long`, `_StringSet`.
`BackupFile` (`BackupUtils.kt:47-50`) wraps two `BackupVars`: `datastore` (the app-data
store) and `settings` (the default settings store).

### Writing a backup — `FragmentActivity.backup()` (`BackupUtils.kt:86-144`)

1. Pull every key/value via `getSharedPrefs().all` and `getDefaultSharedPrefs().all`
   (`BackupUtils.kt:93-94`).
2. Partition each map by runtime type into a `BackupVars` (`BackupUtils.kt:96-112`) — note
   it reads the **raw native** SharedPreferences values, so a JSON-encoded `ResultCached`
   lands in `_String` as its JSON text.
3. Serialize the `BackupFile` with the shared `mapper` (`BackupUtils.kt:121`) and write to a
   timestamped file `QN_Backup_<yyyy_MM_dd_HH_mm>.json` (`BackupUtils.kt:90`).
4. The output stream is created by `setupStream()` (`BackupUtils.kt:52-84`): on API ≥ Q it
   inserts a `MediaStore.Downloads` entry with MIME `application/json`; below Q it writes a
   `.json` file into the default directory via `SafeFile`.

### Restoring — `setUpBackup()` / `restore()` (`BackupUtils.kt:146-242`)

- `setUpBackup()` (`BackupUtils.kt:146-187`) registers an `OpenDocument` picker; when a file
  is chosen it `mapper.readValue<BackupFile>(input)` (`BackupUtils.kt:158`) and runs
  `restore(restoreSettings = true, restoreDataStore = true)` on a background `thread`, then
  `recreate()`s the activity so the restored state takes effect.
- `restore()` (`BackupUtils.kt:220-242`) calls `restoreMap()` for each of the six typed maps
  in both sections. `restoreMap()` (`BackupUtils.kt:209-218`) gets a batched `Editor`
  (choosing the settings vs data store via `isEditingAppSettings`) and replays each
  key/value with `setKeyRaw` (writing **native** types straight back), then a single
  `apply()`.
- `restorePrompt()` (`BackupUtils.kt:189-207`) launches the file picker with a broad list of
  acceptable MIME types.

Because backup/restore operate on the raw SharedPreferences key→value map, **everything**
described above (history, bookmarks, reading progress, download metadata, reader and app
settings) is captured and restored as one unit — there is no per-domain export.

---

## 4. How other modules read/write persisted state (summary)

| Module / file | Interaction |
| --- | --- |
| `ResultViewModel` | Writes history (`HISTORY_FOLDER`), bookmarks (`RESULT_BOOKMARK` + `RESULT_BOOKMARK_STATE`), reading position & read-markers (`EPUB_CURRENT_POSITION*`). |
| `HistoryViewModel` | Reads/clears the `HISTORY_FOLDER` folder. |
| `DownloadViewModel` / `AnyAdapter` | Reads bookmark shelves and download counters; updates `DOWNLOAD_EPUB_LAST_ACCESS`; deletes bookmark keys. |
| `BookDownloader2` | Writes/reads all `DOWNLOAD_*` keys; on rename, migrates every per-book key (download, history, bookmark, progress) from old id/name to new (`BookDownloader2.kt:1408-1464`). |
| `ReadActivityViewModel` | Persists/restores fine-grained reading position by book title. |
| `MainActivity` / `SearchFragment` | Enumerate `DOWNLOAD_FOLDER` to surface downloaded books. |
| `SettingsHelper` | Reads display-format settings from the **default** prefs store. |

All of these go through the `BaseApplication` static `getKey/setKey/getKeys/removeKey`
facade, which is the single funnel into `DataStore` and thus into Jackson-over-SharedPreferences.
