# TTS / "Read Aloud" Module — Business Logic

This document describes the business logic of the text-to-speech ("Read Aloud") feature in the
QuickNovel reader. It covers how read-aloud is started/paused/stopped, the state model, how text is
chunked and highlighted, chapter advancement, the media-session notification, and — importantly —
**which observable state tells the UI that TTS is currently active** so future features can react to it.

## Files involved

| File | Role |
| --- | --- |
| `app/src/main/java/com/lagradost/quicknovel/TTSHelper.kt` | Core types (`TTSStatus`, `TTSActionType`, `TTSLine`), the `TTSSession` engine wrapper, text-to-utterance parsing. |
| `app/src/main/java/com/lagradost/quicknovel/TTSNotificationService.kt` | Foreground `Service` that hosts the TTS playback loop and the media notification. |
| `app/src/main/java/com/lagradost/quicknovel/TTSNotifications.kt` | Builds the `MediaSessionCompat`, media-style notification, and routes hardware/notification media buttons. |
| `app/src/main/java/com/lagradost/quicknovel/ReadActivityViewModel.kt` | Owns TTS state, the playback thread, public control methods, and the observable LiveData. |
| `app/src/main/java/com/lagradost/quicknovel/ReadActivity2.kt` | UI: observes state, wires bottom-bar / d-pad controls, performs scrolling + highlight refresh. |
| `app/src/main/java/com/lagradost/quicknovel/ui/TextAdapter.kt` | Applies the visual highlight span to the currently-spoken line. |
| `app/src/main/java/com/lagradost/quicknovel/receivers/BecomingNoisyReceiver.kt` | Pauses on `ACTION_AUDIO_BECOMING_NOISY` (e.g. headphones unplugged). |

---

## 1. The state model (exact names)

### `TTSHelper.TTSStatus` — the play/pause/stop enum
Defined in `TTSHelper.kt:418-422`:

```kotlin
enum class TTSStatus {
    IsRunning,   // actively speaking
    IsPaused,    // paused, will resume on the same line
    IsStopped,   // not reading (the default / terminal state)
}
```

This is the single source of truth for whether read-aloud is happening.

### `TTSHelper.TTSActionType` — control intents
Defined in `TTSHelper.kt:424-429`:

```kotlin
enum class TTSActionType { Pause, Resume, Stop, Next }
```

Used by `BecomingNoisyReceiver` (`TTSSession`'s `event` callback) and the (now-disabled) media-session
callback path. The live media-session callback in `TTSNotifications.kt` instead calls ViewModel methods
directly (see §5). Validation of these actions happens in `ReadActivityViewModel.parseAction`
(`ReadActivityViewModel.kt:1637-1660`).

### `TTSHelper.TTSLine` — one utterance unit
Defined in `TTSHelper.kt:411-416`:

```kotlin
data class TTSLine(
    val speakOutMsg: String, // cleaned text handed to the OS TTS engine
    val startChar: Int,      // char offset within the chapter's rendered text
    val endChar: Int,
    val index: Int,          // chapter index (the "tag")
)
```

`startChar`/`endChar` are the load-bearing fields for highlighting — they map the utterance back to a
character range in the rendered chapter text.

---

## 2. Where the canonical TTS status lives

There are two coupled fields in `ReadActivityViewModel`:

- **Backing field**: `private var _currentTTSStatus: TTSHelper.TTSStatus` initialised to `IsStopped`
  (`ReadActivityViewModel.kt:1351`).
- **Public property with side effects**: `var currentTTSStatus`
  (`ReadActivityViewModel.kt:1352-1362`). Its setter:
  - is `synchronized(this)`,
  - calls `playDummySound()` (keeps an audio channel warm — `ReadActivityViewModel.kt:1762`),
  - **auto-starts the worker**: if transitioning `IsStopped -> IsRunning`, it calls `startTTSWorker()`
    (`ReadActivityViewModel.kt:1356-1358`),
  - posts the new value to the observable `_ttsStatus` LiveData and stores it in `_currentTTSStatus`.

So setting `currentTTSStatus = IsRunning` from a stopped state is what *boots the whole feature*.

### Observable mirrors (what the UI watches)

- `val ttsStatus: LiveData<TTSHelper.TTSStatus>` (`ReadActivityViewModel.kt:527`), backed by
  `_ttsStatus` (`:525-526`, default `IsStopped`). **This is the primary observable for "is TTS active".**
- `val ttsLine: LiveData<TTSHelper.TTSLine?>` (`ReadActivityViewModel.kt:531`), backed by `_ttsLine`
  (`:529-530`). Emits the currently-spoken line (or `null` when nothing is being spoken). Drives the
  highlight.
- `val ttsTimeRemaining: MutableLiveData<Long?>` (`ReadActivityViewModel.kt:1901`) — sleep-timer
  countdown; `null` when no timer / stopped.
- `val ttsTimerLive: MutableLiveData<Long>` (`ReadActivityViewModel.kt:1896`) — the configured sleep
  timer duration (preference `EPUB_SLEEP_TIMER`).

> **For the future "hide title while reading aloud" feature**, observe `ttsStatus` and treat
> `status != TTSHelper.TTSStatus.IsStopped` as "active" (this includes the paused state). This is
> exactly the predicate the existing UI already uses (see §6). If you want "actively speaking only"
> (exclude paused), use `status == TTSHelper.TTSStatus.IsRunning`, which mirrors the synchronous
> helper `viewModel.isTTSRunning()` (`ReadActivityViewModel.kt:1412-1414`).

---

## 3. Public control methods (ViewModel API)

All in `ReadActivityViewModel.kt`:

| Method | Lines | Effect |
| --- | --- | --- |
| `startTTS()` / `playTTS()` | 1384-1386 / 1400-1402 | set `currentTTSStatus = IsRunning` (auto-starts worker if stopped). |
| `pauseTTS()` | 1376-1382 | `IsRunning -> IsPaused` (only if TTS engine initialised). |
| `pausePlayTTS()` | 1404-1410 | toggle `IsRunning <-> IsPaused`. |
| `stopTTS()` | 1364-1366 | set `currentTTSStatus = IsStopped` (terminates the worker loop). |
| `forwardsTTS()` | 1388-1392 | `pendingTTSSkip += 1` (skip to next line). |
| `backwardsTTS()` | 1394-1398 | `pendingTTSSkip -= 1` (skip back a line / chapter). |
| `setTTSLanguage(Locale?)` / `setTTSVoice(Voice?)` | 1368-1374 | delegate to `TTSSession`. |
| `isTTSRunning()` | 1412-1414 | synchronous `currentTTSStatus == IsRunning`. |

`pendingTTSSkip` (`ReadActivityViewModel.kt:1350`) is an integer accumulator consumed by the playback
loop to seek forward/back without restarting the engine.

### Where these are called from the UI (`ReadActivity2.kt`)
Bottom-bar TTS controls are bound at `ReadActivity2.kt:811-830`:
- `binding.readActionTts` -> `startTTS()` (the "start reading aloud" button),
- `ttsActionPausePlay` -> `pausePlayTTS()`, `ttsActionStop` -> `stopTTS()`,
- `ttsActionForward` -> `forwardsTTS()`, `ttsActionBack` -> `backwardsTTS()`.

Hardware volume / d-pad keys are intercepted in `ReadActivity2.kt:244-261`: while
`viewModel.isTTSRunning()` they call `forwardsTTS()` / `backwardsTTS()`.

Chapter switching forcibly stops TTS: `seekToChapter` sets `currentTTSStatus = IsStopped` if not
already stopped (`ReadActivityViewModel.kt:1724-1726`). The reader also stops TTS on
`onStop`/teardown (`ReadActivity2.kt:681`).

---

## 4. Startup -> playback thread flow

1. UI calls `startTTS()` -> setter sees `IsStopped -> IsRunning` -> `startTTSWorker()`
   (`ReadActivityViewModel.kt:1419-1421`).
2. `startTTSWorker()` calls `TTSNotificationService.start(viewModel, context)`
   (`TTSNotificationService.kt:32-55`): it manages a binary `Semaphore` (`isRunning`, permit 1) to
   ensure a single instance, cancels any prior job, stores the ViewModel in a `WeakReference`, and
   starts the foreground service.
3. `TTSNotificationService.onCreate()` (`:58-112`):
   - sets up the media session (`TTSNotifications.setMediaSession`),
   - calls `startForeground(...)` with an `IsRunning` notification,
   - launches `currentJob = ioSafe { viewModel.startTTSThread() ... }` — **the actual reading loop runs
     inside the foreground service's coroutine**, and the service stops itself when the loop ends.
4. `ReadActivityViewModel.startTTSThread()` (`ReadActivityViewModel.kt:1423-1635`) is the core loop —
   guarded by `ttsThreadMutex` so only one thread runs. See §7.

The TTS engine itself is wrapped by `TTSSession` (`TTSHelper.kt:37-293`), held on the ViewModel as
`var ttsSession: TTSSession?` (`ReadActivityViewModel.kt:1342`), created in `initTTSSession`
(`:1344-1348`) which is invoked during reader init (`:1189`). `TTSSession` lazily initialises the
Android `TextToSpeech` engine on first `requireTTS()` (`TTSHelper.kt:156-248`), manages audio focus
(`AudioFocusRequest`, `:281-291`), registers `BecomingNoisyReceiver`, restores saved voice/language
from keys `EPUB_VOICE` / `EPUB_LANG`, and tracks utterance progress via `TTSStartSpeakId` /
`TTSEndSpeakId` counters set by an `UtteranceProgressListener` (`:213-240`).

---

## 5. Text chunking into utterances

Chapter text is parsed into `TTSLine`s lazily, per chapter:
`LiveChapterData.ttsLines` (`ReadActivityViewModel.kt:452-454`) calls
`TTSHelper.ttsParseText(rendered, index)`.

`TTSHelper.ttsParseText(text, tag)` (`TTSHelper.kt:619-735`) splits text into sentence-sized
utterances:
- normalises decimals/abbreviations (`Dr.`, `Mr.`, `Mrs.`) so the engine doesn't pause oddly
  (`:620-626`),
- skips leading punctuation/quote characters (`invalidStartChars`, `:637-641`),
- ends a line at the nearest of `endingCharacters = [".", "\n", ";", "?", ":"]` (`:642`, `:655-664`),
- strips characters that read badly (`-`, `<`, `>`, `_`, `«`, em-dash, zero-width joiner, etc.,
  `:694-715`),
- emits a `TTSLine(speakOutMsg, startChar = index, endChar, index = tag)` only when
  `isValidSpeakOutMsg` passes (non-blank, contains an alphanumeric, `:615-617`, `:721-723`).

Note `startChar`/`endChar` index into the *original* (un-cleaned) text so highlighting stays aligned;
`debugAssert` checks the cleaned text length is unchanged (`:630-632`).

Separately, the *display* text is parsed into `TextSpan`s by line via `parseTextToSpans` /
`parseSpan` (`TTSHelper.kt:608-613`, `:487-533`) — these back the RecyclerView rows.

---

## 6. Highlighting the spoken line

1. The playback loop posts the active utterance: `_ttsLine.postValue(line)`
   (`ReadActivityViewModel.kt:1558`), and `null` on teardown (`:1632`).
2. `ReadActivity2` observes it: `observeNullable(viewModel.ttsLine) { line -> updateTTSLine(line) }`
   (`ReadActivity2.kt:849-851`).
3. `ReadActivity2.updateTTSLine(line)` (`ReadActivity2.kt:442-523`):
   - pushes the line to the adapter (`textAdapter.updateTTSLine(line)`) and refreshes visible rows,
   - if `viewModel.ttsLock` is on (`ReadActivityViewModel.kt:1796`), computes a lock range
     (`lockTop`/`lockBottom`) and auto-scrolls so the spoken line stays on screen, recursing up to
     depth 3 if the line isn't currently laid out.
4. `TextAdapter.updateTTSLine(line)` stores `currentTTSLine` (`TextAdapter.kt:340-341`, field at
   `:269`); each bound row calls `updateTTSLine(binding, span, currentTTSLine)`
   (`TextAdapter.kt:698-718`), which maps `line.startChar/endChar` into the row's local offsets and
   calls `setHighLightedText` / `removeHighLightedText`.
5. `setHighLightedText(tv, start, end)` (`TextAdapter.kt:142-165`) applies an
   `android.text.Annotation("", "rounded")` span over the spoken range; `removeHighLightedText`
   (`:124-140`) strips any prior `"rounded"` annotation. (A rounded-background drawing pass renders
   the annotation visually.)

---

## 7. The playback loop & chapter advancement

`ReadActivityViewModel.startTTSThread()` (`ReadActivityViewModel.kt:1423-1635`):

- Reads the start position from `desiredTTSIndex ?: desiredIndex` (`:1430`). `desiredTTSIndex` is kept
  in sync with the first fully-visible line on scroll (`:661`) and reset on `seekToChapter` (`:1740`).
- Registers the session, applies `ttsSpeed`/`ttsPitch` (`:1434-1436`).
- Optional sleep timer: `ttsEndTime = startTime + ttsTimer`; when it elapses it forces
  `currentTTSStatus = IsStopped` and posts `ttsTimeRemaining` updates (`:1426-1428`, `:1519-1526`).
- **Outer loop** runs `while (isActive && currentTTSStatus != IsStopped)` (`:1464`), loading the
  chapter at `index`, handling `Resource.Loading/Failure/Success` (`:1465-1486`), updating the reader
  index, and preloading the next chapter (`:1507-1513`).
- **Inner loop** over `ttsInnerIndex` within `lines` (`:1515-1601`):
  - speaks the current line and queues the next via `ttsSession.speak(line, nextLine) { ... }`
    (`:1561-1566`),
  - `ttsSession.waitForOr(...)` blocks until the OS reports the utterance done *or* the action
    predicate (`currentTTSStatus != IsRunning || pendingTTSSkip != 0`) becomes true, then interrupts
    (`:1572-1576`),
  - **pause handling**: while `currentTTSStatus == IsPaused` it sleeps in 100 ms ticks and adds that
    duration back to `ttsEndTime`; on resume it re-notifies and continues on the same line
    (`:1580-1593`),
  - **skip handling**: applies `pendingTTSSkip` to `ttsInnerIndex` else advances by 1 (`:1595-1600`).
- **Chapter advancement** (`:1606-1614`): when the inner index runs off the end (`ttsInnerIndex > 0`
  or the chapter had no lines) it does `index++` and resets `ttsInnerIndex = 0`; a negative inner index
  (from skipping back past the start) wraps to the previous chapter (`index--`, with negative inner
  index wrapped via `+= lines.size` at `:1501-1503`).
- **Finally block** (`:1621-1634`): forces `currentTTSStatus = IsStopped`, fires a final `IsStopped`
  notification (which cancels it), interrupts/unregisters the session, and posts `_ttsLine = null`,
  `ttsTimeRemaining = null`.

---

## 8. Media-session notification & controls

`TTSNotifications` (`TTSNotifications.kt`) owns a global `var mediaSession: MediaSessionCompat?`
(`:51`).

- `setMediaSession(viewModel, book, context)` (`:53-121`): builds the session, sets album-art/author
  metadata, and installs a `MediaSessionCompat.Callback.onMediaButtonEvent` (`:66-103`) that maps
  hardware media keys to ViewModel methods:
  - `KEYCODE_MEDIA_PLAY_PAUSE -> pausePlayTTS()`
  - `KEYCODE_MEDIA_PAUSE -> pauseTTS()`, `KEYCODE_MEDIA_PLAY -> playTTS()`
  - `KEYCODE_MEDIA_STOP -> stopTTS()`
  - `KEYCODE_MEDIA_NEXT / FAST_FORWARD / SKIP_FORWARD / STEP_FORWARD -> forwardsTTS()`
  - `KEYCODE_MEDIA_PREVIOUS / REWIND -> backwardsTTS()`
- `createNotification(title, chapter, icon, status, context)` (`:128-272`): a media-style
  notification (channel `QuickNovelTTS`, id `TTS_NOTIFICATION_ID = 133742`, `:25-26`). It branches on
  `status`:
  - `IsStopped` -> cancels the notification and returns `null` (`:137-140`),
  - `IsRunning` -> actions Rewind / Stop / **Pause** / Fast-Forward (`:216-221`),
  - `IsPaused` -> actions Rewind / Stop / **Play** / Fast-Forward (`:223-228`).
  Action buttons are `PlaybackStateCompat` pending intents delivered through `MediaButtonReceiver`.
- `notify(...)` (`:274-300`) posts it (respecting `POST_NOTIFICATIONS` permission). The playback loop
  calls it on each chapter and on state changes via the local `notify()` helper
  (`ReadActivityViewModel.kt:1488-1497`).
- `TTSNotificationService.onStartCommand` forwards intents to the session via
  `MediaButtonReceiver.handleIntent` (`TTSNotificationService.kt:132-138`); `onDestroy` releases the
  session and the semaphore (`:114-130`).

---

## 9. Quick reference: "TTS is currently active/playing" symbols

Observe these to react to read-aloud state (e.g. to hide the title while reading aloud):

| Symbol | Type | File:line | "Active" predicate |
| --- | --- | --- | --- |
| `ReadActivityViewModel.ttsStatus` | `LiveData<TTSHelper.TTSStatus>` | `ReadActivityViewModel.kt:527` (backing `_ttsStatus` at `:525-526`) | `status != TTSHelper.TTSStatus.IsStopped` (active incl. paused) — same check the UI uses at `ReadActivity2.kt:908`. Use `== IsRunning` for "speaking only". |
| `ReadActivityViewModel.currentTTSStatus` | `var TTSHelper.TTSStatus` (synchronous, non-observable; backing `_currentTTSStatus`) | `ReadActivityViewModel.kt:1352-1362` (backing `:1351`) | `!= IsStopped` / `== IsRunning` |
| `ReadActivityViewModel.isTTSRunning()` | `fun(): Boolean` | `ReadActivityViewModel.kt:1412-1414` | returns `currentTTSStatus == IsRunning` |
| `ReadActivityViewModel.ttsLine` | `LiveData<TTSHelper.TTSLine?>` | `ReadActivityViewModel.kt:531` (backing `_ttsLine` at `:529-530`) | non-`null` while a line is being spoken; `null` when stopped |
| `TTSHelper.TTSStatus` | enum `{ IsRunning, IsPaused, IsStopped }` | `TTSHelper.kt:418-422` | the values themselves |

### Existing precedent in the codebase
`ReadActivity2.kt:907-925` already observes `ttsStatus` and derives
`val isTTSRunning = status != TTSHelper.TTSStatus.IsStopped`, then:
- `binding.readerBottomView.isGone = isTTSRunning` (hides the normal bottom bar),
- `binding.readerBottomViewTts.isVisible = isTTSRunning` (shows the TTS control bar),
- swaps the play/pause icon based on `IsPaused` vs `IsRunning`.

A "hide the title while reading aloud" feature should add an `observe(viewModel.ttsStatus)` block (or
extend the existing one at `ReadActivity2.kt:907`) and toggle the toolbar title visibility on the same
`status != TTSHelper.TTSStatus.IsStopped` predicate. The toolbar title itself is set from a separate
observer at `ReadActivity2.kt:853-855` (`viewModel.title` -> `binding.readToolbar.title`).
