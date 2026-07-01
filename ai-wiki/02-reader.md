# Reader Module — Business Logic

The in-app novel reader: the screen that displays chapter text, tracks reading
progress, lets the user tune typography/theme, and reads the book aloud via TTS.

## Key files

| File | Role |
|------|------|
| `app/src/main/java/com/lagradost/quicknovel/ReadActivity2.kt` | The `Activity`. Owns the views, animates the chrome, drives the `RecyclerView`, observes all `ViewModel` state, translates scroll geometry into reading position. |
| `app/src/main/java/com/lagradost/quicknovel/ReadActivityViewModel.kt` | All business logic: book loading, chapter paging/caching, progress persistence, settings (as preference-backed properties), translation, and the TTS worker thread. |
| `app/src/main/java/com/lagradost/quicknovel/ui/TextAdapter.kt` | `RecyclerView.Adapter` that renders `SpanDisplay` items; holds the live `TextConfig` (font/size/color/bg/padding) and the per-line TTS highlight. Defines `ScrollIndex`, `ScrollVisibilityIndex`, `TextVisualLine`. |
| `app/src/main/java/com/lagradost/quicknovel/TTSHelper.kt` | Defines `SpanDisplay` hierarchy, `ChapterStartSpanned`, `TTSHelper.TTSStatus`, `TTSHelper.TTSLine`, `TTSHelper.TTSActionType`, text->span/tts parsing. |
| `app/src/main/java/com/lagradost/quicknovel/FontAdapter.kt` | Font-picker list adapter. |
| `app/src/main/res/layout/read_main.xml` | The reader layout (chrome, RecyclerView, overlay). |
| `app/src/main/res/layout/read_bottom_settings.xml` | The settings bottom sheet (`ReadBottomSettingsBinding`). |
| `app/src/main/java/com/lagradost/quicknovel/DataStore.kt` | `EPUB_*` preference-key constants. |
| `app/src/main/java/com/lagradost/quicknovel/ui/ReadingType.kt`, `OrientationType.kt` | Reading-mode and orientation enums. |

The `Activity` is `ReadActivity2`; the binding class is `ReadMainBinding`
(inflated `ReadActivity2.kt:697`). A static `WeakReference` `readActivity`
(`ReadActivity2.kt:84-92`) exposes the current instance to color-picker callbacks.

---

## 1. Loading a book — downloaded EPUB vs live stream

Entry point: `ReadActivityViewModel.init(intent, context)` (`ReadActivityViewModel.kt:1187`),
called from `ReadActivity2.onCreate` at `ReadActivity2.kt:702`.

The source type is decided from the `Intent` (`ReadActivityViewModel.kt:1194-1207`):

- `isFromEpub = intent.type != "quickstream"`.
- **EPUB (downloaded)** — opens a file descriptor via `contentResolver.openFileDescriptor(data, "r")`,
  wraps it in `AndroidZipFile`, parses lazily with `EpubReader().readEpubLazy(...)`,
  and wraps the result in **`RegularBook`** (`ReadActivityViewModel.kt:295`).
- **Live stream** — reads the intent's input stream as JSON into `QuickStreamData`
  (`DataStore.mapper.readValue(...)`) and wraps it in **`QuickBook`**
  (`ReadActivityViewModel.kt:219`).

Both implement the abstract **`AbstractBook`** (`ReadActivityViewModel.kt:165`),
which is the polymorphic contract the rest of the reader uses:
`size()`, `title()`, `getChapterTitle(index)`, `getLoadingStatus(index)`,
`getChapterData(index, reload)`, `expand(last)`, `canReload`, `poster()`, `author()`.
`book` is a `lateinit var` on the ViewModel (`ReadActivityViewModel.kt:463`).

Differences that matter to business logic:

| Aspect | `QuickBook` (live stream) | `RegularBook` (EPUB) |
|--------|--------------------------|----------------------|
| `canReload` | `true` (`:228`) | `false` (`:326`) |
| Chapter list | `data.data` array of `ChapterData` | flattened TOC (`allTocReferences`, `:296`); falls back to spine references for corrupt EPUBs (`:314-323`) |
| `getChapterData` | network fetch via `ctx.getQuickChapter(...)` (`:246`) | reads spine resources between TOC anchors and rewrites `<img>`/`<image>` paths (`:357-405`) |
| `getLoadingStatus` | the chapter URL (shown while loading) | `null` |
| `expand` | can append a "next chapter" by scraping a Next link (Reddit-style, `:256`) | always `false` (`:429`) |

Because a stream book can grow, the code never assumes a fixed max chapter count;
out-of-bounds reads trigger `book.expand(...)` under `chapterExpandMutex`
(`ReadActivityViewModel.kt:821-836`).

After the book parses, `init(book, context)` (`ReadActivityViewModel.kt:1293`):
- posts the title to `_title` (drives the toolbar — see §5),
- builds the **Markwon** renderer with HTML + Coil image plugins (`:1322-1336`),
- seeds chapter titles via `updateChapters()`.

The reader then restores the saved position (see §4) and calls
`updateIndexAsync(loadedChapterIndex, ...)` to load the first chapters.

### Loading status / failure UI
`_loadingStatus: MutableLiveData<Resource<Boolean>>` (`ReadActivityViewModel.kt:505`)
drives a 3-state screen observed at `ReadActivity2.kt:954-993`:
- `Resource.Loading` → shows `binding.readLoading` + `binding.loadingText` (the URL).
- `Resource.Success` → fades in `binding.readNormalLayout`.
- `Resource.Failure` → shows `binding.readFail` + `binding.failText`.

---

## 2. Chapters: paging, caching, and rendering

### Data model
A loaded chapter becomes a **`LiveChapterData`** (`ReadActivityViewModel.kt:436`)
holding the rendered `Spanned`, the `TextSpan` list, the original (untranslated)
copies, the title, raw text, and **lazy** `ttsLines` (`:452`, parsed on demand by
`ttsParseText`). The on-screen list is a `List<SpanDisplay>` — `ChapterStartSpanned`
(header), `TextSpan` (paragraph), `LoadingSpanned`, `FailedSpanned`,
`ChapterLoadSpanned`/`ChapterOverscrollSpanned` (next/prev buttons). `SpanDisplay`
is defined in `TTSHelper.kt:346`.

### Cache + concurrency
- `chapterData: HashMap<Int, Resource<LiveChapterData>?>` (`ReadActivityViewModel.kt:577`) — the per-index cache.
- `loading`, `requested`, `hasExpanded` `HashSet`s guard duplicate work.
- `chapterMutex` and `chapterExpandMutex` (`:571-572`) and `markwonMutex` (`:795`) serialize access.

`loadIndividualChapter(index, reload, notify, reTranslate, postLoading)`
(`ReadActivityViewModel.kt:798`) is the single chapter loader: it marks loading,
expands the book if the index is past the end, fetches text via
`book.getChapterData`, pre-parses HTML (`preParseHtml(text, authorNotes)`),
renders with Markwon, resolves image bitmaps, optionally translates (§7), and
stores a `Resource.Success(LiveChapterData)`.

### Preloading window
The visible chapter is `currentIndex` (`ReadActivityViewModel.kt:580`). Around it a
sliding window is kept loaded:
- `chapterPaddingBottom = 1` / `chapterPaddingTop = 2` (`:588,592`), with smaller
  `initPaddingBottom/Top = 1` for the very first load.
- `chapterPaddingTop` grows dynamically (up to 10) when chapters are tiny, in
  `onScroll(...)` (`:656`).
- `updateIndex(index)` (`:642`) loads `index-bottom .. index+top` if not already requested.

### Building the on-screen list
`updateReadArea(seekToDesired)` (`ReadActivityViewModel.kt:732`) assembles the
`ArrayList<SpanDisplay>` for the current reading mode and posts it through
`_chapterData` as a **`ChapterUpdate(data, seekToDesired)`** (`:457`, `:785`).
The mode is `readerType` (see `ReadingType`, `ui/ReadingType.kt`):
- `DEFAULT` / `INF_SCROLL` — emits every chapter in the padding window, each
  prefixed by a `ChapterStartSpanned` header (`:738-749`).
- `BTT_SCROLL` (button scroll) — current chapter plus prev/next **button** rows
  (`chapterIdxToSpanDisplayNextButton`, `:752-766`).
- `OVERSCROLL_SCROLL` — current chapter plus prev/next overscroll rows; flinging
  past the edge advances the chapter (see §3).

### Rendering on the Activity side
`observe(viewModel.chapter)` (`ReadActivity2.kt:1107`):
- caches `chapter.data` into `cachedChapter` (used by `scrollToDesired`),
- if `chapter.seekToDesired` → `textAdapter.submitIncomparableList(...)` then
  `scrollToDesired()`; otherwise `textAdapter.submitList(...)`,
- posts `Resource.Success(true)` to hide the loading overlay.

`chapterIdxToSpanDisplay(index)` (`ReadActivityViewModel.kt:673`) maps a cache entry
to display rows (loading spinner / failure / the spans).

### Manual chapter jump
The chapter list dialog is built in `observe(viewModel.chaptersTitles)`
(`ReadActivity2.kt:861`) wired to `binding.readActionChapters`; selecting an item
calls `viewModel.seekToChapter(which)` (`ReadActivityViewModel.kt:1719`), which stops
TTS, sets loading, loads around the target, persists the new position, and posts a
seek update. `reloadChapter()` (`:612`) re-fetches the current chapter (only when
`book.canReload`; the button visibility is gated by `viewModel.canReload()` at
`ReadActivity2.kt:1519`).

---

## 3. Scrolling and how the reader knows "where you are"

The text is a vertical `LinearLayoutManager` `RecyclerView`, `binding.realText`
(`@+id/real_text`), set up at `ReadActivity2.kt:995-1104`. Two zero-height marker
views, `@+id/read_top_item` and `@+id/read_bottom_item`, bracket it so the code can
measure the visible window in screen coordinates.

- `getTopY()` / `getBottomY()` (`ReadActivity2.kt:320-335`) compute the visible band
  in window coordinates; `getBottomY()` subtracts the overlay height when the
  clock/battery overlay is shown.
- `getAllLines()` (`:355`) flattens every visible adapter item into
  `TextVisualLine`s (char-precise line geometry from `TextAdapter`).
- `postLines(lines)` (`:364`) packages first/last in memory, first fully visible,
  first fully visible *under the top bar*, and last half-visible into a
  **`ScrollVisibilityIndex`** and calls `viewModel.onScroll(...)`.
- The scroll listener (`:1040-1102`) calls `onScroll()` on settle/idle and also
  enforces the TTS scroll-lock (`lockTop`/`lockBottom`, see §6) and overscroll.

`ReadActivityViewModel.onScroll(visibility)` (`ReadActivityViewModel.kt:651`):
- updates the dynamic padding,
- `desiredTTSIndex = firstFullyVisibleUnderLine` (where TTS would begin),
- `changeIndex(firstFullyVisible.toScroll())` → updates `desiredIndex`,
  `currentIndex`, the chapter title, and persists keys,
- triggers `updateReadArea()` if the chapter changed and preloads neighbors.

### Seeking back to a position
`scrollToDesired()` (`ReadActivity2.kt:394`) finds the adapter position matching
`viewModel.desiredIndex` (matching `index` + `innerIndex`), uses
`scrollToPositionWithOffset`, then char-seeks within the block by scrolling to the
`TextVisualLine` whose `endChar >= desired.char`. `postDesired(view)` (`:567`)
re-applies the desired index after layout changes (rotation, font/size change).

### Overscroll chapter switching
For `ReadingType.OVERSCROLL_SCROLL`, `currentOverScroll` (`ReadActivity2.kt:646-678`)
accumulates from touch deltas when the list can't scroll further; crossing ±0.9
calls `viewModel.seekToChapter(currentIndex ± 1)`. A progress bar in the
`SingleOverscrollChapterBinding` row is updated via `setProgressOfOverscroll(...)`.

### Volume-key navigation
`onKeyDown` (`ReadActivity2.kt:232`): when the bottom bar is hidden and
`viewModel.scrollWithVolume` is on, Volume Down/Up page the text by a screen; when
TTS is running the same keys call `forwardsTTS()` / `backwardsTTS()`.

---

## 4. Reading progress: tracking and persistence

The position type is **`ScrollIndex(index, innerIndex, char)`**
(`ui/TextAdapter.kt:85`): `index` = chapter, `innerIndex` = text block within the
chapter (derivable from `char`), `char` = character offset within the chapter.
The current target is `viewModel.desiredIndex` (`ReadActivityViewModel.kt:561`).

`changeIndex(scrollIndex, alsoTitle)` (`ReadActivityViewModel.kt:1675`) is the single
mutator: sets `desiredIndex`, `currentIndex`, optionally posts the chapter subtitle,
and **throttles persistence** — `setScrollKeys` is only called if >200 ms since the
last write (`lastScrollMs`, `:1686`) to avoid scroll lag. The pending value is kept
in `lastChangeIndex` and flushed on `leftApp()`/`onCleared()`.

`setScrollKeys(scrollIndex)` (`ReadActivityViewModel.kt:1692`) writes, all keyed by
`book.title()` (constants in `DataStore.kt`):
- `EPUB_CURRENT_POSITION_READ_AT` (timestamp, keyed `"title/index"`),
- `EPUB_CURRENT_POSITION_SCROLL_CHAR` (the char offset),
- `EPUB_CURRENT_POSITION` (the chapter index),
- `EPUB_CURRENT_POSITION_CHAPTER` (the chapter title string).

**Restore on open** (`ReadActivityViewModel.kt:1223-1270`): tries to match the saved
chapter *title* (`EPUB_CURRENT_POSITION_CHAPTER`) first, falling back to the saved
`EPUB_CURRENT_POSITION` index, then `0`; then restores the char via
`EPUB_CURRENT_POSITION_SCROLL_CHAR`, converts to `innerIndex` with
`innerCharToIndex(...)`, and calls `changeIndex(ScrollIndex(...))` + `updateReadArea(seekToDesired = true)`.

**Lifecycle**: `onResume`/`onPause` (`ReadActivity2.kt:576-584`) call
`viewModel.resumedApp()` / `leftApp()`. `leftApp()` flushes keys and records
`leftAppAt`; `resumedApp()` re-seeks if the desired index changed while away
(e.g. TTS advanced in the background). `onCleared()` (`:1769`) flushes keys, releases
the TTS session and translator.

---

## 5. Title / chapter header display and update

Two distinct title surfaces:

1. **The toolbar (top chrome)** — `binding.readToolbar`, a `MaterialToolbar`
   (`@+id/read_toolbar`) inside the `AppBarLayout` `binding.readToolbarHolder`
   (`@+id/read_toolbar_holder`), `read_main.xml:483-505`.
   - **Book title** → `Toolbar.title`, set by `observe(viewModel.title)` at
     **`ReadActivity2.kt:853-855`** (`binding.readToolbar.title = title`).
     Source: `_title` posted in `init(book, context)` (`ReadActivityViewModel.kt:1295`).
   - **Chapter title** → `Toolbar.subtitle`, set by `observe(viewModel.chapterTile)`
     at **`ReadActivity2.kt:857-859`**
     (`binding.readToolbar.subtitle = title.asString(...)`).
     Source: `_chapterTile` posted from `changeIndex` (`:1677`) and `seekToChapter`
     (`:1745`) — so the subtitle updates live as you scroll between chapters.
   - The back arrow is wired at `ReadActivity2.kt:724-729`.

2. **The per-chapter in-text header** — each chapter starts with a
   `ChapterStartSpanned` row rendered by `TextAdapter` (the bold serif
   `@+id/read_title_text` styling lives in the item layout / `single_*` bindings).
   This is independent of the toolbar subtitle.

The chapter-title list (`chaptersTitlesInternal`, posted via `_chaptersTitles`,
`ReadActivityViewModel.kt:559-568`) feeds both the subtitle and the chapter-select
dialog.

---

## 6. Typography, theme, and background

All reading settings are **preference-backed properties** on the ViewModel. Two
delegate types are used (`ReadActivityViewModel.kt:112-163`):
- `PreferenceDelegate` — cached read/write to `DataStore`, no observation.
- `PreferenceDelegateLiveView` — same, plus pushes every change to a paired
  `MutableLiveData<...>Live` so the Activity can react.

The Activity observes each `…Live` and forwards to the adapter's `TextConfig`
(`ui/TextAdapter.kt:167-310`). The config carries bitmask flags
`CONFIG_COLOR`/`CONFIG_FONT`/`CONFIG_SIZE`/`CONFIG_FONT_BOLD`/`CONFIG_FONT_ITALIC`/
`CONFIG_BG_COLOR`/`CONFIG_PADDING`. Each `TextConfig.changeXxx(...)` returns `true`
only if the value actually changed; the Activity then calls
`updateTextAdapterConfig()` (`ReadActivity2.kt:553`), which `notifyDataSetChanged()`
and re-applies `updateOtherTextConfig(...)` to the loading/battery/clock views.

| Setting | ViewModel property | Pref key (`DataStore.kt`) | Default | Activity observer |
|---------|--------------------|---------------------------|---------|-------------------|
| Font | `textFont` (`:1818`) | `EPUB_FONT` | `""` (system default) | `:803` → `changeFont` |
| Text size | `textSize` (`:1820`) | `EPUB_TEXT_SIZE` | `DEF_FONT_SIZE`=14 | `:790` → `changeSize` + `postDesired` |
| Text color | `textColor` (`:1851`) | `EPUB_TEXT_COLOR` | `#cccccc` | `:797` → `changeColor` |
| Background color | `backgroundColor` (`:1861`) | `EPUB_BG_COLOR` | `#292832` | `:747` → root/overlay bg + `changeBackgroundColor` |
| Vertical line padding | `textVerticalPadding` (`:1856`) | `EPUB_TEXT_VERTICAL_PADDING` | `7.5` | `:755` |
| Bionic reading | `bionicReading` (`:1828`) | `EPUB_TEXT_BIONIC` | `false` | `:762` |
| Text selectable | `isTextSelectable` (`:1836`) | `EPUB_TEXT_SELECTABLE` | `false` | `:767` |
| Horizontal padding | `paddingHorizontal` (`:1876`) | `EPUB_TEXT_PADDING` | `DEF_HORIZONTAL_PAD`=20 | `:734` → `updatePadding` |
| Vertical padding | `paddingVertical` (`:1881`) | `EPUB_TEXT_PADDING_TOP` | `DEF_VERTICAL_PAD`=0 | `:738` → `updatePadding` |
| Reading mode | `readerType` (`:1786`) | `EPUB_READER_TYPE` | `ReadingType.DEFAULT` | re-builds read area |
| Orientation | `orientation` (`:1844`) | `EPUB_LOCK_ROTATION` | `OrientationType.DEFAULT` | `:832` → `requestedOrientation` |
| Keep screen awake | `screenAwake` (`:1891`) | `EPUB_KEEP_SCREEN_ACTIVE` | `true` | `:783` → `FLAG_KEEP_SCREEN_ON` |
| Show clock | `showTime` (`:1871`) | `EPUB_HAS_TIME` | `true` | `:778` |
| Show battery | `showBattery` (`:1866`) | `EPUB_HAS_BATTERY` | `true` | `:773` |
| Scroll with volume keys | `scrollWithVolume` (`:1794`) | `EPUB_SCROLL_VOL` | `true` | read in `onKeyDown` |
| Author notes | `authorNotes` (`:1795`) | `EPUB_AUTHOR_NOTES` | `true` | toggling → `refreshChapters()` |

The adapter's initial `TextConfig` is constructed in `onCreate`
(`ReadActivity2.kt:705-722`) seeded from these properties, with
`defaultFont = binding.readText.typeface` (the serif default from `read_main.xml`).

**Theme/background colors** are applied at `ReadActivity2.kt:747-753`: the activity
root and the bottom overlay take the background color, and the adapter repaints the
text rows. The preset color swatches come from `R.array.readerBgColors` /
`R.array.readerTextColors`; the custom color picker (`ColorPickerDialog`) routes
through `onColorSelected` (`:170`) → `setBackgroundColor` / `setTextColor`.
`updateImages()` (`:189`) keeps the swatch checkmarks in sync.

**Fonts**: `showFonts()` (`ReadActivity2.kt:600`) lists `systemFonts` plus a "default"
entry in a `RecyclerView` using **`FontAdapter`** (`FontAdapter.kt:17`). Each row
previews the typeface (`Typeface.createFromFile`), the current font is highlighted
via the `checked` index (looked up against `EPUB_FONT`), and selecting one sets
`viewModel.textFont = file.file?.name ?: ""`.

All of the above sliders/toggles live in the settings bottom sheet opened by
`binding.readActionSettings` (`ReadActivity2.kt:1135`), a `BottomSheetDialog` inflating
`ReadBottomSettingsBinding` with four tabs: Settings / Translation / Voice / Text
(`:1142-1162`).

---

## 7. Translation (relevant because it mutates the rendered text)

Per-book ML settings (`MLSettings`, `ReadActivityViewModel.kt:1935`) select a
from/to language and online vs offline (Google ML Kit) translation. During
`loadIndividualChapter`, `translate(...)` (`:978`) replaces the rendered spans with
translated ones (cached to `cacheDir` as `ml_<hash>...txt`). `originalRendered` /
`originalSpans` are retained so `reTranslateChapter` / `applyMLSettings` can
re-translate without re-fetching. This is wired in the settings sheet's Translation
tab (`:1318-1411`).

---

## 8. UI chrome: show/hide of top and bottom bars

There is no menu button — tapping the text toggles the chrome. Click listeners on
`realText`, `readToolbar`, and `readerLinContainer` all call
`viewModel.switchVisibility()` (`ReadActivity2.kt:927-937`), which flips
`_bottomVisibility` (`ReadActivityViewModel.kt:554`).

`observe(viewModel.bottomVisibility)` (`ReadActivity2.kt:939-951`):
- `true` → `showSystemUI()` (`:132`): shows system bars, slides
  `binding.readToolbarHolder` down and `binding.readerBottomViewHolder` up
  (200 ms `ObjectAnimator` on `translationY`).
- `false` → `hideSystemUI()` (`:94`): hides system bars (immersive,
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, cutout short-edges), slides both bars
  off-screen and sets them `isVisible = false`; then re-runs `updateTTSLine(...)`
  to fix the TTS lock band.

The **top chrome** is `binding.readToolbarHolder` (AppBarLayout) containing the
toolbar; its min height is `actionBarSize + statusBar` (`ReadActivity2.kt:703-704`,
`fixPaddingStatusbar` at `:732`).

The **bottom chrome** is `binding.readerBottomViewHolder` (`@+id/reader_bottom_view_holder`),
which contains two mutually-exclusive bars:
- `binding.readerBottomView` (`@+id/reader_bottom_view`) — normal actions: rotate,
  TTS-start, chapters, settings.
- `binding.readerBottomViewTts` (`@+id/reader_bottom_view_tts`) — TTS transport:
  back, stop, pause/play, forward.
- plus `binding.ttsStopTime` (`@+id/tts_stop_time`) — sleep-timer countdown text.

A bottom **overlay** `binding.readOverlay` (`@+id/read_overlay`) holds the
`TextClock` (`@+id/read_time_clock`) and battery `TextView` (`@+id/read_battery`),
independent of the chrome and toggled by `showTime`/`showBattery`. The battery value
comes from a `BroadcastReceiver` `mBatInfoReceiver` (`ReadActivity2.kt:289-304`).

---

## 9. TTS (read aloud) integration

### Status model and the "is TTS active" flags
- **`TTSHelper.TTSStatus`** enum (`TTSHelper.kt:418`): `IsRunning`, `IsPaused`, `IsStopped`.
- The **single source of truth** is `ReadActivityViewModel.currentTTSStatus`
  (`ReadActivityViewModel.kt:1352`); its setter (`:1354`) starts the worker on
  Stopped→Running, posts to `_ttsStatus`, and updates the backing
  `_currentTTSStatus`.
- **Observable** state: `viewModel.ttsStatus: LiveData<TTSHelper.TTSStatus>`
  (`ReadActivityViewModel.kt:527`). The Activity observes it at
  **`ReadActivity2.kt:907-925`** and derives
  **`val isTTSRunning = status != TTSHelper.TTSStatus.IsStopped`** (`:908`) — this is
  the canonical "TTS is active (running or paused)" flag on the UI side. There is
  also `viewModel.isTTSRunning()` (`ReadActivityViewModel.kt:1412`) which is
  narrower: `currentTTSStatus == IsRunning` only.
- `viewModel.ttsLine: LiveData<TTSHelper.TTSLine?>` (`:531`) — the currently spoken
  line (non-null while speaking), observed at `ReadActivity2.kt:849`.
- `viewModel.ttsTimeRemaining` (`:1901`) — sleep-timer countdown, observed at `:1124`.

The status observer at `ReadActivity2.kt:907` is exactly where chrome swaps for TTS:
```
binding.readerBottomView.isGone = isTTSRunning       // hide normal bottom bar
binding.readerBottomViewTts.isVisible = isTTSRunning // show transport bar
binding.ttsActionPausePlay.setImageResource(... per status ...)
```

### Controls and actions
Transport buttons are wired in `onCreate` (`ReadActivity2.kt:811-830`):
`ttsActionPausePlay → pausePlayTTS()`, `ttsActionStop → stopTTS()`,
`readActionTts → startTTS()`, `ttsActionForward → forwardsTTS()`,
`ttsActionBack → backwardsTTS()`. External actions (notification / media buttons)
arrive as `TTSHelper.TTSActionType` (`TTSHelper.kt:424`) through
`parseAction(input)` (`ReadActivity2.kt:306` → `ReadActivityViewModel.kt:1637`).

### The TTS worker
`startTTSWorker()` (`ReadActivityViewModel.kt:1419`) starts a foreground
`TTSNotificationService`, which runs `startTTSThread()` (`:1423`). That coroutine
locks `ttsThreadMutex`, registers the `TTSSession`, applies speed/pitch, then loops
chapter-by-chapter/line-by-line: it posts each spoken line to `_ttsLine`, honors
`pendingTTSSkip` for forward/back, advances across chapters, handles the sleep timer
(`ttsTimer`/`ttsTimeRemaining`), and — when the app is backgrounded
(`!isInApp`) — also advances `desiredIndex` via `changeIndex(..., alsoTitle = false)`
so returning to the app scrolls to the spoken position (`:1544-1554`). TTS settings:
`ttsSpeed`/`ttsPitch` (`:1802-1814`), `ttsTimer` (`EPUB_SLEEP_TIMER`, `:1897`),
`ttsLock` (`EPUB_TTS_LOCK`, `:1796`). Voice/language pickers are in the settings sheet
(`ReadActivity2.kt:1413-1516`). `ttsSession` is created in `initTTSSession` (`:1344`).

### TTS visual highlight and scroll-lock
`observeNullable(viewModel.ttsLine)` → `updateTTSLine(line)` (`ReadActivity2.kt:442`):
- pushes the line into `textAdapter.updateTTSLine(line)` and refreshes visible rows
  so the spoken sentence is highlighted (`TextAdapter.updateTTSLine`, `TextAdapter.kt:340/698`);
- if `viewModel.ttsLock` is on, computes `lockTop`/`lockBottom` (`:507-510`) so the
  user can't scroll the active line out of view; the scroll listener
  (`ReadActivity2.kt:1047-1059`) clamps scrolling to that band; if the line is off
  the loaded range it scrolls/seeks back to it.

Stopping: `kill()` (`ReadActivity2.kt:282`) cancels the TTS notification and finishes;
`onDestroy` (`:680`) calls `viewModel.stopTTS()`.

---

## Appendix — "Hide the title while TTS is active" (for the upcoming feature)

Everything needed to gate the title on TTS state:

**The title views (what to hide):**
- Book title: `binding.readToolbar.title` — set at **`ReadActivity2.kt:853-855`**
  (`observe(viewModel.title)`). View = `MaterialToolbar` `binding.readToolbar`
  (`@+id/read_toolbar`, `read_main.xml:491`).
- Chapter title: `binding.readToolbar.subtitle` — set at **`ReadActivity2.kt:857-859`**
  (`observe(viewModel.chapterTile)`).
- Container (if hiding the whole bar instead of just text):
  `binding.readToolbarHolder` (`AppBarLayout`, `@+id/read_toolbar_holder`).

**The "TTS active" signal (what to condition on):**
- UI-side flag already computed: `val isTTSRunning = status != TTSHelper.TTSStatus.IsStopped`
  at **`ReadActivity2.kt:908`**, inside `observe(viewModel.ttsStatus)` (`:907-925`).
  This block is the natural place to also clear/restore the title.
- Underlying LiveData: `viewModel.ttsStatus` (`ReadActivityViewModel.kt:527`),
  enum `TTSHelper.TTSStatus` (`TTSHelper.kt:418`).
- Alternative accessors: `viewModel.isTTSRunning()` (running-only,
  `ReadActivityViewModel.kt:1412`) and `viewModel.currentTTSStatus` (`:1352`).

**Gotcha:** the title/subtitle observers (`:853`, `:857`) re-assign the toolbar text
whenever `viewModel.title` / `viewModel.chapterTile` post a new value (e.g. scrolling
across a chapter while TTS plays). A correct implementation must make those two
observers also respect the TTS state (or re-apply the hide inside them), not only the
`ttsStatus` observer — otherwise the subtitle will reappear on the next chapter change.
