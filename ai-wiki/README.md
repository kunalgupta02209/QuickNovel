# QuickNovel — AI Wiki

Business-logic documentation for the QuickNovel Android app, grouped by module. Each page describes *how a subsystem works* (data flow, key types, important behaviors) rather than listing files — start here, then open the page for the area you're working on.

> Generated for AI assistants and developers. References point at real `file:line` locations; verify against source before relying on a detail, as line numbers drift.

## High-level picture

QuickNovel scrapes ~50 web-novel sites ("providers"), lets the user read novels in-app, download them as EPUB, and listen via TTS. The dominant data flow is:

```
Provider (MainAPI)  →  APIRepository (cache/ad-strip/rate-limit)  →  ViewModel  →  Fragment/Activity
```

Persistence is Jackson-over-SharedPreferences (no Room). Networking is NiceHttp with an optional Cloudflare-bypass client. EPUB read/write uses a vendored `me.ag2s` library.

## Modules

| # | Module | What it covers |
|---|--------|----------------|
| 01 | [Providers & Scraping](01-providers-and-scraping.md) | `MainAPI` contract, the shared data model, builder helpers, `APIRepository`, provider registration in `Apis.kt`, Cloudflare handling, shared base classes, and how to add a provider. |
| 02 | [Reader](02-reader.md) | The in-app reader (`ReadActivity2` / `ReadActivityViewModel`): book loading (EPUB vs live stream), chapter paging, progress tracking, typography/theme, UI chrome, and TTS integration. |
| 03 | [Downloader](03-downloader.md) | `BookDownloader2` + WorkManager: queuing, chapter fetch/assembly, progress/state, notifications, pause/resume/delete, EPUB packaging, and SAF storage. |
| 04 | [TTS / Read Aloud](04-tts.md) | `TTSHelper` + media-session notification: start/pause/stop, the `TTSStatus` state machine, text chunking & line highlighting, chapter advancement, and the observable TTS-active state. |
| 05 | [Data & Persistence](05-data-and-persistence.md) | `DataStore` key/value model, the main persisted domains (history, bookmarks, reading progress, downloads, settings), serialization, and backup/restore. |
| 06 | [UI](06-ui.md) | Single-Activity + Fragments + AndroidX Navigation, the MVVM contract (`Resource<T>`, `safeApiCall`, `Event`), search fan-out, the `loadResult` detail flow, and deep links. |
| 07 | [Networking, Translation & Utils](07-networking-translation-utils.md) | The dual NiceHttp clients, `CloudflareKiller`, ML Kit + online translation, coroutine/parallel-map helpers, Coil image loading, language helpers, and the in-app updater. |
| 08 | [EPUB & UMD Libraries](08-epub-umd-libraries.md) | The vendored `me.ag2s` EPUB library (data model + read/write entry points) and the (currently unused) UMD library. Treat as a vendored library. |

## Conventions worth knowing

- Wrap provider/network calls in `safeApiCall { }` / `safe { }` and build responses with the `newXxxResponse { }` helpers (they normalize URLs and patch Cloudflare poster headers).
- Providers live under `app/src/main/java/com/lagradost/quicknovel/providers/` and **must** also be registered in `util/Apis.kt`.
- The `me.ag2s` namespace is third-party vendored code (LGPL) — keep modifications minimal.
- See the repo root [`CLAUDE.md`](../CLAUDE.md) for build/test commands and the top-level architecture summary.
